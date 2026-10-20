package com.portable.musicplayer

import android.app.Application
import android.content.Intent
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.portable.musicplayer.data.LoopMode
import com.portable.musicplayer.data.MediaRepository
import com.portable.musicplayer.data.Settings
import com.portable.musicplayer.data.Track
import com.portable.musicplayer.player.AudioEffects
import com.portable.musicplayer.player.EqBands
import com.portable.musicplayer.player.EqPresets
import com.portable.musicplayer.player.EqSettings
import com.portable.musicplayer.player.LiveAnalyzer
import com.portable.musicplayer.player.LiveEffects
import com.portable.musicplayer.player.LiveEvent
import com.portable.musicplayer.player.LiveKinds
import com.portable.musicplayer.player.LiveSettings
import com.portable.musicplayer.player.LiveVenues
import com.portable.musicplayer.player.LoudnessAnalyzer
import com.portable.musicplayer.player.PlaybackEngine
import com.portable.musicplayer.player.liveEventsFromJson
import com.portable.musicplayer.player.liveEventsToJson
import com.portable.musicplayer.player.normalizationGainDb
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.util.Locale
import kotlin.math.roundToInt

// -- navigation ---------------------------------------------------------------

sealed interface Screen {
    object NowPlaying : Screen
    object FoldersRoot : Screen
    data class Folder(val path: String) : Screen
    object PlaylistsRoot : Screen
    data class PlaylistScreen(val name: String) : Screen
    object SettingsScreen : Screen
    object EqualizerScreen : Screen
    object LiveScreen : Screen
    object LicensesScreen : Screen
}

enum class RowKind { ACTION, FOLDER, TRACK, PLAYLIST, INFO }

sealed interface RowAction {
    data class OpenFolder(val path: String) : RowAction
    data class PlayFolderAll(val path: String) : RowAction
    data class PlayTrack(val path: String) : RowAction
    object NewPlaylist : RowAction
    data class OpenPlaylist(val name: String) : RowAction
    data class PlayPlaylist(val name: String) : RowAction
    data class DeletePlaylist(val name: String) : RowAction
    object CycleLoop : RowAction
    object ToggleShuffle : RowAction
    object OpenEqualizer : RowAction
    object OpenLive : RowAction
    object OpenLicenses : RowAction
    data class OpenUrl(val url: String) : RowAction
    data class AddToPlaylist(val playlist: String, val path: String) : RowAction
    data class RemoveFromPlaylist(val playlist: String, val path: String) : RowAction
}

data class RowData(
    val kind: RowKind,
    val label: String,
    val sub: String = "",
    val badge: String = "",
    val icon: String = "play",
    val playing: Boolean = false,
    val action: RowAction? = null,
)

data class UiState(
    val screen: Screen = Screen.NowPlaying,
    val canGoBack: Boolean = false,
    val bottomActive: String? = null,
    val title: String = "",
    val hint: String = "",
    /** Stable id of the list currently on screen ("" when not a list). */
    val listKey: String = "",
    val rows: List<RowData> = emptyList(),
    val current: Track? = null,
    val index: Int = 0,
    val total: Int = 0,
    val positionMs: Long = 0,
    val durationMs: Long = 0,
    val playing: Boolean = false,
    val loopMode: String = LoopMode.ALL,
    val shuffle: Boolean = false,
    val sampleRate: Int = 0,
    val osd: String? = null,
    val loading: Boolean = false,
    val eqEnabled: Boolean = true,
    val eqNormalize: Boolean = true,
    val eqPreset: String = "flat",
    val eqGains: List<Float> = List(EqBands.COUNT) { 0f },
    val eqBass: Float = 0f,
    val eqPreamp: Float = 0f,
    val eqCustomPresets: Map<String, List<Float>> = emptyMap(),
    // live effect
    val liveEnabled: Boolean = false,
    val liveVenue: String = "arena",
    val liveIntensity: Float = 0.6f,
    val liveCrowd: Float = 0.5f,
    val liveWidth: Float = 0.6f,
    val liveAutoShow: Boolean = true,
    val liveReady: Boolean = false,
    val liveStatus: String = "",
    val liveManual: List<LiveEvent> = emptyList(),
)

const val FORMAT_HINT =
    "対応: MP3 / M4A / AAC / FLAC / WAV / AIFF / OGG / Opus / ALAC / AMR"

/** Owns the library, playback engine, playlists and the navigation stack. */
class PlayerViewModel(app: Application) : AndroidViewModel(app) {

    private val appContext: Application = app
    private val repo = MediaRepository(app)
    private val store = Settings(app)
    val engine = PlaybackEngine(app)

    private var allTracks: List<Track> = emptyList()
    private val byPath = HashMap<String, Track>()
    private val _state = MutableStateFlow(UiState())
    val state: StateFlow<UiState> = _state

    private val backStack = ArrayDeque<Screen>()
    private var restored = false
    private var normalizationDb = 0f
    private var normalizeJob: Job? = null

