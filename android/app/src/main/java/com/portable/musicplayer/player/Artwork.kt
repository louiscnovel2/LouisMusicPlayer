package com.portable.musicplayer.player

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.MediaMetadataRetriever
import android.net.Uri
import androidx.media3.common.util.BitmapLoader
import androidx.media3.common.util.UnstableApi
import com.google.common.util.concurrent.ListenableFuture
import com.google.common.util.concurrent.SettableFuture
import java.util.concurrent.Executors

/**
 * Custom URI scheme used to hand a local file path to the notification's bitmap
 * loader. Media3 only understands content / file URIs, so the engine encodes the
 * track path in an [ArtworkUri] and [LocalArtworkBitmapLoader] decodes it back.
 */
object ArtworkUri {

    private const val SCHEME = "mpart"
    private const val KEY_PATH = "path"

    fun forPath(path: String): Uri = Uri.Builder()
        .scheme(SCHEME)
        .authority("artwork")
        .appendQueryParameter(KEY_PATH, path)
        .build()

    fun pathOf(uri: Uri): String? =
        if (uri.scheme == SCHEME) uri.getQueryParameter(KEY_PATH) else null
}

/**
 * Loads embedded cover art from the file system so the media notification shows
 * the current jacket. Media3 calls this asynchronously and refreshes the
 * notification once the bitmap is ready.
 */
@androidx.annotation.OptIn(UnstableApi::class)
class LocalArtworkBitmapLoader(context: Context) : BitmapLoader {

    private val executor = Executors.newSingleThreadExecutor()

    override fun loadBitmap(uri: Uri): ListenableFuture<Bitmap> {
        val future = SettableFuture.create<Bitmap>()
        val path = ArtworkUri.pathOf(uri)
        if (path.isNullOrEmpty()) {
            future.setException(IllegalArgumentException("Unsupported artwork uri: $uri"))
            return future
        }
        executor.execute {
            try {
                val bitmap = decodeEmbeddedPicture(path)
                if (bitmap != null) future.set(bitmap)
                else future.setException(IllegalStateException("No embedded artwork"))
            } catch (t: Throwable) {
                future.setException(t)
            }
        }
        return future
    }

    override fun decodeBitmap(data: ByteArray): ListenableFuture<Bitmap> {
        val future = SettableFuture.create<Bitmap>()
        executor.execute {
            try {
                val bitmap = BitmapFactory.decodeByteArray(data, 0, data.size)
                if (bitmap != null) future.set(bitmap)
                else future.setException(IllegalStateException("Cannot decode artwork"))
            } catch (t: Throwable) {
                future.setException(t)
            }
        }
        return future
    }

    private fun decodeEmbeddedPicture(path: String): Bitmap? {
        val mmr = MediaMetadataRetriever()
        return try {
            mmr.setDataSource(path)
            val bytes = mmr.embeddedPicture ?: return null
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
        } catch (_: Throwable) {
            null
        } finally {
            runCatching { mmr.release() }
        }
    }
}
