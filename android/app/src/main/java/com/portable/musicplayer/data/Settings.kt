package com.portable.musicplayer.data

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/** Loop modes, matching the desktop engine (`off` / `all` / `one`). */
object LoopMode {
    const val OFF = "off"
    const val ALL = "all"
    const val ONE = "one"
    val ORDER = listOf(OFF, ALL, ONE)

    fun label(mode: String): String = when (mode) {
        OFF -> "オフ"
        ALL -> "全曲（フォルダ）"
        ONE -> "1曲"
        else -> mode
    }
}

data class Playlist(val name: String, val tracks: MutableList<String> = mutableListOf())

/**
 * Persistent playlist + settings storage, backed by [android.content.SharedPreferences].
 * Playlists keep absolute file paths (they survive MediaStore id churn).
 */
class Settings(context: Context) {

    private val prefs = context.getSharedPreferences("music_player", Context.MODE_PRIVATE)

    var loopMode: String
        get() = prefs.getString("loop_mode", LoopMode.ALL) ?: LoopMode.ALL
        set(value) = prefs.edit().putString("loop_mode", value).apply()

    var shuffle: Boolean
        get() = prefs.getBoolean("shuffle", false)
        set(value) = prefs.edit().putBoolean("shuffle", value).apply()

    var volume: Int
        get() = prefs.getInt("volume", 70)
        set(value) = prefs.edit().putInt("volume", value.coerceIn(0, 100)).apply()

    var hiddenFolders: Set<String>
        get() = prefs.getStringSet("hidden_folders", emptySet()) ?: emptySet()
        set(value) = prefs.edit().putStringSet("hidden_folders", value).apply()

    fun hideFolder(path: String) {
        hiddenFolders = hiddenFolders + path
    }

    // -- last playback queue (restored on next launch) ------------------------
    var queuePaths: List<String>
        get() {
            val raw = prefs.getString("queue_paths", "[]") ?: "[]"
            return try {
                val arr = JSONArray(raw)
                (0 until arr.length()).map { arr.getString(it) }
            } catch (_: Throwable) {
                emptyList()
            }
        }
        set(value) = prefs.edit().putString("queue_paths", JSONArray(value).toString()).apply()

    var queueIndex: Int
        get() = prefs.getInt("queue_index", 0)
        set(value) = prefs.edit().putInt("queue_index", value.coerceAtLeast(0)).apply()

    var queuePosition: Long
        get() = prefs.getLong("queue_position", 0L)
        set(value) = prefs.edit().putLong("queue_position", value.coerceAtLeast(0)).apply()

    fun clearQueue() {
        prefs.edit().remove("queue_paths").remove("queue_index").remove("queue_position").apply()
    }

    // -- per-list scroll positions (restored on next launch) ------------------
    private fun listScrollMap(): JSONObject {
        val raw = prefs.getString("list_scroll", "{}") ?: "{}"
        return try {
            JSONObject(raw)
        } catch (_: Throwable) {
            JSONObject()
        }
    }

    /** Saved (firstVisibleItemIndex, offset) for a list screen key. */
    fun listScroll(key: String): Pair<Int, Int> {
        if (key.isEmpty()) return 0 to 0
        val arr = listScrollMap().optJSONArray(key) ?: return 0 to 0
        return arr.optInt(0, 0) to arr.optInt(1, 0)
    }

    fun putListScroll(key: String, index: Int, offset: Int) {
        if (key.isEmpty()) return
        val obj = listScrollMap()
        obj.put(key, JSONArray().put(index).put(offset))
        prefs.edit().putString("list_scroll", obj.toString()).apply()
    }

    // -- equalizer ------------------------------------------------------------
    var eqEnabled: Boolean
        get() = prefs.getBoolean("eq_enabled", true)
        set(value) = prefs.edit().putBoolean("eq_enabled", value).apply()

    var eqNormalize: Boolean
        get() = prefs.getBoolean("eq_normalize", true)
        set(value) = prefs.edit().putBoolean("eq_normalize", value).apply()