    private val liveAutoCache = HashMap<String, List<LiveEvent>>()
    private val liveManualCache = HashMap<String, MutableList<LiveEvent>>()
    private var liveAnalysisJob: Job? = null

    init {
        engine.loopMode = store.loopMode
        engine.shuffle = store.shuffle
        engine.setVolume(store.volume)
        _state.update { it.copy(loopMode = engine.loopMode, shuffle = engine.shuffle) }

        loadEqState()
        pushEq()
        LiveEffects.init(app)
        loadLiveState()
        pushLive()
        engine.connect()

        viewModelScope.launch {
            engine.queue.collect { q ->
                _state.update { it.copy(total = q.size) }
                store.queuePaths = q.map { it.path }
                rebuildRows()
            }
        }
        viewModelScope.launch {
            engine.index.collect { i ->
                // Prefer the fully-tagged library entry, else fall back to the
                // metadata carried by the session (activity recreated mid-play).
                val track = engine.currentTrack()?.let { byPath[it.path] ?: it }
                _state.update { it.copy(index = i + 1, current = track) }
                if (i >= 0) {
                    store.queueIndex = i
                    updateNormalization(track)
                    recompileLive()
                }
                rebuildRows()
            }
        }
        viewModelScope.launch { engine.playing.collect { p -> _state.update { it.copy(playing = p) } } }
        viewModelScope.launch {
            engine.duration.collect { d ->
                _state.update { it.copy(durationMs = d) }
                recompileLive()
            }
        }
        viewModelScope.launch {
            engine.sampleRate.collect { s ->
                _state.update { it.copy(sampleRate = s) }
                if (s > 0) LiveEffects.ensurePrepared(s, _state.value.liveVenue)
            }
        }
        viewModelScope.launch {
            engine.error.collect { e -> if (e != null) setHint("再生エラー: $e") }
        }
        viewModelScope.launch { engine.ready.collect { r -> if (r) tryRestoreQueue() } }

        // position ticker (+ periodic queue persistence)
        viewModelScope.launch {
            var ticks = 0
            while (true) {
                val pos = engine.position()
                LiveEffects.positionMs = pos
                val ready = LiveEffects.ready()
                if (_state.value.playing) {
                    _state.update { it.copy(positionMs = pos, liveReady = ready) }
                    ticks++
                    if (ticks % 10 == 0) {
                        store.queueIndex = engine.currentIndex.coerceAtLeast(0)
                        store.queuePosition = pos
                    }
                } else if (_state.value.liveReady != ready) {
                    _state.update { it.copy(liveReady = ready) }
                }
                delay(500)
            }
        }

        reload()
    }

    /** Restore the queue from the previous session (paused, at the last position). */
    private fun tryRestoreQueue() {
        if (restored) return
        if (!engine.ready.value) return
        if (allTracks.isEmpty()) return
        restored = true

        // The service may already be playing (e.g. the activity was recreated
        // after tapping the media notification). Adopt that queue instead of
        // restarting from the saved one, so playback is never interrupted.
        if (engine.queue.value.isNotEmpty()) {
            val cur = engine.currentTrack()?.let { byPath[it.path] ?: it }
            _state.update {
                it.copy(
                    current = cur,
                    total = engine.queue.value.size,
                    index = engine.currentIndex + 1,
                    positionMs = engine.position(),
                )
            }
            rebuildRows()
            return
        }

        val paths = store.queuePaths
        if (paths.isEmpty()) return
        val tracks = paths.mapNotNull { p -> byPath[p]?.takeIf { File(p).exists() } }
        if (tracks.isEmpty()) {
            store.clearQueue()
            return
        }
        val idx = store.queueIndex.coerceIn(0, tracks.size - 1)
        val pos = store.queuePosition
        engine.setQueue(tracks, idx, pos, play = false)
        _state.update {
            it.copy(
                current = engine.currentTrack(),
                total = tracks.size,
                index = idx + 1,
                positionMs = pos,
                durationMs = engine.duration(),
            )
        }
        rebuildRows()
    }

    // -- library --------------------------------------------------------------
    fun reload() {
        _state.update { it.copy(loading = true) }
        viewModelScope.launch {
            val tracks = withContext(Dispatchers.IO) { repo.loadTracks() }
            allTracks = tracks
            byPath.clear()
            tracks.forEach { byPath[it.path] = it }
            rebuildRows()
            _state.update { it.copy(loading = false) }
            tryRestoreQueue()

            // Enrich with exact spec info in the background.
            val enriched = withContext(Dispatchers.IO) { tracks.map { repo.enrich(it) } }
            allTracks = enriched
            byPath.clear()
            enriched.forEach { byPath[it.path] = it }
            val cur = engine.currentTrack()
            if (cur != null) _state.update { it.copy(current = byPath[cur.path] ?: cur) }
            rebuildRows()
        }
    }

    // -- transport ------------------------------------------------------------
    fun toggle() = engine.toggle()
    fun next() = engine.next()
    fun previous() = engine.previous()
    fun seek(ms: Long) {
        engine.seek(ms)
        _state.update { it.copy(positionMs = ms) }
        LiveEffects.positionMs = ms
        LiveEffects.resync()
    }

