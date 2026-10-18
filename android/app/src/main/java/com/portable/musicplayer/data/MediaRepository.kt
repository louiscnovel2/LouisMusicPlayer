package com.portable.musicplayer.data

import android.content.ContentUris
import android.content.Context
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import java.io.File
import java.util.Locale

/** Scans the device audio library through [MediaStore]. */
class MediaRepository(private val context: Context) {

    /** All audio tracks known to the system, sorted by folder then title. */
    fun loadTracks(): List<Track> {
        val collection = MediaStore.Audio.Media.EXTERNAL_CONTENT_URI
        val projection = buildList {
            add(MediaStore.Audio.Media._ID)
            add(MediaStore.Audio.Media.TITLE)
            add(MediaStore.Audio.Media.ARTIST)
            add(MediaStore.Audio.Media.ALBUM)
            add(MediaStore.Audio.Media.ALBUM_ID)
            add(MediaStore.Audio.Media.DURATION)
            add(MediaStore.Audio.Media.MIME_TYPE)
            add(MediaStore.Audio.Media.TRACK)
            add(MediaStore.Audio.Media.DATA)
            add(MediaStore.Audio.Media.DISPLAY_NAME)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                add(MediaStore.Audio.Media.RELATIVE_PATH)
            }
        }.toTypedArray()

        val selection = "${MediaStore.Audio.Media.IS_MUSIC} != 0"
        val order = "${MediaStore.Audio.Media.TITLE} COLLATE NOCASE ASC"

        val tracks = ArrayList<Track>()
        try {
            context.contentResolver.query(collection, projection, selection, null, order)?.use { c ->
                val idCol = c.getColumnIndexOrThrow(MediaStore.Audio.Media._ID)
                val titleCol = c.getColumnIndexOrThrow(MediaStore.Audio.Media.TITLE)
                val artistCol = c.getColumnIndexOrThrow(MediaStore.Audio.Media.ARTIST)
                val albumCol = c.getColumnIndexOrThrow(MediaStore.Audio.Media.ALBUM)
                val albumIdCol = c.getColumnIndexOrThrow(MediaStore.Audio.Media.ALBUM_ID)
                val durCol = c.getColumnIndexOrThrow(MediaStore.Audio.Media.DURATION)
                val mimeCol = c.getColumnIndexOrThrow(MediaStore.Audio.Media.MIME_TYPE)
                val trackCol = c.getColumnIndex(MediaStore.Audio.Media.TRACK)
                val dataCol = c.getColumnIndex(MediaStore.Audio.Media.DATA)
                val relCol = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q)
                    c.getColumnIndex(MediaStore.Audio.Media.RELATIVE_PATH) else -1

                while (c.moveToNext()) {
                    val id = c.getLong(idCol)
                    val uri: Uri = ContentUris.withAppendedId(collection, id)
                    val data = if (dataCol >= 0) c.getString(dataCol) else null
                    val rel = if (relCol >= 0) c.getString(relCol) else null
                    val folder = when {
                        !data.isNullOrBlank() -> File(data).parent ?: ""
                        !rel.isNullOrBlank() -> rel.trimEnd('/')
                        else -> ""
                    }
                    val name = if (!data.isNullOrBlank()) File(data).name
                    else c.getString(c.getColumnIndexOrThrow(MediaStore.Audio.Media.DISPLAY_NAME))
                    tracks.add(
                        Track(
                            id = id,
                            uri = uri,
                            path = data ?: (folder + "/" + name),
                            folder = folder,
                            title = c.getString(titleCol) ?: "",
                            artist = c.getString(artistCol) ?: "",
                            album = c.getString(albumCol) ?: "",
                            albumId = c.getLong(albumIdCol),
                            durationMs = c.getLong(durCol),
                            mime = c.getString(mimeCol) ?: "",
                            trackNo = trackNumberOf(c.getInt(trackCol)),
                        )
                    )
                }
            }
        } catch (_: Throwable) {
            // Permission not granted yet or provider unavailable.
        }

        // Order tracks by their embedded track number (ascending) within each
        // folder; files without a track number fall to the end, then by title.
        return tracks.sortedWith(
            compareBy(
                { it.folder.lowercase(Locale.ROOT) },
                { if (it.trackNo > 0) it.trackNo else Int.MAX_VALUE },
                { it.displayTitle.lowercase(Locale.ROOT) },
            )
        )
    }

    /**
     * MediaStore stores the track number as `disc * 1000 + track` when a disc
     * number is present, so unwrap the low three digits.
     */
    private fun trackNumberOf(raw: Int): Int = if (raw >= 1000) raw % 1000 else raw

    /** Fill in sample-rate / bit-depth details for a single track, in place. */
    fun enrich(track: Track): Track {
        val spec = AudioSpec.read(track.path)
        return track.copy(
            sampleRate = if (spec.sampleRate > 0) spec.sampleRate else track.sampleRate,
            bits = if (spec.bits > 0) spec.bits else track.bits,
            channels = if (spec.channels > 0) spec.channels else track.channels,
        )
    }

    /** Embedded cover art bytes for a track (may be null). */
    fun loadArtwork(path: String): ByteArray? {
        val mmr = MediaMetadataRetriever()
        return try {
            mmr.setDataSource(path)
            mmr.embeddedPicture
        } catch (_: Throwable) {
            null
        } finally {
            try {
                mmr.release()
            } catch (_: Throwable) {
            }
        }
    }
}
