package com.portable.musicplayer.data

import android.media.MediaExtractor
import android.media.MediaFormat
import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Reads exact sample-rate / bit-depth / channel info from lossless container
 * headers (FLAC, WAV, AIFF) and falls back to [MediaExtractor] for everything
 * else. This backs the "24bit/96kHz" spec text and the HR badge.
 */
object AudioSpec {

    data class Spec(val sampleRate: Int = 0, val bits: Int = 0, val channels: Int = 0)

    fun read(path: String): Spec {
        val ext = path.substringAfterLast('.', "").lowercase()
        val exact = try {
            when (ext) {
                "flac" -> readFlac(path)
                "wav", "wave" -> readWav(path)
                "aiff", "aif", "aifc" -> readAiff(path)
                else -> null
            }
        } catch (_: Throwable) {
            null
        }
        if (exact != null && exact.sampleRate > 0) return exact

        val generic = readGeneric(path)
        return if (exact != null) exact.copy(
            sampleRate = if (exact.sampleRate > 0) exact.sampleRate else generic.sampleRate,
            channels = if (exact.channels > 0) exact.channels else generic.channels,
        ) else generic
    }

    // -- FLAC -----------------------------------------------------------------
    private fun readFlac(path: String): Spec? {
        val f = RandomAccessFile(path, "r")
        f.use {
            val magic = ByteArray(4)
            if (it.read(magic) != 4 || String(magic) != "fLaC") return null
            while (true) {
                val header = ByteArray(4)
                if (it.read(header) != 4) return null
                val isLast = (header[0].toInt() and 0x80) != 0
                val type = header[0].toInt() and 0x7f
                val len = ((header[1].toInt() and 0xff) shl 16) or
                    ((header[2].toInt() and 0xff) shl 8) or (header[3].toInt() and 0xff)
                if (type == 0) {
                    val body = ByteArray(len)
                    if (it.read(body) != len) return null
                    if (len < 18) return null
                    val b = body
                    val sampleRate = ((b[10].toInt() and 0xff) shl 12) or
                        ((b[11].toInt() and 0xff) shl 4) or
                        ((b[12].toInt() and 0xff) shr 4)
                    val channels = ((b[12].toInt() shr 1) and 0x07) + 1
                    val bits = (((b[12].toInt() and 0x01) shl 4) or
                        ((b[13].toInt() and 0xff) shr 4)) + 1
                    return Spec(sampleRate, bits, channels)
                }
                if (isLast) return null
                it.seek(it.filePointer + len)
            }
        }
    }

    // -- WAV ------------------------------------------------------------------
    private fun readWav(path: String): Spec? {
        val f = RandomAccessFile(path, "r")
        f.use {
            val riff = ByteArray(12)
            if (it.read(riff) != 12) return null
            if (String(riff, 0, 4) != "RIFF") return null
            var pos = 12L
            val header = ByteArray(8)
            while (true) {
                it.seek(pos)
                if (it.read(header) != 8) break
                val id = String(header, 0, 4)
                val size = leUInt(header, 4)
                if (id == "fmt ") {
                    val body = ByteArray(minOf(size, 40))
                    it.read(body)
                    if (body.size < 16) return null
                    val channels = leUShort(body, 2)
                    val sampleRate = leUInt(body, 4)
                    val bits = leUShort(body, 14)
                    return Spec(sampleRate, bits, channels)
                }
                pos += 8 + size + (size and 1)
            }
            return null
        }
    }

    // -- AIFF -----------------------------------------------------------------
    private fun readAiff(path: String): Spec? {
        val f = RandomAccessFile(path, "r")
        f.use {
            val form = ByteArray(12)
            if (it.read(form) != 12) return null
            if (String(form, 0, 4) != "FORM") return null
            var pos = 12L
            val header = ByteArray(8)
            while (true) {
                it.seek(pos)
                if (it.read(header) != 8) break
                val id = String(header, 0, 4)
                val size = beUInt(header, 4)
                if (id == "COMM") {
                    val body = ByteArray(minOf(size, 30))
                    it.read(body)
                    if (body.size < 18) return null
                    val channels = beUShort(body, 0)
                    val bits = beUShort(body, 6)
                    return Spec(extendedToInt(body, 8), bits, channels)
                }
                pos += 8 + size + (size and 1)
            }
            return null
        }
    }

    // -- generic (MediaExtractor) --------------------------------------------
    private fun readGeneric(path: String): Spec {
        val ex = MediaExtractor()
        return try {
            ex.setDataSource(path)
            for (i in 0 until ex.trackCount) {
                val fmt: MediaFormat = ex.getTrackFormat(i)
                val mime = fmt.getString(MediaFormat.KEY_MIME) ?: continue
                if (!mime.startsWith("audio/")) continue
                val sr = if (fmt.containsKey(MediaFormat.KEY_SAMPLE_RATE))
                    fmt.getInteger(MediaFormat.KEY_SAMPLE_RATE) else 0
                val ch = if (fmt.containsKey(MediaFormat.KEY_CHANNEL_COUNT))
                    fmt.getInteger(MediaFormat.KEY_CHANNEL_COUNT) else 0
                return Spec(sr, 0, ch)
            }
            Spec()
        } catch (_: Throwable) {
            Spec()
        } finally {
            try {
                ex.release()
            } catch (_: Throwable) {
            }
        }
    }

    // -- byte helpers ---------------------------------------------------------
    private fun leUShort(b: ByteArray, o: Int) =
        (b[o].toInt() and 0xff) or ((b[o + 1].toInt() and 0xff) shl 8)

    private fun leUInt(b: ByteArray, o: Int) =
        (b[o].toInt() and 0xff) or ((b[o + 1].toInt() and 0xff) shl 8) or
            ((b[o + 2].toInt() and 0xff) shl 16) or ((b[o + 3].toInt() and 0xff) shl 24)

    private fun beUShort(b: ByteArray, o: Int) =
        ((b[o].toInt() and 0xff) shl 8) or (b[o + 1].toInt() and 0xff)

    private fun beUInt(b: ByteArray, o: Int) =
        ((b[o].toInt() and 0xff) shl 24) or ((b[o + 1].toInt() and 0xff) shl 16) or
            ((b[o + 2].toInt() and 0xff) shl 8) or (b[o + 3].toInt() and 0xff)

    /** Convert an 80-bit IEEE 754 extended float (AIFF sample rate). */
    private fun extendedToInt(b: ByteArray, o: Int): Int {
        val buf = ByteBuffer.wrap(b, o, 10).order(ByteOrder.BIG_ENDIAN)
        val signExp = buf.short.toInt() and 0xffff
        val exp = signExp and 0x7fff
        val hi = buf.int.toLong() and 0xffffffffL
        val lo = buf.int.toLong() and 0xffffffffL
        val mantissa = (hi shl 32) or lo
        if (exp == 0 && mantissa == 0L) return 0
        val shift = exp - 16383 - 63
        var value = mantissa.toDouble()
        if (shift >= 0) value *= Math.pow(2.0, shift.toDouble())
        else value /= Math.pow(2.0, -shift.toDouble())
        return Math.round(value).toInt()
    }

    /** True when the file looks like a decodable audio file we can open. */
    fun exists(path: String): Boolean = try {
        File(path).exists()
    } catch (_: Throwable) {
        false
    }
}