    fun cycleLoop() {
        val order = LoopMode.ORDER
        val idx = order.indexOf(engine.loopMode).coerceAtLeast(0)
        val mode = order[(idx + 1) % order.size]
        engine.loopMode = mode
        store.loopMode = mode
        _state.update { it.copy(loopMode = mode) }
        showOsd("リピート再生: ${LoopMode.label(mode)}")
        rebuildRows()
    }

    fun toggleShuffle() {
        engine.shuffle = !engine.shuffle
        store.shuffle = engine.shuffle
        _state.update { it.copy(shuffle = engine.shuffle) }
        showOsd("シャッフル再生: ${if (engine.shuffle) "オン" else "オフ"}")
        rebuildRows()
    }

    // -- equalizer / sound shaping --------------------------------------------
    private fun loadEqState() {
        _state.update {
            it.copy(
                eqEnabled = store.eqEnabled,
                eqNormalize = store.eqNormalize,
                eqPreset = store.eqPreset,
                eqGains = store.eqGains,
                eqBass = store.eqBass,
                eqPreamp = store.eqPreamp,
                eqCustomPresets = store.eqCustomPresets(),
            )
        }
    }

    /** Publish the current UI state to the audio pipeline (lock-free handoff). */
    private fun pushEq() {
        val s = _state.value
        AudioEffects.settings = EqSettings(
            enabled = s.eqEnabled,
            gains = s.eqGains.toFloatArray(),
            bass = s.eqBass,
            preampDb = s.eqPreamp,
            normalizationDb = normalizationDb,
        )
    }

    private fun updateEq(transform: (UiState) -> UiState) {
        _state.update(transform)
        val s = _state.value
        store.eqEnabled = s.eqEnabled
        store.eqNormalize = s.eqNormalize
        store.eqPreset = s.eqPreset
        store.eqGains = s.eqGains
        store.eqBass = s.eqBass
        store.eqPreamp = s.eqPreamp
        pushEq()
    }

    /** Analyse the current track (once) and fan its level out to the pipeline. */
    private fun updateNormalization(track: Track?) {
        normalizeJob?.cancel()
        normalizeJob = null
        if (track == null || !_state.value.eqNormalize) {
            if (normalizationDb != 0f) {
                normalizationDb = 0f
                pushEq()
            }
            return
        }
        val path = track.path
        val cached = store.loudness(path)
        if (cached != null) {
            normalizationDb = normalizationGainDb(cached)
            pushEq()
            return
        }
        normalizationDb = 0f
        pushEq()
        normalizeJob = viewModelScope.launch {
            val measured = withContext(Dispatchers.IO) { LoudnessAnalyzer.measure(path) }
            if (measured == null) return@launch
            store.putLoudness(path, measured)
            if (_state.value.current?.path != path) return@launch
            normalizationDb = normalizationGainDb(measured)
            pushEq()
        }
    }

    fun setEqEnabled(value: Boolean) = updateEq { it.copy(eqEnabled = value) }

    fun setEqNormalize(value: Boolean) {
        updateEq { it.copy(eqNormalize = value) }
        updateNormalization(_state.value.current)
    }

    fun setEqBand(index: Int, db: Float) {
        val gains = _state.value.eqGains.toMutableList()
        if (index !in gains.indices) return
        gains[index] = db.coerceIn(EqBands.GAIN_MIN, EqBands.GAIN_MAX)
        updateEq { it.copy(eqGains = gains, eqPreset = "custom") }
    }

    fun setEqBass(db: Float) =
        updateEq { it.copy(eqBass = db.coerceIn(0f, EqBands.BASS_MAX), eqPreset = "custom") }

    fun setEqPreamp(db: Float) =
        updateEq { it.copy(eqPreamp = db.coerceIn(EqBands.PREAMP_MIN, EqBands.PREAMP_MAX), eqPreset = "custom") }

    fun applyEqPreset(id: String) {
        val gains = EqPresets.GAINS[id]?.toList() ?: _state.value.eqCustomPresets[id] ?: return
        updateEq { it.copy(eqGains = gains, eqPreset = id) }
        showOsd("イコライザー: ${EqPresets.label(id)}")
    }

    fun resetEqFlat() {
        updateEq {
            it.copy(
                eqGains = List(EqBands.COUNT) { 0f },
                eqBass = 0f,
                eqPreamp = 0f,
                eqPreset = "flat",
            )
        }
    }

    fun saveEqPreset(name: String) {
        val trimmed = name.trim()
        if (trimmed.isEmpty()) return
        store.putEqCustomPreset(trimmed, _state.value.eqGains)
        updateEq { it.copy(eqCustomPresets = store.eqCustomPresets(), eqPreset = trimmed) }
        showOsd("プリセット「$trimmed」を保存しました")
    }

