package com.portable.musicplayer.data

import android.net.Uri

/**
 * A single playable audio item, resolved from [android.provider.MediaStore].
 *
 * Mirrors the desktop `library.Track` model: display fallbacks, an extension
 * label, a hi-res heuristic and a compact quality string used by the rows and
 * the now-playing screen.
 */
data class Track(
    val id: Long,
    val uri: Uri,
    val path: String,
    val folder: String,
    val title: String,
    val artist: String,
    val album: String,
    val albumId: Long,
    val durationMs: Long,
    val mime: String,
    val trackNo: Int = 0,
    val sampleRate: Int = 0,
    val bits: Int = 0,
    val channels: Int = 0,
    val bitrate: Int = 0,
) {
    val displayTitle: String get() = title.ifBlank { path.substringAfterLast('/') }
    val displayArtist: String get() = artist.ifBlank { "Unknown Artist" }
    val displayAlbum: String get() = album.ifBlank { "Unknown Album" }

    val ext: String get() = path.substringAfterLast('.', "").lowercase()
    val extLabel: String get() = EXT_LABEL[ext] ?: ext.uppercase()

    val isHires: Boolean
        get() = ext == "dsf" || ext == "dff" || bits > 16 || sampleRate > 48000

    val specText: String
        get() {
            val parts = ArrayList<String>(3)
            parts.add(extLabel)
            if (bits > 0 && ext in BIT_EXT) parts.add("${bits}bit")
            if (sampleRate > 0) {
                parts.add(if (sampleRate % 1000 == 0) "${sampleRate / 1000}kHz"
                else String.format("%.1fkHz", sampleRate / 1000.0))
            } else if (bitrate > 0) {
                parts.add("${bitrate / 1000}kbps")
            }
            return parts.joinToString(" / ")
        }

    companion object {
        val BIT_EXT = setOf("flac", "wav", "wave", "aiff", "aif", "alac", "m4a", "dsf", "dff")
        val EXT_LABEL = mapOf(
            "flac" to "FLAC", "wav" to "WAV", "wave" to "WAV", "m4a" to "M4A",
            "mp3" to "MP3", "dsf" to "DSF", "dff" to "DFF", "ogg" to "OGG",
            "opus" to "OPUS", "aac" to "AAC", "wma" to "WMA", "aiff" to "AIFF",
            "aif" to "AIFF", "ape" to "APE", "wv" to "WV",
        )
    }
}
