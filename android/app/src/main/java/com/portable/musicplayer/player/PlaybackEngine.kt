package com.portable.musicplayer.player

import android.content.ComponentName
import android.content.Context
import android.net.Uri
import androidx.core.content.ContextCompat
import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.Tracks
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import com.google.common.util.concurrent.ListenableFuture
import com.portable.musicplayer.data.LoopMode
import com.portable.musicplayer.data.Track
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * Transport front-end. Playback itself lives in [PlaybackService]; this class
 * drives it through a Media3 [MediaController] so the UI and the notification
 * both control the same player.
 *
 * Loop / shuffle rules mirror the desktop engine:
 *  * `off` – play the queue once
 *  * `all` – loop the whole folder / playlist
 *  * `one` – repeat the current track
 */
class PlaybackEngine(private val context: Context) {

    private val sessionToken = SessionToken(context, ComponentName(context, PlaybackService::class.java))
    private var future: ListenableFuture<MediaController>? = null
    private var controller: MediaController? = null

    private val _ready = MutableStateFlow(false)
    val ready: StateFlow<Boolean> = _ready

    private val _queue = MutableStateFlow<List<Track>>(emptyList())
    val queue: StateFlow<List<Track>> = _queue

    private val _index = MutableStateFlow(-1)
    val index: StateFlow<Int> = _index

    private val _playing = MutableStateFlow(false)
    val playing: StateFlow<Boolean> = _playing

    private val _duration = MutableStateFlow(0L)
    val duration: StateFlow<Long> = _duration

    private val _sampleRate = MutableStateFlow(0)
    val sampleRate: StateFlow<Int> = _sampleRate

    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error

    var loopMode: String = LoopMode.ALL
        set(value) {
            field = value
            controller?.repeatMode = repeatOf(value)
        }

    var shuffle: Boolean = false
        set(value) {
            field = value
            controller?.shuffleModeEnabled = value
        }

    private var volume: Int = 70

    private val listener = object : Player.Listener {
        override fun onIsPlayingChanged(isPlaying: Boolean) {
            _playing.value = isPlaying
        }

        override fun onPlaybackStateChanged(playbackState: Int) {
            if (playbackState == Player.STATE_READY) {
                val d = controller?.duration ?: 0L
                if (d > 0) _duration.value = d
                updateFormat()
            }
        }

        override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
            syncFromSession()
            _sampleRate.value = 0
            val d = controller?.duration ?: 0L
            _duration.value = if (d > 0) d else 0L
            updateFormat()
        }

        override fun onMediaMetadataChanged(mediaMetadata: MediaMetadata) {
            syncFromSession()
        }

        override fun onTracksChanged(tracks: Tracks) {
            updateFormat(tracks)
        }