    // -- live effect ----------------------------------------------------------
    private fun loadLiveState() {
        val venue = store.liveVenue
        _state.update {
            it.copy(
                liveEnabled = store.liveEnabled,
                liveVenue = if (LiveVenues.VENUES.containsKey(venue)) venue else "arena",
                liveIntensity = store.liveIntensity.coerceIn(0f, 1f),
                liveCrowd = store.liveCrowd.coerceIn(0f, 1f),
                liveWidth = store.liveWidth.coerceIn(0f, 1f),
                liveAutoShow = store.liveAutoShow,
                liveReady = LiveEffects.ready(),
            )
        }
    }

    /** Publish the current Live settings to the audio pipeline. */
    private fun pushLive() {
        val s = _state.value
        LiveEffects.settings = LiveSettings(
            enabled = s.liveEnabled,
            venue = s.liveVenue,
            intensity = s.liveIntensity,
            crowd = s.liveCrowd,
            width = s.liveWidth,
            autoShow = s.liveAutoShow,
        )
    }

    private fun updateLive(transform: (UiState) -> UiState) {
        _state.update(transform)
        val s = _state.value
        store.liveEnabled = s.liveEnabled
        store.liveVenue = s.liveVenue
        store.liveIntensity = s.liveIntensity
        store.liveCrowd = s.liveCrowd
        store.liveWidth = s.liveWidth
        store.liveAutoShow = s.liveAutoShow
        pushLive()
        if (s.liveEnabled) {
            val rate = engine.sampleRate.value
            LiveEffects.ensurePrepared(if (rate > 0) rate else 44100, s.liveVenue)
        }
        LiveEffects.positionMs = engine.position()
        LiveEffects.resync()
    }

    fun setLiveEnabled(value: Boolean) = updateLive { it.copy(liveEnabled = value) }
    fun setLiveVenue(venue: String) {
        if (!LiveVenues.VENUES.containsKey(venue)) return
        updateLive { it.copy(liveVenue = venue) }
    }
    fun setLiveIntensity(v: Float) = updateLive { it.copy(liveIntensity = v.coerceIn(0f, 1f)) }
    fun setLiveCrowd(v: Float) = updateLive { it.copy(liveCrowd = v.coerceIn(0f, 1f)) }
    fun setLiveWidth(v: Float) = updateLive { it.copy(liveWidth = v.coerceIn(0f, 1f)) }
    fun setLiveAutoShow(value: Boolean) {
        updateLive { it.copy(liveAutoShow = value) }
        recompileLive()
    }

    private fun manualFor(path: String): MutableList<LiveEvent> {
        liveManualCache[path]?.let { return it }
        val arr = store.liveManual(path)
        val list = if (arr != null) liveEventsFromJson(arr).toMutableList() else mutableListOf()
        liveManualCache[path] = list
        return list
    }

    private fun autoFor(path: String): List<LiveEvent> {
        liveAutoCache[path]?.let { return it }
        val rec = store.liveAnalysis(path)
        if (rec != null) {
            val mtime = rec.optLong("mtime", -1L)
            val version = rec.optInt("v", -1)
            if (mtime == File(path).lastModified() && version == LiveAnalyzer.VERSION) {
                val events = liveEventsFromJson(rec.optJSONArray("events") ?: org.json.JSONArray())
                liveAutoCache[path] = events
                return events
            }
        }
        return emptyList()
    }

    /** (Re)build the reaction list for the current track and hand it to the DSP. */
    private fun recompileLive() {
        val track = _state.value.current
        if (track == null) {
            LiveEffects.compileTrack(0L, emptyList())
            return
        }
        val manual = manualFor(track.path)
        val auto = if (_state.value.liveAutoShow) autoFor(track.path) else emptyList()
        val events = ArrayList<LiveEvent>(auto.size + manual.size)
        events.addAll(auto)
        events.addAll(manual)
        LiveEffects.compileTrack(engine.duration(), events)
        if (_state.value.liveManual != manual.toList()) {
            _state.update { it.copy(liveManual = manual.toList()) }
        }
        if (_state.value.liveAutoShow && !liveAutoCache.containsKey(track.path)) {
            scheduleAnalysis(track.path)
        }
    }

    private fun scheduleAnalysis(path: String) {
        liveAnalysisJob?.cancel()
        liveAnalysisJob = viewModelScope.launch {
            _state.update { it.copy(liveStatus = "楽曲を解析しています…") }
            val events = withContext(Dispatchers.IO) { LiveAnalyzer.analyze(path) } ?: emptyList()
            store.putLiveAnalysis(
                path,
                JSONObject()
                    .put("mtime", File(path).lastModified())
                    .put("v", LiveAnalyzer.VERSION)
                    .put("events", liveEventsToJson(events)),
            )
            liveAutoCache[path] = events
            if (_state.value.current?.path == path) {
                _state.update { it.copy(liveStatus = "") }
                recompileLive()
            }
        }
    }

