package com.portable.musicplayer.player

import android.media.AudioFormat
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sqrt

/**
 * Decodes a track and turns its loudness contour into crowd reactions, mirroring
 * the desktop `live_analysis` module. The PCM is streamed and only the small
 * per-hop RMS / onset envelopes are kept in memory.
 */
object LiveAnalyzer {

    const val VERSION = 1

    private const val HOP = 1024
    private const val MAX_SECONDS = 420.0
    private const val MIN_GAP = 7.0
    private const val MAX_EVENTS = 24
    private const val DEQUEUE_TIMEOUT_US = 10_000L
    private const val MAX_STALLS = 200

    /** Returns the automatic reaction list, or `null` if analysis failed. */
    fun analyze(path: String): List<LiveEvent>? {
        val env = decodeEnvelopes(path) ?: return null
        if (env.onset.size < 4) return null
        return try {
            analyzeEnvelopes(env)
        } catch (_: Throwable) {
            null
        }
    }

    private class EnvData(
        val energy: FloatArray,
        val onset: FloatArray,
        val rate: Int,
        val duration: Double,
    )

    // -- decoding -------------------------------------------------------------

    private class EnvCollector(private val hop: Int) {
        private val frame = FloatArray(hop)
        private var fill = 0
        private val energy = ArrayList<Float>()
        private val onset = ArrayList<Float>()
        private var prevLogHf = 0.0
        private var started = false

        fun push(x: Float) {
            frame[fill++] = x
            if (fill == hop) flush()
        }

        private fun flush() {
            var sum = 0.0
            for (v in frame) sum += v.toDouble() * v
            val rms = sqrt(sum / hop + 1e-12)
            energy.add(ln(rms + 1e-6).toFloat())

            var hfSum = 0.0
            var prev = frame[0]
            for (i in 1 until hop) {
                val d = (frame[i] - prev).toDouble()
                hfSum += d * d
                prev = frame[i]
            }
            val hf = sqrt(hfSum / hop + 1e-12)
            val lh = ln(hf + 1e-6)
            onset.add(max(0.0, if (started) lh - prevLogHf else lh).toFloat())
            prevLogHf = lh
            started = true
            fill = 0
        }

        fun energy(): FloatArray = energy.toFloatArray()
        fun onset(): FloatArray = onset.toFloatArray()
    }