    var eqPreset: String
        get() = prefs.getString("eq_preset", "flat") ?: "flat"
        set(value) = prefs.edit().putString("eq_preset", value).apply()

    /** Band gains in dB; always 10 entries (missing values fall back to 0). */
    var eqGains: List<Float>
        get() {
            val raw = prefs.getString("eq_gains", null) ?: return List(10) { 0f }
            val values = raw.split(',').mapNotNull { it.trim().toFloatOrNull() }
            if (values.size != 10) return List(10) { 0f }
            return values
        }
        set(value) = prefs.edit()
            .putString("eq_gains", value.take(10).joinToString(",") { it.toString() })
            .apply()

    var eqBass: Float
        get() = prefs.getString("eq_bass", null)?.toFloatOrNull() ?: 0f
        set(value) = prefs.edit().putString("eq_bass", value.toString()).apply()

    var eqPreamp: Float
        get() = prefs.getString("eq_preamp", null)?.toFloatOrNull() ?: 0f
        set(value) = prefs.edit().putString("eq_preamp", value.toString()).apply()

    /** User-saved curves: name -> band gains. */
    fun eqCustomPresets(): Map<String, List<Float>> {
        val raw = prefs.getString("eq_custom", "{}") ?: "{}"
        val out = LinkedHashMap<String, List<Float>>()
        try {
            val obj = JSONObject(raw)
            for (key in obj.keys()) {
                val arr = obj.optJSONArray(key) ?: continue
                val gains = (0 until arr.length()).map { arr.getDouble(it).toFloat() }
                if (gains.size == 10) out[key] = gains
            }
        } catch (_: Throwable) {
            // corrupt store - ignore
        }
        return out
    }

    fun putEqCustomPreset(name: String, gains: List<Float>) {
        val obj = JSONObject()
        for ((key, value) in eqCustomPresets()) obj.put(key, JSONArray(value))
        obj.put(name, JSONArray(gains))
        prefs.edit().putString("eq_custom", obj.toString()).apply()
    }

    // -- per-track loudness cache (dBFS) --------------------------------------
    fun loudness(path: String): Float? {
        val raw = prefs.getString("loudness", "{}") ?: "{}"
        return try {
            val obj = JSONObject(raw)
            if (obj.has(path)) obj.getDouble(path).toFloat() else null
        } catch (_: Throwable) {
            null
        }
    }

    fun putLoudness(path: String, db: Float) {
        val raw = prefs.getString("loudness", "{}") ?: "{}"
        val obj = try {
            JSONObject(raw)
        } catch (_: Throwable) {
            JSONObject()
        }
        obj.put(path, db.toDouble())
        if (obj.length() > 400) {
            // keep the cache bounded: drop the oldest entries
            val keys = obj.keys().asSequence().toList()
            val fresh = JSONObject()
            for (k in keys.takeLast(300)) fresh.put(k, obj.get(k))
            prefs.edit().putString("loudness", fresh.toString()).apply()
            return
        }
        prefs.edit().putString("loudness", obj.toString()).apply()
    }

    // -- live effect ----------------------------------------------------------
    var liveEnabled: Boolean
        get() = prefs.getBoolean("live_enabled", false)
        set(value) = prefs.edit().putBoolean("live_enabled", value).apply()

    var liveVenue: String
        get() = prefs.getString("live_venue", "arena") ?: "arena"
        set(value) = prefs.edit().putString("live_venue", value).apply()

    var liveIntensity: Float
        get() = prefs.getString("live_intensity", null)?.toFloatOrNull() ?: 0.6f
        set(value) = prefs.edit().putString("live_intensity", value.toString()).apply()

    var liveCrowd: Float
        get() = prefs.getString("live_crowd", null)?.toFloatOrNull() ?: 0.5f
        set(value) = prefs.edit().putString("live_crowd", value.toString()).apply()

    var liveWidth: Float
        get() = prefs.getString("live_width", null)?.toFloatOrNull() ?: 0.6f
        set(value) = prefs.edit().putString("live_width", value.toString()).apply()