    fun addLiveCheer(kind: String = "cheer") {
        val track = _state.value.current ?: return
        val list = manualFor(track.path)
        val posSec = maxOf(0L, _state.value.positionMs) / 1000.0
        list.add(LiveEvent(posSec, if (kind in LiveKinds.KINDS) kind else "cheer", 0.8f, "manual"))
        list.sortBy { it.time }
        store.putLiveManual(track.path, liveEventsToJson(list))
        recompileLive()
        showOsd("歓声を追加: ${LiveKinds.label(kind)}")
    }

    fun removeLiveCheer(index: Int) {
        val track = _state.value.current ?: return
        val list = manualFor(track.path)
        if (index !in list.indices) return
        list.removeAt(index)
        store.putLiveManual(track.path, liveEventsToJson(list))
        recompileLive()
    }

    fun clearLiveCheers() {
        val track = _state.value.current ?: return
        val list = manualFor(track.path)
        if (list.isEmpty()) return
        list.clear()
        store.putLiveManual(track.path, liveEventsToJson(list))
        recompileLive()
        showOsd("歓声をすべて削除しました")
    }

    // -- navigation -----------------------------------------------------------
    fun openFoldersRoot() = goto(Screen.FoldersRoot, reset = true, active = "folders")
    fun openPlaylistsRoot() = goto(Screen.PlaylistsRoot, reset = true, active = "playlists")
    fun openSettings() = goto(Screen.SettingsScreen, reset = true, active = "settings")
    fun openEqualizer() = goto(Screen.EqualizerScreen, active = "settings")
    fun openLive() = goto(Screen.LiveScreen, active = "settings")
    fun openLicenses() = goto(Screen.LicensesScreen, active = "settings")
    fun openFolder(path: String) = goto(Screen.Folder(path), active = "folders")
    fun openPlaylist(name: String) = goto(Screen.PlaylistScreen(name), active = "playlists")

    fun showNowPlaying() {
        backStack.clear()
        _state.update { it.copy(screen = Screen.NowPlaying, bottomActive = null, canGoBack = false) }
    }

    fun back() {
        if (_state.value.screen == Screen.NowPlaying) return
        val prev = if (backStack.isNotEmpty()) backStack.removeLast() else Screen.NowPlaying
        val active = when (prev) {
            Screen.FoldersRoot, is Screen.Folder -> "folders"
            Screen.PlaylistsRoot, is Screen.PlaylistScreen -> "playlists"
            Screen.SettingsScreen, Screen.EqualizerScreen, Screen.LiveScreen,
            Screen.LicensesScreen -> "settings"
            else -> null
        }
        _state.update { it.copy(screen = prev, bottomActive = active, canGoBack = backStack.isNotEmpty()) }
        rebuildRows()
    }

    private fun goto(screen: Screen, reset: Boolean = false, active: String? = null) {
        if (reset) backStack.clear() else if (_state.value.screen != Screen.NowPlaying) backStack.addLast(_state.value.screen)
        _state.update { it.copy(screen = screen, bottomActive = active, canGoBack = backStack.isNotEmpty()) }
        rebuildRows()
    }

    // -- row activation -------------------------------------------------------
    fun onRow(row: RowData) {
        when (val a = row.action) {
            is RowAction.OpenFolder -> openFolder(a.path)
            is RowAction.PlayFolderAll -> playFolder(a.path)
            is RowAction.PlayTrack -> playPath(a.path)
            is RowAction.OpenPlaylist -> openPlaylist(a.name)
            is RowAction.PlayPlaylist -> playPlaylist(a.name)
            is RowAction.DeletePlaylist -> deletePlaylist(a.name)
            RowAction.CycleLoop -> cycleLoop()
            RowAction.ToggleShuffle -> toggleShuffle()
            RowAction.OpenEqualizer -> openEqualizer()
            RowAction.OpenLive -> openLive()
            RowAction.OpenLicenses -> openLicenses()
            is RowAction.OpenUrl -> openUrl(a.url)
            is RowAction.AddToPlaylist -> addToPlaylist(a.playlist, a.path)
            is RowAction.RemoveFromPlaylist -> removeFromPlaylist(a.playlist, a.path)
            else -> Unit
        }
    }

    private fun openUrl(url: String) {
        try {
            val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            appContext.startActivity(intent)
        } catch (_: Throwable) {
            showOsd("リンクを開けませんでした")
        }
    }

    // -- playback from views --------------------------------------------------
    fun playFolder(path: String) {
        val tracks = tracksUnderFolder(path)
        if (tracks.isEmpty()) {
            setHint("対応する音楽ファイルが見つかりません")
            return
        }
        // "このフォルダをループ再生" must switch to folder loop, otherwise a
        // leftover single-track repeat would keep restarting one song.
        if (engine.loopMode != LoopMode.ALL) {
            engine.loopMode = LoopMode.ALL
            store.loopMode = LoopMode.ALL
            _state.update { it.copy(loopMode = LoopMode.ALL) }
            showOsd("リピート再生: ${LoopMode.label(LoopMode.ALL)}")
        }
        startQueue(tracks, 0)
    }