    private fun decodeEnvelopes(path: String): EnvData? {
        val extractor = MediaExtractor()
        var codec: MediaCodec? = null
        try {
            extractor.setDataSource(path)
            var trackIndex = -1
            var trackFormat: MediaFormat? = null
            for (i in 0 until extractor.trackCount) {
                val f = runCatching { extractor.getTrackFormat(i) }.getOrNull() ?: continue
                val mime = f.getString(MediaFormat.KEY_MIME) ?: continue
                if (mime.startsWith("audio/")) {
                    trackIndex = i
                    trackFormat = f
                    break
                }
            }
            val format = trackFormat ?: return null
            if (trackIndex < 0) return null
            extractor.selectTrack(trackIndex)

            val mime = format.getString(MediaFormat.KEY_MIME) ?: return null
            val decoder = MediaCodec.createDecoderByType(mime)
            codec = decoder
            decoder.configure(format, null, null, 0)
            decoder.start()

            val sampleRate = intOr(format, MediaFormat.KEY_SAMPLE_RATE, 44100)
            val limitSamples = (MAX_SECONDS * sampleRate).toLong()
            val collector = EnvCollector(HOP)
            val info = MediaCodec.BufferInfo()

            var totalSamples = 0L
            var inputDone = false
            var outputDone = false
            var stalls = 0

            while (!outputDone && totalSamples < limitSamples && stalls < MAX_STALLS) {
                var progressed = false

                if (!inputDone) {
                    val inIndex = decoder.dequeueInputBuffer(DEQUEUE_TIMEOUT_US)
                    if (inIndex >= 0) {
                        val buffer = decoder.getInputBuffer(inIndex)
                        val sz = if (buffer == null) -1 else extractor.readSampleData(buffer, 0)
                        if (sz < 0) {
                            decoder.queueInputBuffer(
                                inIndex, 0, 0, 0L, MediaCodec.BUFFER_FLAG_END_OF_STREAM
                            )
                            inputDone = true
                        } else {
                            decoder.queueInputBuffer(inIndex, 0, sz, extractor.sampleTime, 0)
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
                            buffer.position(info.offset)
                            buffer.limit(info.offset + info.size)
                            val enc = intOr(
                                decoder.outputFormat,
                                MediaFormat.KEY_PCM_ENCODING,
                                AudioFormat.ENCODING_PCM_16BIT,
                            )
                            val ch = intOr(decoder.outputFormat, MediaFormat.KEY_CHANNEL_COUNT, 1)
                                .coerceAtLeast(1)
                            totalSamples += feed(buffer, enc, ch, collector)
                        }
                    }
                    decoder.releaseOutputBuffer(outIndex, false)
                    progressed = true
                    if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) outputDone = true
                }

                stalls = if (progressed) 0 else stalls + 1
            }

            val duration = totalSamples.toDouble() / sampleRate
            if (duration < 1.0) return null
            return EnvData(collector.energy(), collector.onset(), sampleRate, duration)
        } catch (_: Throwable) {
            return null
        } finally {
            runCatching { codec?.stop() }
            runCatching { codec?.release() }
            runCatching { extractor.release() }
        }
    }

    private fun feed(buffer: ByteBuffer, enc: Int, ch: Int, c: EnvCollector): Long {
        var frames = 0L
        if (enc == AudioFormat.ENCODING_PCM_FLOAT) {
            val f = buffer.order(ByteOrder.nativeOrder()).asFloatBuffer()
            while (f.remaining() >= ch) {
                var s = 0f
                for (j in 0 until ch) s += f.get()
                c.push(s / ch)
                frames++
            }
        } else {
            val sh = buffer.order(ByteOrder.nativeOrder()).asShortBuffer()
            while (sh.remaining() >= ch) {
                var s = 0f
                for (j in 0 until ch) s += sh.get()
                c.push(s / (ch * 32768f))
                frames++
            }
        }
        return frames
    }

    private fun intOr(format: MediaFormat, key: String, fallback: Int): Int =
        runCatching { format.getInteger(key) }.getOrDefault(fallback)

    // -- analysis -------------------------------------------------------------

    private class Section(val start: Double, val end: Double, val level: Double)

    private fun analyzeEnvelopes(env: EnvData): List<LiveEvent> {
        val rate = env.rate
        val duration = env.duration
        val (period, conf) = tempo(env.onset, rate)
        val phase = if (conf > 0.05) beatPhase(env.onset, period, rate) else 0.0
        val sections = sections(env.energy, rate)
        return buildEvents(sections, period, phase, duration)
    }

    private fun tempo(onset: FloatArray, rate: Int): Pair<Double, Double> {
        val hopRate = rate.toDouble() / HOP
        val n = onset.size
        if (n < 4) return 0.5 to 0.0
        var mean = 0.0
        for (v in onset) mean += v
        mean /= n
        val x = DoubleArray(n) { onset[it] - mean }
        var any = false
        for (v in x) if (abs(v) > 1e-9) { any = true; break }
        if (!any) return 0.5 to 0.0

        val lo = max(1, (hopRate * 60.0 / 190.0).toInt())
        val hi = min(n - 1, (hopRate * 60.0 / 55.0).toInt())
        if (hi <= lo + 1) return 0.5 to 0.0

        val maxLag = min(n - 1, 2 * hi + 2)
        val acf = DoubleArray(maxLag + 1)
        for (lag in 0..maxLag) {
            var s = 0.0
            val limit = n - lag
            var i = 0
            while (i < limit) {
                s += x[i] * x[i + lag]
                i++
            }
            acf[lag] = s
        }
        if (acf[0] > 0.0) for (k in acf.indices) acf[k] /= acf[0]

        var idx = lo
        var bestVal = -1e30
        for (k in lo until hi) {
            if (acf[k] > bestVal) {
                bestVal = acf[k]
                idx = k
            }
        }
        if (2 * idx < hi && acf[2 * idx] > bestVal * 0.82) {
            idx = 2 * idx
            bestVal = acf[idx]
        }
        return (idx / hopRate) to bestVal
    }

    private fun beatPhase(onset: FloatArray, period: Double, rate: Int): Double {
        val hopRate = rate.toDouble() / HOP
        val steps = max(2, (period * hopRate).roundToInt())
        if (steps <= 1) return 0.0
        var best = 0
        var bestScore = -1.0
        for (p in 0 until steps) {
            var s = 0.0
            var i = p
            while (i < onset.size) {
                s += onset[i]
                i += steps
            }
            if (s > bestScore) {
                bestScore = s
                best = p
            }
        }
        return best / hopRate
    }

    private fun snap(t: Double, period: Double, phase: Double): Double {
        if (period <= 0.05) return t
        val k = Math.round((t - phase) / period)
        return max(0.0, phase + k * period)
    }

    private fun smooth(x: FloatArray, win: Int): FloatArray {
        val w = max(1, win)
        val n = x.size
        if (w <= 1 || n <= w) return FloatArray(n) { x[it] }
        val out = FloatArray(n)
        val half = w / 2
        for (i in 0 until n) {
            val lo = max(0, i - half)
            val hi = min(n, i + half + 1)
            var s = 0f
            for (j in lo until hi) s += x[j]
            out[i] = s / (hi - lo)
        }
        return out
    }

    private fun percentile(sorted: FloatArray, p: Double): Double {
        if (sorted.isEmpty()) return 0.0
        val rank = (p / 100.0) * (sorted.size - 1)
        val lo = floor(rank).toInt()
        val hi = ceil(rank).toInt()
        if (lo == hi) return sorted[lo].toDouble()
        val f = rank - lo
        return sorted[lo] * (1 - f) + sorted[hi] * f
    }

    private fun sections(energy: FloatArray, rate: Int): List<Section> {
        val hopRate = rate.toDouble() / HOP
        val sm = smooth(energy, (hopRate * 2.0).toInt())
        if (sm.size < 8) return emptyList()
        val sorted = sm.copyOf()
        sorted.sort()
        val lo = percentile(sorted, 12.0)
        val hi = percentile(sorted, 92.0)
        val span = max(0.2, hi - lo)
        if (hi - lo < 0.5) return listOf(Section(0.0, sm.size / hopRate, 0.0))
        val threshold = lo + 0.45 * (hi - lo)
        val loud = BooleanArray(sm.size) { sm[it] > threshold }
        val out = ArrayList<Section>()
        var start = 0
        for (i in 1..sm.size) {
            if (i == sm.size || loud[i] != loud[start]) {
                if (i - start >= (hopRate * 1.5).toInt()) {
                    var sum = 0.0
                    for (j in start until i) sum += sm[j]
                    val mean = sum / (i - start)
                    val level = ((mean - lo) / span).coerceIn(0.0, 1.0)
                    out.add(Section(start / hopRate, i / hopRate, level))
                }
                start = i
            }
        }
        return out
    }

    private fun buildEvents(
        sections: List<Section>,
        period: Double,
        phase: Double,
        duration: Double,
    ): List<LiveEvent> {
        data class Cand(val time: Double, val kind: String, val gain: Float)

        val candidates = ArrayList<Cand>()
        val loudCount = sections.count { it.level > 0.5 }
        var quietAfter = false
        var prevLevel = 0.0
        var chorusSeen = 0

        for (s in sections) {
            if (s.level > 0.5 && (prevLevel <= 0.5 || loudCount == 1)) {
                val t = snap(s.start, period, phase)
                chorusSeen++
                candidates.add(Cand(t, "cheer", if (chorusSeen == 1) 0.75f else 0.6f))
                candidates.add(Cand(max(0.0, t - max(1.0, period * 3.0)), "build", 0.4f))
            } else if (s.level <= 0.25 && (s.end - s.start) > 6.0) {
                quietAfter = true
            }
            prevLevel = s.level
        }

        for (frac in doubleArrayOf(0.4, 0.68)) {
            val t = snap(duration * frac, period, phase)
            if (t > 8.0 && t < duration - 10.0) candidates.add(Cand(t, "cheer", 0.35f))
        }

        val endT = snap(max(0.0, duration - 3.0), period, phase)
        candidates.add(Cand(endT, "applause", 0.6f))
        candidates.add(Cand(max(0.0, duration - 0.15), "big_cheer", 0.95f))
        if (quietAfter && duration > 40) {
            candidates.add(Cand(snap(duration * 0.5, period, phase), "applause", 0.3f))
        }

        val builds = candidates.filter { it.kind == "build" }
        val mains = candidates.filter { it.kind != "build" }.sortedBy { it.time }

        val events = ArrayList<LiveEvent>()
        if (duration > 1.5) {
            events.add(LiveEvent(min(0.9, duration - 0.1), "applause", 0.45f, "auto"))
        }
        for (c in mains) {
            var t = c.time
            if (duration > 0) t = max(0.0, min(t, duration - 0.05))
            if (events.isNotEmpty()) {
                val last = events.last()
                if (t - last.time < MIN_GAP) {
                    if (c.gain > last.gain && t - last.time > 1.5) {
                        events[events.size - 1] = LiveEvent(t, c.kind, c.gain, "auto")
                    }
                    continue
                }
            }
            events.add(LiveEvent(t, c.kind, c.gain, "auto"))
        }

        for (c in builds) {
            if (duration <= 0 || c.time < 1.0) continue
            val t = max(0.0, min(c.time, duration - 0.05))
            if (events.any { abs(t - it.time) < 0.4 }) continue
            events.add(LiveEvent(t, c.kind, c.gain, "auto"))
        }

        events.sortBy { it.time }
        return thin(events)
    }

    private fun thin(events: List<LiveEvent>): List<LiveEvent> {
        if (events.size <= MAX_EVENTS) return events
        val head = events.take(2)
        val tail = events.takeLast(3)
        val middle = events.subList(2, events.size - 3)
        val step = max(1, middle.size / max(1, MAX_EVENTS - 5))
        val out = ArrayList<LiveEvent>(head)
        var i = 0
        while (i < middle.size) {
            out.add(middle[i])
            i += step
        }
        out.addAll(tail)
        return out
    }
}