    var liveAutoShow: Boolean
        get() = prefs.getBoolean("live_auto_show", true)
        set(value) = prefs.edit().putBoolean("live_auto_show", value).apply()

    private fun jsonObject(rawKey: String): JSONObject {
        val raw = prefs.getString(rawKey, "{}") ?: "{}"
        return try {
            JSONObject(raw)
        } catch (_: Throwable) {
            JSONObject()
        }
    }

    /** Manual reactions the user placed on a track: path -> JSON array. */
    fun liveManual(path: String): JSONArray? {
        if (path.isEmpty()) return null
        return jsonObject("live_manual").optJSONArray(path)
    }

    fun putLiveManual(path: String, events: JSONArray) {
        if (path.isEmpty()) return
        val obj = jsonObject("live_manual")
        if (events.length() == 0) obj.remove(path) else obj.put(path, events)
        prefs.edit().putString("live_manual", obj.toString()).apply()
    }

    /** Cached automatic analysis for a track: path -> { mtime, v, events }. */
    fun liveAnalysis(path: String): JSONObject? {
        if (path.isEmpty()) return null
        return jsonObject("live_analysis").optJSONObject(path)
    }

    fun putLiveAnalysis(path: String, record: JSONObject) {
        if (path.isEmpty()) return
        val map = jsonObject("live_analysis")
        map.put(path, record)
        if (map.length() > 400) {
            val keys = map.keys().asSequence().toList()
            val fresh = JSONObject()
            for (k in keys.takeLast(300)) fresh.put(k, map.get(k))
            prefs.edit().putString("live_analysis", fresh.toString()).apply()
            return
        }
        prefs.edit().putString("live_analysis", map.toString()).apply()
    }

    // -- playlists ------------------------------------------------------------

    fun playlists(): MutableList<Playlist> {
        val raw = prefs.getString("playlists", "[]") ?: "[]"
        val out = mutableListOf<Playlist>()
        try {
            val arr = JSONArray(raw)
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                val name = o.optString("name")
                val tracks = mutableListOf<String>()
                val ta = o.optJSONArray("tracks") ?: JSONArray()
                for (j in 0 until ta.length()) tracks.add(ta.getString(j))
                if (name.isNotBlank()) out.add(Playlist(name, tracks))
            }
        } catch (_: Throwable) {
            // corrupt store - start clean
        }
        return out
    }

    private fun save(list: List<Playlist>) {
        val arr = JSONArray()
        for (p in list) {
            val o = JSONObject()
            o.put("name", p.name)
            o.put("tracks", JSONArray(p.tracks))
            arr.put(o)
        }
        prefs.edit().putString("playlists", arr.toString()).apply()
    }

    fun playlist(name: String): Playlist? = playlists().firstOrNull { it.name == name }

    fun createPlaylist(name: String, tracks: List<String> = emptyList()): Playlist {
        val list = playlists()
        list.firstOrNull { it.name == name }?.let { return it }
        val pl = Playlist(name, tracks.toMutableList())
        list.add(pl)
        save(list)
        return pl
    }

    fun deletePlaylist(name: String) {
        val list = playlists()
        list.removeAll { it.name == name }
        save(list)
    }

    fun renamePlaylist(old: String, new: String) {
        val list = playlists()
        if (list.any { it.name == new }) return
        list.firstOrNull { it.name == old }?.let { it0 ->
            val idx = list.indexOf(it0)
            list[idx] = it0.copy(name = new)
            save(list)
        }
    }

    fun addToPlaylist(name: String, paths: List<String>) {
        val list = playlists()
        val pl = list.firstOrNull { it.name == name } ?: Playlist(name)
        if (list.none { it.name == name }) list.add(pl)
        for (p in paths) if (p !in pl.tracks) pl.tracks.add(p)
        save(list)
    }

    fun removeFromPlaylist(name: String, path: String) {
        val list = playlists()
        list.firstOrNull { it.name == name }?.let { it.tracks.remove(path) }
        save(list)
    }
}