    fun playPath(path: String) {
        val list = currentScreenTracks()
        val idx = list.indexOfFirst { it.path == path }
        if (idx >= 0) {
            startQueue(list, idx)
            return
        }
        // Fall back to the folder that contains the file.
        val track = byPath[path] ?: fileOnlyTrack(path)
        if (track.folder.isNotEmpty()) {
            val folderTracks = allTracks.filter { it.folder == track.folder }
            val i = folderTracks.indexOfFirst { it.path == path }
            if (folderTracks.isNotEmpty() && i >= 0) {
                startQueue(folderTracks, i)
                return
            }
        }
        startQueue(listOf(track), 0)
    }

    fun playPlaylist(name: String, startPath: String? = null) {
        val tracks = resolvePlaylist(name)
        if (tracks.isEmpty()) {
            setHint("再生できる曲がありません")
            return
        }
        val start = tracks.indexOfFirst { it.path == startPath }.let { if (it >= 0) it else 0 }
        startQueue(tracks, start)
    }

    private fun startQueue(tracks: List<Track>, start: Int) {
        engine.setQueue(tracks, start)
        showNowPlaying()
        _state.update { it.copy(current = engine.currentTrack(), total = tracks.size, index = start + 1) }
        rebuildRows()
    }

    private fun currentScreenTracks(): List<Track> = when (val s = _state.value.screen) {
        is Screen.Folder -> allTracks.filter { it.folder == s.path }
        is Screen.PlaylistScreen -> resolvePlaylist(s.name)
        else -> listOfNotNull(_state.value.current)
    }

    private fun tracksUnderFolder(path: String): List<Track> {
        val prefix = path.trimEnd('/') + "/"
        return allTracks.filter { it.folder == path || it.folder.startsWith(prefix) }
    }

    private fun resolvePlaylist(name: String): List<Track> {
        val pl = store.playlist(name) ?: return emptyList()
        return pl.tracks.mapNotNull { p ->
            byPath[p]?.takeIf { File(p).exists() } ?: fileOnlyTrack(p).takeIf { File(p).exists() }
        }
    }

    private fun fileOnlyTrack(path: String): Track = byPath[path] ?: Track(
        id = -1,
        uri = android.net.Uri.fromFile(File(path)),
        path = path,
        folder = File(path).parent ?: "",
        title = File(path).nameWithoutExtension,
        artist = "", album = "", albumId = -1,
        durationMs = 0, mime = "",
    )

    // -- playlist editing -----------------------------------------------------
    fun createPlaylistFromQueue(name: String) {
        val trimmed = name.trim()
        if (trimmed.isEmpty()) return
        val paths = engine.queue.value.map { it.path }
        store.createPlaylist(trimmed, paths)
        rebuildRows()
    }

    fun createPlaylist(name: String, path: String?) {
        val trimmed = name.trim()
        if (trimmed.isEmpty()) return
        store.createPlaylist(trimmed, listOfNotNull(path))
        rebuildRows()
    }

    fun deletePlaylist(name: String) {
        store.deletePlaylist(name)
        if (_state.value.screen is Screen.PlaylistScreen) back() else rebuildRows()
    }

    fun addToPlaylist(name: String, path: String) {
        store.addToPlaylist(name, listOf(path))
        setHint("「$name」に追加しました")
    }

    fun removeFromPlaylist(name: String, path: String) {
        store.removeFromPlaylist(name, path)
        rebuildRows()
    }

    fun hideFolder(path: String) {
        store.hideFolder(path)
        val name = folderName(path)
        setHint("「$name」を一覧から削除しました")
        if (_state.value.screen is Screen.Folder) back() else rebuildRows()
    }

    fun playlistNames(): List<String> = store.playlists().map { it.name }

    // -- osd / hint -----------------------------------------------------------
    private fun showOsd(text: String) {
        _state.update { it.copy(osd = text) }
        viewModelScope.launch {
            delay(1300)
            if (_state.value.osd == text) _state.update { it.copy(osd = null) }
        }
    }

    private fun setHint(text: String) {
        _state.update { it.copy(hint = text) }
    }

    // -- rows -----------------------------------------------------------------
    private fun visibleFolders(): List<String> {
        val hidden = store.hiddenFolders
        return allTracks.asSequence()
            .map { it.folder }
            .filter { it.isNotEmpty() && it !in hidden }
            .distinct()
            .sortedWith(compareBy({ it.count { c -> c == '/' } }, { it.lowercase(Locale.ROOT) }))
            .toList()
    }

    private fun childFolders(path: String): List<String> {
        val prefix = path.trimEnd('/') + "/"
        return allTracks.asSequence().map { it.folder }
            .filter { it.startsWith(prefix) && it.removePrefix(prefix).isNotEmpty() && !it.removePrefix(prefix).contains('/') }
            .distinct().sortedBy { it.lowercase(Locale.ROOT) }.toList()
    }

    private fun eqSummary(s: UiState): String {
        if (!s.eqEnabled) return "オフ"
        val parts = ArrayList<String>()
        parts.add("10バンド")
        parts.add(EqPresets.label(s.eqPreset))
        if (s.eqBass > 0.05f) parts.add(String.format(Locale.US, "Bass %+.1f dB", s.eqBass))
        if (s.eqNormalize) parts.add("ノーマライズ")
        return parts.joinToString(" ・ ")
    }