        override fun onPlayerError(error: PlaybackException) {
            _error.value = error.message
        }
    }

    fun connect() {
        if (controller != null || future != null) return
        val f = MediaController.Builder(context, sessionToken).buildAsync()
        future = f
        f.addListener({
            try {
                val c = f.get()
                controller = c
                c.addListener(listener)
                c.repeatMode = repeatOf(loopMode)
                c.shuffleModeEnabled = shuffle
                c.volume = volume / 100f
                // Adopt whatever the service is already playing (e.g. when the
                // activity was recreated by tapping the media notification).
                syncFromSession()
                _playing.value = c.isPlaying
                val d = c.duration
                if (d > 0) _duration.value = d
                _ready.value = true
            } catch (_: Throwable) {
                // controller unavailable; UI stays idle
            }
        }, ContextCompat.getMainExecutor(context))
    }

    // -- query ----------------------------------------------------------------
    val currentIndex: Int get() = _index.value

    fun currentTrack(): Track? {
        val q = _queue.value
        val i = _index.value
        return if (i in q.indices) q[i] else null
    }

    fun isPlaying(): Boolean = controller?.isPlaying ?: false

    fun position(): Long = controller?.currentPosition?.coerceAtLeast(0) ?: 0L

    fun duration(): Long = controller?.duration?.let { if (it > 0) it else 0L } ?: 0L

    fun volumePercent(): Int = volume

    // -- queue ----------------------------------------------------------------
    fun setQueue(tracks: List<Track>, startIndex: Int = 0, startPositionMs: Long = 0L, play: Boolean = true) {
        if (tracks.isEmpty()) {
            stop()
            _queue.value = emptyList()
            _index.value = -1
            return
        }
        _queue.value = tracks
        val start = startIndex.coerceIn(0, tracks.size - 1)
        _error.value = null
        val c = controller ?: return
        val items = tracks.map { toMediaItem(it) }
        c.setMediaItems(items, start, startPositionMs)
        c.prepare()
        if (play) c.play()
        _index.value = start
    }

    fun play() {
        val c = controller ?: return
        if (c.mediaItemCount == 0) return
        if (c.playbackState == Player.STATE_IDLE) c.prepare()
        c.play()
    }

    fun pause() {
        controller?.pause()
    }

    fun toggle() {
        val c = controller ?: return
        if (c.isPlaying) c.pause() else play()
    }

    fun stop() {
        controller?.run {
            stop()
            clearMediaItems()
        }
    }

    fun seek(ms: Long) {
        controller?.seekTo(ms.coerceAtLeast(0))
    }

    fun next() {
        val c = controller ?: return
        if (c.mediaItemCount == 0) return
        if (c.hasNextMediaItem()) {
            c.seekToNextMediaItem()
        } else if (loopMode == LoopMode.ALL) {
            c.seekToDefaultPosition(0)
        } else {
            c.pause()
        }
    }

    fun previous() {
        val c = controller ?: return
        if (c.mediaItemCount == 0) return
        // Restart the track first (typical portable-player behaviour).
        if (c.currentPosition > 3000) {
            c.seekTo(0)
            return
        }
        if (c.hasPreviousMediaItem()) {
            c.seekToPreviousMediaItem()
        } else if (loopMode == LoopMode.ALL) {
            c.seekToDefaultPosition(c.mediaItemCount - 1)
        } else {
            c.seekTo(0)
        }
    }

    fun setVolume(percent: Int) {
        volume = percent.coerceIn(0, 100)
        controller?.volume = volume / 100f
    }

    /** Detach from the service player (the service keeps playing in background). */
    fun release() {
        controller?.removeListener(listener)
        controller = null
        future?.let { runCatching { MediaController.releaseFuture(it) } }
        future = null
        _ready.value = false
    }

    private fun toMediaItem(t: Track): MediaItem =
        MediaItem.Builder()
            .setUri(t.uri)
            .setMediaId(t.path)
            .setMediaMetadata(
                MediaMetadata.Builder()
                    .setTitle(t.displayTitle)
                    .setArtist(t.displayArtist)
                    .setAlbumTitle(t.displayAlbum)
                    .setArtworkUri(ArtworkUri.forPath(t.path))
                    .build()
            )
            .build()

    /**
     * Mirror the live session into the local queue flow. This keeps the UI in
     * sync when the session already has items (activity recreated from the
     * notification) or when the service advances to another track on its own.
     */
    private fun syncFromSession() {
        val c = controller ?: return
        val count = c.mediaItemCount
        if (count == 0) {
            if (_queue.value.isNotEmpty()) {
                _queue.value = emptyList()
                _index.value = -1
            }
            return
        }
        val samePaths = _queue.value.size == count &&
            _queue.value.indices.all { _queue.value[it].path == c.getMediaItemAt(it).mediaId }
        if (!samePaths) {
            val duration = c.duration.let { if (it > 0) it else 0L }
            _queue.value = (0 until count).map { trackFromItem(c.getMediaItemAt(it), duration) }
        }
        _index.value = c.currentMediaItemIndex
    }

    private fun trackFromItem(item: MediaItem, durationMs: Long): Track {
        val path = item.mediaId.ifBlank { item.localConfiguration?.uri?.path ?: "" }
        val md = item.mediaMetadata
        return Track(
            id = -1,
            uri = item.localConfiguration?.uri ?: Uri.EMPTY,
            path = path,
            folder = path.substringBeforeLast('/', ""),
            title = md.title?.toString() ?: "",
            artist = md.artist?.toString() ?: "",
            album = md.albumTitle?.toString() ?: "",
            albumId = 0,
            durationMs = durationMs,
            mime = "",
        )
    }

    private fun updateFormat() {
        controller?.let { updateFormat(it.currentTracks) }
    }

    private fun updateFormat(tracks: Tracks) {
        for (group in tracks.groups) {
            if (group.type != C.TRACK_TYPE_AUDIO) continue
            for (i in 0 until group.length) {
                if (!group.isTrackSelected(i)) continue
                val fmt: Format = group.getTrackFormat(i)
                if (fmt.sampleRate != Format.NO_VALUE && fmt.sampleRate > 0) {
                    _sampleRate.value = fmt.sampleRate
                }
            }
        }
    }

    private fun repeatOf(mode: String): Int = when (mode) {
        LoopMode.OFF -> Player.REPEAT_MODE_OFF
        LoopMode.ONE -> Player.REPEAT_MODE_ONE
        else -> Player.REPEAT_MODE_ALL
    }
}
