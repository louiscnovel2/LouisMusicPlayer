package com.portable.musicplayer.player

import android.media.AudioFormat
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import kotlin.math.log10
import kotlin.math.sqrt

/**
 * Measures the average level (dBFS) of an audio file so playback can be
 * normalised between tracks. Only the first [MAX_SECONDS] seconds are decoded,
 * which is plenty for a stable average and keeps the cost low.
 */
object LoudnessAnalyzer {

    private const val MAX_SECONDS = 60.0
    private const val DEQUEUE_TIMEOUT_US = 10_000L
    private const val MAX_STALLS = 200

    /** Returns the RMS level of [path] in dBFS, or `null` if it cannot be read. */
    fun measure(path: String): Float? {
        val extractor = MediaExtractor()
        var codec: MediaCodec? = null
        try {
            extractor.setDataSource(path)

            var trackIndex = -1
            var format: MediaFormat? = null
            for (i in 0 until extractor.trackCount) {
                val f = runCatching { extractor.getTrackFormat(i) }.getOrNull() ?: continue
                val mime = f.getString(MediaFormat.KEY_MIME) ?: continue
                if (mime.startsWith("audio/")) {
                    trackIndex = i
                    format = f
                    break
                }
            }
            val trackFormat = format ?: return null
            if (trackIndex < 0) return null
            extractor.selectTrack(trackIndex)

            val mime = trackFormat.getString(MediaFormat.KEY_MIME) ?: return null
            val decoder = MediaCodec.createDecoderByType(mime)
            codec = decoder
            decoder.configure(trackFormat, null, null, 0)
            decoder.start()

            val sampleRate = intOr(trackFormat, MediaFormat.KEY_SAMPLE_RATE, 44100)
            val maxSamples = (sampleRate * MAX_SECONDS).toLong()
            val info = MediaCodec.BufferInfo()

            var samples = 0L
            var sum = 0.0
            var inputDone = false
            var outputDone = false
            var stalls = 0

            while (!outputDone && samples < maxSamples && stalls < MAX_STALLS) {
                var progressed = false

                if (!inputDone) {
                    val inIndex = decoder.dequeueInputBuffer(DEQUEUE_TIMEOUT_US)
                    if (inIndex >= 0) {
                        val buffer = decoder.getInputBuffer(inIndex)
                        val size = if (buffer == null) -1 else extractor.readSampleData(buffer, 0)
                        if (size < 0) {
                            decoder.queueInputBuffer(
                                inIndex, 0, 0, 0L, MediaCodec.BUFFER_FLAG_END_OF_STREAM
                            )
                            inputDone = true
                        } else {
                            decoder.queueInputBuffer(inIndex, 0, size, extractor.sampleTime, 0)
                            extractor.advance()
                        }
                        progressed = true
                    }
                }

                val outIndex = decoder.dequeueOutputBuffer(info, DEQUEUE_TIMEOUT_US)
                if (outIndex >= 0) {
                    if (info.size > 0) {
                        val buffer = decoder.getOutputBuffer(outIndex)
                        if (buffer != null) {
                            val pcmEncoding = intOr(
                                decoder.outputFormat,
                                MediaFormat.KEY_PCM_ENCODING,
                                AudioFormat.ENCODING_PCM_16BIT,
                            )
                            buffer.position(info.offset)
                            buffer.limit(info.offset + info.size)
                            val (count, total) = accumulate(buffer, pcmEncoding)
                            samples += count
                            sum += total
                        }
                    }
                    decoder.releaseOutputBuffer(outIndex, false)
                    progressed = true
                    if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) outputDone = true
                }

                stalls = if (progressed) 0 else stalls + 1
            }

            if (samples <= 0L) return null
            val rms = sqrt(sum / samples)
            val db = if (rms <= 1e-7) -120f else (20.0 * log10(rms)).toFloat()
            // An average level near full scale means we decoded into the wrong
            // PCM layout (e.g. 24-bit output) - better to skip than to apply a
            // bogus gain.
            if (db > -1f) return null
            return db
        } catch (_: Throwable) {
            return null
        } finally {
            runCatching { codec?.stop() }
            runCatching { codec?.release() }
            runCatching { extractor.release() }
        }
    }

    /** Sums squares and counts samples for one decoded output buffer. */
    private fun accumulate(buffer: java.nio.ByteBuffer, pcmEncoding: Int): Pair<Long, Double> {
        var count = 0L
        var total = 0.0
        if (pcmEncoding == AudioFormat.ENCODING_PCM_FLOAT) {
            val floats = buffer.order(java.nio.ByteOrder.nativeOrder()).asFloatBuffer()
            while (floats.hasRemaining()) {
                val v = floats.get().toDouble()
                total += v * v
                count++
            }
        } else {
            val shorts = buffer.order(java.nio.ByteOrder.nativeOrder()).asShortBuffer()
            while (shorts.hasRemaining()) {
                val v = shorts.get() / 32768.0
                total += v * v
                count++
            }
        }
        return count to total
    }

    private fun intOr(format: MediaFormat, key: String, fallback: Int): Int =
        runCatching { format.getInteger(key) }.getOrDefault(fallback)
}