    private fun liveSummary(s: UiState): String {
        if (!s.liveEnabled) return "オフ"
        val parts = ArrayList<String>()
        parts.add(LiveVenues.label(s.liveVenue))
        parts.add("強さ ${(s.liveIntensity * 100).roundToInt()}%")
        if (s.liveCrowd > 0.05f) parts.add("歓声 ${(s.liveCrowd * 100).roundToInt()}%")
        if (!s.liveAutoShow) parts.add("自動演出オフ")
        return parts.joinToString(" ・ ")
    }

    private fun folderName(path: String): String =
        path.trimEnd('/').substringAfterLast('/').ifBlank { path }

    private fun trackRow(t: Track): RowData {
        val cur = engine.currentTrack()
        val playing = cur != null && cur.path == t.path
        val spec = t.specText
        val dur = t.durationMs
        val sub = buildString {
            append(t.displayArtist)
            if (spec.isNotEmpty()) append("  ·  ").append(spec)
            if (dur > 0) append("  ·  ").append(fmtTime(dur))
        }
        return RowData(
            kind = RowKind.TRACK,
            label = t.displayTitle,
            sub = sub,
            badge = if (t.isHires) "HR" else "",
            icon = "play",
            playing = playing,
            action = RowAction.PlayTrack(t.path),
        )
    }

    private fun rebuildRows() {
        val s = _state.value
        val rows: List<RowData>
        var title = s.title
        var hint = s.hint
        val listKey = when (val sc = s.screen) {
            Screen.FoldersRoot -> "folders"
            is Screen.Folder -> "folder:${sc.path}"
            Screen.PlaylistsRoot -> "playlists"
            is Screen.PlaylistScreen -> "playlist:${sc.name}"
            Screen.SettingsScreen -> "settings"
            Screen.LicensesScreen -> "licenses"
            else -> ""
        }
        when (val screen = s.screen) {
            Screen.FoldersRoot -> {
                val folders = visibleFolders()
                val list = ArrayList<RowData>()
                for (f in folders) {
                    list.add(
                        RowData(
                            RowKind.FOLDER, folderName(f), f, icon = "folder",
                            action = RowAction.OpenFolder(f),
                        )
                    )
                }
                if (list.isEmpty()) {
                    list.add(RowData(RowKind.INFO, "フォルダがありません", "端末に音楽を追加してください", icon = "folder"))
                }
                rows = list
                title = "フォルダ"
                hint = if (folders.isNotEmpty())
                    "長押しで一覧から削除（本体は残ります）" else FORMAT_HINT
            }
            is Screen.Folder -> {
                val list = ArrayList<RowData>()
                val sub = childFolders(screen.path)
                val files = allTracks.filter { it.folder == screen.path }
                list.add(
                    RowData(
                        RowKind.ACTION, "▶ このフォルダをループ再生", "サブフォルダを含む全曲",
                        icon = "play", action = RowAction.PlayFolderAll(screen.path),
                    )
                )
                for (d in sub) {
                    list.add(RowData(RowKind.FOLDER, folderName(d), icon = "folder", action = RowAction.OpenFolder(d)))
                }
                for (t in files) list.add(trackRow(t))
                if (sub.isEmpty() && files.isEmpty()) {
                    list.add(RowData(RowKind.INFO, "音楽ファイルがありません", FORMAT_HINT))
                }
                rows = list
                title = folderName(screen.path)
                hint = FORMAT_HINT
            }
            Screen.PlaylistsRoot -> {
                val list = ArrayList<RowData>()
                list.add(RowData(RowKind.ACTION, "＋ 新規プレイリスト", "現在の曲／フォルダから作成", icon = "list", action = RowAction.NewPlaylist))
                val playlists = store.playlists()
                for (p in playlists) {
                    list.add(RowData(RowKind.PLAYLIST, p.name, "${p.tracks.size} 曲", icon = "list", action = RowAction.OpenPlaylist(p.name)))
                }
                if (playlists.isEmpty()) {
                    list.add(RowData(RowKind.INFO, "プレイリストはまだありません", "上の ＋ から作成"))
                }
                rows = list
                title = "プレイリスト"
                hint = s.hint.ifBlank { FORMAT_HINT }
            }
            is Screen.PlaylistScreen -> {
                val list = ArrayList<RowData>()
                val tracks = resolvePlaylist(screen.name)
                list.add(RowData(RowKind.ACTION, "▶ 全曲を再生", "${tracks.size} 曲", icon = "play", action = RowAction.PlayPlaylist(screen.name)))
                list.add(RowData(RowKind.ACTION, "🗑 このプレイリストを削除", "", icon = "back", action = RowAction.DeletePlaylist(screen.name)))
                if (tracks.isEmpty()) {
                    list.add(RowData(RowKind.INFO, "曲がありません", "曲の長押しから追加できます"))
                } else {
                    for (t in tracks) list.add(trackRow(t))
                }
                rows = list
                title = screen.name
                hint = if (tracks.isNotEmpty()) "長押しでリストから削除" else FORMAT_HINT
            }
            Screen.SettingsScreen -> {
                rows = listOf(
                    RowData(RowKind.ACTION, "ループ再生", "現在: ${LoopMode.label(s.loopMode)}", icon = "repeat", action = RowAction.CycleLoop),
                    RowData(RowKind.ACTION, "シャッフル", if (s.shuffle) "オン" else "オフ", icon = "shuffle", action = RowAction.ToggleShuffle),
                    RowData(RowKind.ACTION, "音質調整（イコライザー）", eqSummary(s), icon = "eq", action = RowAction.OpenEqualizer),
                    RowData(RowKind.ACTION, "ライブ演出（Live Effect）", liveSummary(s), icon = "volume", action = RowAction.OpenLive),
                    RowData(RowKind.ACTION, "ライセンス", "効果音クレジット / オープンソース", icon = "list", action = RowAction.OpenLicenses),
                    RowData(RowKind.INFO, "音量", "${store.volume} / 100", icon = "volume"),
                    RowData(RowKind.INFO, "対応フォーマット", FORMAT_HINT, icon = "list"),
                    RowData(RowKind.INFO, "ハイレゾ再生", "FLAC/WAV/AIFF 24bit 以上 (HR)", icon = "list"),
                    RowData(RowKind.INFO, "フォント", "SST JP 相当: Noto Sans JP", icon = "list"),
                    RowData(RowKind.INFO, "曲数", "${allTracks.size} 曲", icon = "list"),
                    RowData(RowKind.INFO, "バージョン", BuildConfig.VERSION_NAME, icon = "list"),
                )
                title = "設定"
                hint = "Louis Music Player for Android"
            }
            Screen.EqualizerScreen -> {
                rows = emptyList()
                title = "イコライザー"
                hint = "10バンド EQ / Bass Boost / 音量ノーマライズ"
            }
            Screen.LiveScreen -> {
                rows = emptyList()
                title = "ライブ演出"
                hint = "会場の響きと歓声で臨場感を再現"
            }
            Screen.LicensesScreen -> {
                rows = listOf(
                    RowData(RowKind.ACTION, "OtoLogic", "歓声・拍手の効果音 (CC BY 4.0)", icon = "list",
                        action = RowAction.OpenUrl("https://otologic.jp/")),
                    RowData(RowKind.ACTION, "OtoLogic 利用規約", "クレジット表記で無料利用", icon = "list",
                        action = RowAction.OpenUrl("https://otologic.jp/free/license.html")),
                    RowData(RowKind.ACTION, "CC BY 4.0", "クリエイティブ・コモンズ 表示 4.0", icon = "list",
                        action = RowAction.OpenUrl("https://creativecommons.org/licenses/by/4.0/deed.ja")),
                    RowData(RowKind.INFO, "会場の残響・環境音", "アプリ内で合成 (Louis Music Player)", icon = "list"),
                    RowData(RowKind.ACTION, "AndroidX / Jetpack Compose", "Apache License 2.0", icon = "list",
                        action = RowAction.OpenUrl("https://www.apache.org/licenses/LICENSE-2.0")),
                    RowData(RowKind.ACTION, "AndroidX Media3", "Apache License 2.0", icon = "list",
                        action = RowAction.OpenUrl("https://www.apache.org/licenses/LICENSE-2.0")),
                    RowData(RowKind.ACTION, "Kotlin", "Apache License 2.0", icon = "list",
                        action = RowAction.OpenUrl("https://www.apache.org/licenses/LICENSE-2.0")),
                    RowData(RowKind.ACTION, "Noto Sans JP", "SIL Open Font License 1.1", icon = "list",
                        action = RowAction.OpenUrl("https://openfontlicense.org/")),
                )
                title = "ライセンス"
                hint = "タップで該当ページを開きます"
            }
            Screen.NowPlaying -> {
                rows = s.rows
                title = s.title
                hint = s.hint
            }
        }
        _state.update { it.copy(rows = rows, title = title, hint = hint, listKey = listKey) }
    }

    // -- list scroll persistence ----------------------------------------------
    fun listScroll(key: String): Pair<Int, Int> = store.listScroll(key)

    fun saveListScroll(key: String, index: Int, offset: Int) = store.putListScroll(key, index, offset)

    fun setVolumePercent(v: Int) {
        store.volume = v
        engine.setVolume(v)
    }

    override fun onCleared() {
        super.onCleared()
        store.volume = engine.volumePercent()
        store.queueIndex = engine.currentIndex.coerceAtLeast(0)
        store.queuePosition = engine.position()
        // Detach from the service; playback keeps running in the background.
        engine.release()
    }
}

fun fmtTime(ms: Long): String {
    val total = (ms / 1000).coerceAtLeast(0)
    return String.format(Locale.US, "%02d:%02d", total / 60, total % 60)
}
