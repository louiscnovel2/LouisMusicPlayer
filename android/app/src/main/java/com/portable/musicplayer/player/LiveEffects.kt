package com.portable.musicplayer.player

import android.content.Context
import androidx.media3.common.C
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.audio.BaseAudioProcessor
import androidx.media3.common.util.UnstableApi
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.nio.ByteBuffer
import java.util.concurrent.Executors
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.random.Random

// -- configuration ------------------------------------------------------------

const val LIVE_HOP = 1024
const val LIVE_IR_VERSION = 4
const val LIVE_TEXTURE_VERSION = 5
const val LIVE_SAMPLE_VERSION = 1

/** Acoustic profile of one venue (mirrors the desktop `VENUES` table). */
data class Venue(
    val label: String,
    val preDelay: Float,
    val rt60: Float,
    val damping: Float,
    val wet: Float,
    val size: Float,
    val crowd: Float,
)

object LiveVenues {
    val VENUES: Map<String, Venue> = linkedMapOf(
        "arena" to Venue("アリーナ", 0.012f, 1.6f, 0.42f, 0.30f, 1.0f, 0.55f),
        "livehouse" to Venue("ライブハウス", 0.006f, 0.9f, 0.34f, 0.34f, 0.7f, 0.78f),
        "hall" to Venue("コンサートホール", 0.019f, 2.1f, 0.58f, 0.30f, 1.2f, 0.30f),
        "dome" to Venue("ドーム", 0.026f, 2.7f, 0.28f, 0.36f, 1.4f, 0.68f),
        "festival" to Venue("野外フェス", 0.030f, 1.8f, 0.24f, 0.26f, 1.6f, 0.88f),
        "stadium" to Venue("スタジアム", 0.042f, 3.0f, 0.20f, 0.34f, 1.8f, 1.0f),
    )
    val ORDER: List<String> = listOf("arena", "livehouse", "hall", "dome", "festival", "stadium")
    fun get(key: String): Venue = VENUES[key] ?: VENUES.getValue("arena")
    fun label(key: String): String = get(key).label
}

object LiveKinds {
    val KINDS: List<String> = listOf("applause", "cheer", "big_cheer", "build")
    private val LABELS = mapOf(
        "applause" to "拍手",
        "cheer" to "歓声",
        "big_cheer" to "大歓声",
        "build" to "盛り上げ",
    )
    private val GAIN = mapOf(
        "applause" to 0.55f,
        "cheer" to 0.72f,
        "big_cheer" to 0.95f,
        "build" to 0.45f,
    )
    private val PAN = mapOf(
        "applause" to 0.8f,
        "cheer" to 0.45f,
        "big_cheer" to 0.0f,
        "build" to 0.0f,
    )
    private val LENGTH = mapOf(
        "applause" to 8.0f,
        "cheer" to 6.5f,
        "big_cheer" to 8.5f,
        "build" to 1.9f,
    )
    fun label(kind: String): String = LABELS[kind] ?: kind
    fun gain(kind: String): Float = GAIN[kind] ?: 0.7f
    fun pan(kind: String): Float = PAN[kind] ?: 0.5f
    fun length(kind: String): Float = LENGTH[kind] ?: 4.0f
}

data class LiveSettings(
    val enabled: Boolean = false,
    val venue: String = "arena",
    val intensity: Float = 0.6f,
    val crowd: Float = 0.5f,
    val width: Float = 0.6f,
    val autoShow: Boolean = true,
)

data class LiveEvent(
    val time: Double,
    val kind: String = "cheer",
    val gain: Float = 0.8f,
    /** "auto" (from the song analysis) or "manual" (user placed). */
    val source: String = "auto",
)

// -- JSON serialisation (used by the settings store) --------------------------

fun liveEventToJson(e: LiveEvent): JSONObject = JSONObject()
    .put("time", e.time)
    .put("kind", e.kind)
    .put("gain", e.gain.toDouble())
    .put("source", e.source)

fun liveEventFromJson(o: JSONObject): LiveEvent? {
    val t = o.optDouble("time", Double.NaN)
    if (t.isNaN() || t.isInfinite()) return null
    var kind = o.optString("kind", "cheer")
    if (kind !in LiveKinds.KINDS) kind = "cheer"
    val gain = o.optDouble("gain", 0.8).toFloat().coerceIn(0f, 1f)
    val source = if (o.optString("source", "auto") == "manual") "manual" else "auto"
    return LiveEvent(max(0.0, t), kind, gain, source)
}

fun liveEventsToJson(list: List<LiveEvent>): JSONArray {
    val arr = JSONArray()
    for (e in list) arr.put(liveEventToJson(e))
    return arr
}

fun liveEventsFromJson(arr: JSONArray): List<LiveEvent> {
    val out = ArrayList<LiveEvent>(arr.length())
    for (i in 0 until arr.length()) {
        val o = arr.optJSONObject(i) ?: continue
        liveEventFromJson(o)?.let { out.add(it) }
    }
    return out
}

// -- FFT ----------------------------------------------------------------------

/** In-place iterative radix-2 complex FFT (size must be a power of two). */
internal class SimpleFft(private val n: Int) {
    private val levels: Int
    private val cosTable = FloatArray(n / 2)
    private val sinTable = FloatArray(n / 2)
    private val rev = IntArray(n)

    init {
        require(n > 0 && (n and (n - 1)) == 0) { "FFT size must be a power of two" }
        levels = Integer.numberOfTrailingZeros(n)
        for (i in 0 until n / 2) {
            val a = 2.0 * Math.PI * i / n
            cosTable[i] = cos(a).toFloat()
            sinTable[i] = sin(a).toFloat()
        }
        for (i in 0 until n) rev[i] = Integer.reverse(i) ushr (32 - levels)
    }

    fun forward(re: FloatArray, im: FloatArray) {
        for (i in 0 until n) {
            val j = rev[i]
            if (j > i) {
                var t = re[i]; re[i] = re[j]; re[j] = t
                t = im[i]; im[i] = im[j]; im[j] = t
            }
        }
        var size = 2
        while (size <= n) {
            val half = size / 2
            val step = n / size
            var i = 0
            while (i < n) {
                var j = i
                var k = 0
                while (j < i + half) {
                    val l = j + half
                    val wr = cosTable[k]
                    val wi = sinTable[k]
                    val xr = re[l]
                    val xi = im[l]
                    val tr = xr * wr + xi * wi
                    val ti = xi * wr - xr * wi
                    re[l] = re[j] - tr
                    im[l] = im[j] - ti
                    re[j] += tr
                    im[j] += ti
                    j++
                    k += step
                }
                i += size
            }
            size *= 2
        }
    }

    fun inverse(re: FloatArray, im: FloatArray) {
        for (i in 0 until n) im[i] = -im[i]
        forward(re, im)
        val inv = 1f / n
        for (i in 0 until n) {
            re[i] *= inv
            im[i] = -im[i] * inv
        }
    }
}

// -- partitioned convolution kernel -------------------------------------------

/** Pre-computed frequency-domain partitions of a stereo impulse response. */
class ConvolverKernel private constructor(
    val sampleRate: Int,
    val hop: Int,
    val nfft: Int,
    val nparts: Int,
    /** [channel][nparts * nfft] real part */
    val kr: Array<FloatArray>,
    /** [channel][nparts * nfft] imaginary part */
    val ki: Array<FloatArray>,
) {
    companion object {
        fun build(ir: Array<FloatArray>, hop: Int, sampleRate: Int): ConvolverKernel {
            val nfft = hop * 2
            val ch = ir.size
            val n = ir[0].size
            val nparts = max(1, ceil(n.toDouble() / hop).toInt())
            val fft = SimpleFft(nfft)
            val kr = Array(ch) { FloatArray(nparts * nfft) }
            val ki = Array(ch) { FloatArray(nparts * nfft) }
            val re = FloatArray(nfft)
            val im = FloatArray(nfft)
            for (c in 0 until ch) {
                for (p in 0 until nparts) {
                    java.util.Arrays.fill(re, 0f)
                    java.util.Arrays.fill(im, 0f)
                    val start = p * hop
                    val len = min(hop, n - start)
                    for (i in 0 until len) re[i] = ir[c][start + i]
                    fft.forward(re, im)
                    System.arraycopy(re, 0, kr[c], p * nfft, nfft)
                    System.arraycopy(im, 0, ki[c], p * nfft, nfft)
                }
            }
            return ConvolverKernel(sampleRate, hop, nfft, nparts, kr, ki)
        }
    }
}

/** Everything the realtime processor needs, built once per rate/venue. */
class LivePrepared(
    val sampleRate: Int,
    val venue: String,
    val kernel: ConvolverKernel,
    val textures: Map<String, List<Array<FloatArray>>>,
)

// -- synthesis ----------------------------------------------------------------

internal object LiveSynthesis {

    private val TEXTURE_KINDS = listOf("bed", "applause", "cheer", "big_cheer", "build")

    // Free applause / cheer recordings by OtoLogic (CC BY 4.0).
    // https://otologic.jp/free/se/applause-cheer01.html
    private val SAMPLE_FILES = mapOf(
        "applause" to listOf("otologic/applause_01.wav", "otologic/applause_02.wav"),
        "cheer" to listOf("otologic/cheer_01.wav", "otologic/cheer_02.wav"),
        "big_cheer" to listOf("otologic/big_cheer_01.wav"),
    )

    fun build(context: Context, rate: Int, venue: String): LivePrepared {
        val ir = loadIr(context, rate, venue)
        val kernel = ConvolverKernel.build(ir, LIVE_HOP, rate)
        val textures = HashMap<String, List<Array<FloatArray>>>()
        for (k in TEXTURE_KINDS) textures[k] = loadTextureSet(context, rate, k)
        return LivePrepared(rate, venue, kernel, textures)
    }

    // -- OtoLogic samples -----------------------------------------------------

    private fun readWav(stream: java.io.InputStream): Pair<Array<FloatArray>, Int> {
        val bytes = stream.use { it.readBytes() }
        require(bytes.size > 44) { "WAV too short" }
        require(String(bytes, 0, 4, Charsets.US_ASCII) == "RIFF") { "not a RIFF file" }
        require(String(bytes, 8, 4, Charsets.US_ASCII) == "WAVE") { "not a WAVE file" }

        fun u16(off: Int): Int =
            (bytes[off].toInt() and 0xFF) or ((bytes[off + 1].toInt() and 0xFF) shl 8)

        fun u32(off: Int): Long =
            (u16(off).toLong()) or (u16(off + 2).toLong() shl 16)

        var pos = 12
        var format = 1
        var channels = 2
        var sampleRate = 44100
        var bits = 16
        var dataOffset = -1
        var dataSize = 0
        while (pos + 8 <= bytes.size) {
            val id = String(bytes, pos, 4, Charsets.US_ASCII)
            val size = u32(pos + 4).toInt()
            val body = pos + 8
            if (id == "fmt " && body + 16 <= bytes.size) {
                format = u16(body)
                channels = u16(body + 2)
                sampleRate = u32(body + 4).toInt()
                bits = u16(body + 14)
                if (format == 0xFFFE && body + 26 <= bytes.size) {
                    format = u16(body + 24)
                }
            } else if (id == "data") {
                dataOffset = body
                dataSize = min(size, bytes.size - body)
            }
            val advance = size + (size and 1)
            if (advance <= 0) break
            pos = body + advance
        }
        require(dataOffset >= 0 && dataSize > 0) { "WAV has no data chunk" }
        require(channels > 0) { "WAV has no channels" }

        val bytesPerSample = max(1, bits / 8)
        val frames = dataSize / (bytesPerSample * channels)
        val out = Array(2) { FloatArray(frames) }
        for (i in 0 until frames) {
            for (c in 0 until min(2, channels)) {
                val base = dataOffset + (i * channels + c) * bytesPerSample
                val v: Float = when {
                    format == 3 && bits == 32 ->
                        Float.fromBits(
                            (bytes[base].toInt() and 0xFF) or
                                ((bytes[base + 1].toInt() and 0xFF) shl 8) or
                                ((bytes[base + 2].toInt() and 0xFF) shl 16) or
                                ((bytes[base + 3].toInt() and 0xFF) shl 24)
                        )
                    bits == 16 -> {
                        val s = (bytes[base].toInt() and 0xFF) or
                            ((bytes[base + 1].toInt() and 0xFF) shl 8)
                        (if (s >= 0x8000) s - 0x10000 else s) / 32768f
                    }
                    bits == 24 -> {
                        var s = (bytes[base].toInt() and 0xFF) or
                            ((bytes[base + 1].toInt() and 0xFF) shl 8) or
                            ((bytes[base + 2].toInt() and 0xFF) shl 16)
                        if (s and 0x800000 != 0) s = s or -0x1000000
                        s / 8388608f
                    }
                    bits == 32 -> {
                        var s = (bytes[base].toInt() and 0xFF) or
                            ((bytes[base + 1].toInt() and 0xFF) shl 8) or
                            ((bytes[base + 2].toInt() and 0xFF) shl 16) or
                            ((bytes[base + 3].toInt() and 0xFF) shl 24)
                        s / 2147483648f
                    }
                    bits == 8 -> ((bytes[base].toInt() and 0xFF) - 128) / 128f
                    else -> 0f
                }
                out[c][i] = v
            }
        }
        if (channels == 1) out[1] = out[0].copyOf()
        return out to sampleRate
    }

    private fun resample(src: Array<FloatArray>, srcRate: Int, dstRate: Int): Array<FloatArray> {
        if (srcRate == dstRate || src[0].isEmpty()) return src
        val n = src[0].size
        val nOut = max(1, (n.toDouble() * dstRate / srcRate).toInt())
        val step = srcRate.toDouble() / dstRate
        return Array(2) { c ->
            val from = src[c]
            FloatArray(nOut) { i ->
                val p = i * step
                val i0 = p.toInt()
                if (i0 >= n - 1) from[n - 1]
                else {
                    val f = (p - i0).toFloat()
                    from[i0] * (1f - f) + from[i0 + 1] * f
                }
            }
        }
    }

    /** Match the perceived level of the different OtoLogic takes. */
    private fun standardize(x: Array<FloatArray>): Array<FloatArray> {
        var peak = 0f
        var sum = 0.0
        var count = 0
        for (c in x) for (v in c) {
            val a = abs(v)
            if (a > peak) peak = a
            sum += v.toDouble() * v
            count++
        }
        val rms = if (count > 0) sqrt(sum / count) else 0.0
        if (peak < 1e-9f || rms < 1e-9) return x
        val gain = min(0.12 / rms, 0.98 / peak).toFloat()
        for (c in x) for (i in c.indices) c[i] *= gain
        return x
    }

    private fun loadSample(context: Context, rate: Int, rel: String): Array<FloatArray> {
        val stem = rel.substringAfterLast('/').substringBeforeLast('.')
        val file = File(liveDir(context), "sample_${stem}_${rate}_$LIVE_SAMPLE_VERSION.bin")
        readChannels(file, 2, 1)?.let { return it }
        val (data, srcRate) = readWav(context.assets.open(rel))
        val tex = standardize(resample(data, srcRate, rate))
        writeChannels(file, tex)
        return tex
    }

    private fun loadTextureSet(context: Context, rate: Int, kind: String): List<Array<FloatArray>> {
        val files = SAMPLE_FILES[kind]
        if (files != null) {
            try {
                return files.map { loadSample(context, rate, it) }
            } catch (_: Throwable) {
                // fall through to the synthesised texture
            }
        }
        return listOf(loadTexture(context, rate, kind))
    }

    // -- impulse response -----------------------------------------------------

    private fun velvet(nImpIn: Int, length: Int, rnd: Random): FloatArray {
        val out = FloatArray(length)
        if (nImpIn <= 0 || length <= 0) return out
        val nImp = min(nImpIn, length)
        val step = length.toDouble() / nImp
        for (i in 0 until nImp) {
            var p = ((i + rnd.nextDouble()) * step).toInt()
            if (p < 0) p = 0 else if (p > length - 1) p = length - 1
            out[p] += if (rnd.nextBoolean()) -1f else 1f
        }
        return out
    }

    private fun damp(x: FloatArray, rate: Int, damping: Float) {
        val fc = max(600.0f, 6500f - 5200f * damping.coerceIn(0f, 1f))
        val a = exp(-2.0 * Math.PI * fc / rate).toFloat()
        val b = 1f - a
        var y = 0f
        for (i in x.indices) {
            y = b * x[i] + a * y
            x[i] = y
        }
    }

    fun buildIr(rate: Int, venue: String): Array<FloatArray> {
        val cfg = LiveVenues.get(venue)
        val rt60 = cfg.rt60
        val decay = min(rt60 * 0.95f, 3.2f)
        val body = (decay * rate).toInt()
        val preN = max(1, (cfg.preDelay * rate).toInt())
        val total = preN + body
        val rnd = Random(("$venue:$LIVE_IR_VERSION").hashCode().toLong())

        val env = FloatArray(body)
        for (i in 0 until body) {
            val t = i.toDouble() / rate
            env[i] = Math.pow(10.0, -3.0 * t / rt60).toFloat()
        }
        val nImp = (14000f * decay).toInt()
        val chans = Array(2) { FloatArray(body) }
        for (c in 0 until 2) {
            val noise = velvet(nImp, body, rnd)
            for (i in 0 until body) chans[c][i] = noise[i] * env[i]
            damp(chans[c], rate, cfg.damping)
        }
        val out = Array(2) { FloatArray(total) }
        for (c in 0 until 2) System.arraycopy(chans[c], 0, out[c], preN, body)

        val tapsMax = min(0.055f, decay * 0.4f)
        for (i in 0 until 9) {
            val tap = lerp(0.002f, tapsMax, rnd.nextFloat())
            val gain = Math.pow(10.0, -3.0 * tap / rt60).toFloat() * lerp(0.35f, 0.75f, rnd.nextFloat())
            val idx = preN + (tap * rate).toInt()
            if (idx in 0 until total) {
                val amp = gain * (0.5f + 0.5f * cfg.size * 0.4f)
                val side = if (rnd.nextBoolean()) 1f else -1f
                out[0][idx] += amp
                out[1][idx] += amp * (0.75f * side)
            }
        }
        var energy = 0.0
        for (c in 0 until 2) for (v in out[c]) energy += v.toDouble() * v
        val e = sqrt(energy)
        if (e > 1e-9) {
            val inv = (1.0 / e).toFloat()
            for (c in 0 until 2) for (i in out[c].indices) out[c][i] *= inv
        }
        return out
    }

    // -- textures -------------------------------------------------------------

    private fun gauss(rnd: Random): Float {
        var u1 = rnd.nextDouble()
        if (u1 < 1e-12) u1 = 1e-12
        val u2 = rnd.nextDouble()
        return (sqrt(-2.0 * ln(u1)) * cos(2.0 * Math.PI * u2)).toFloat()
    }

    private fun bandpass(x: FloatArray, rate: Int, lo: Float, hi: Float, passes: Int) {
        val fs = rate.toFloat()
        val hp = Array(passes) { OnePoleHP(lo, fs) }
        val lp = Array(passes) { OnePoleLP(hi, fs) }
        for (i in x.indices) {
            var v = x[i]
            for (f in hp) v = f.process(v)
            for (f in lp) v = f.process(v)
            x[i] = v
        }
    }

    private fun peakNormalize(x: FloatArray, peak: Float): FloatArray {
        var m = 0f
        for (v in x) {
            val a = abs(v)
            if (a > m) m = a
        }
        if (m < 1e-9f) return x
        val s = peak / m
        for (i in x.indices) x[i] *= s
        return x
    }

    private fun attackRelease(n: Int, rate: Int, attack: Float, release: Float, hold: Float): FloatArray {
        val a = max(1, (attack * rate).toInt())
        val h = max(0, (hold * rate).toInt())
        var r = n - a - h
        if (r < 1) r = 1
        val env = FloatArray(n)
        for (i in 0 until min(a, n)) env[i] = i.toFloat() / a
        for (i in 0 until h) {
            val idx = a + i
            if (idx < n) env[idx] = 1f
        }
        for (i in 0 until r) {
            val idx = a + h + i
            if (idx >= n) break
            val t = if (r > 1) 1f - i.toFloat() / (r - 1) else 0f
            env[idx] = Math.pow(t.toDouble(), (1.0 + 0.32 * release).toDouble()).toFloat()
        }
        return env
    }

    private fun claps(n: Int, rate: Int, rnd: Random, count: Int, gain: Float): FloatArray {
        val env = FloatArray(n)
        val decay = 0.0035 * rate
        for (c in 0 until count) {
            val ln = (lerp(0.004f, 0.018f, rnd.nextFloat()) * rate).toInt()
            if (ln + 1 >= n) continue
            val p = rnd.nextInt(n - ln)
            val amp = lerp(0.35f, 1f, rnd.nextFloat()) * gain
            for (k in 0 until ln) env[p + k] += (amp * exp(-k / decay)).toFloat()
        }
        val hp = FloatArray(n) { gauss(rnd) }
        bandpass(hp, rate, 1100f, rate * 0.45f, 2)
        for (i in 0 until n) hp[i] *= env[i]
        return hp
    }

    private fun voiceShape(x: FloatArray, rate: Int) {
        val out = FloatArray(x.size)
        val peaks = arrayOf(
            floatArrayOf(620f, 1f, 260f),
            floatArrayOf(1150f, 0.8f, 340f),
            floatArrayOf(2050f, 0.55f, 520f),
            floatArrayOf(3200f, 0.3f, 900f),
        )
        for (pk in peaks) {
            val q = (pk[0] / pk[2]).coerceAtLeast(0.7f)
            val bq = BandBiquad(rate.toFloat(), pk[0], q)
            for (i in x.indices) out[i] += pk[1] * bq.process(x[i])
        }
        val lo = FloatArray(x.size) { x[it] }
        bandpass(lo, rate, 120f, 6000f, 1)
        for (i in x.indices) x[i] = out[i] + 0.12f * lo[i]
    }

    private fun seamless(x: Array<FloatArray>, rate: Int, fade: Float): Array<FloatArray> {
        val m = (fade * rate).toInt()
        val n = x[0].size
        if (m <= 0 || n <= 2 * m) return x
        for (c in x.indices) {
            val head = FloatArray(m) { x[c][it] }
            val tail = FloatArray(m) { x[c][n - m + it] }
            for (i in 0 until m) {
                val r = if (m > 1) i.toFloat() / (m - 1) else 0f
                x[c][i] = head[i] * (1f - r) + tail[i] * r
            }
        }
        return Array(x.size) { x[it].copyOf(n - m) }
    }

    fun buildTexture(kind: String, rate: Int): Array<FloatArray> {
        val rnd = Random(("$kind:$LIVE_TEXTURE_VERSION").hashCode().toLong())

        if (kind == "bed") {
            val n = (11.0 * rate).toInt()
            val chans = Array(2) {
                val x = FloatArray(n) { gauss(rnd) }
                bandpass(x, rate, 90f, 900f, 2)
                val ph1 = rnd.nextDouble() * 6.28
                val ph2 = rnd.nextDouble() * 6.28
                for (i in 0 until n) {
                    val slow = 0.65f + 0.35f * sin(2.0 * Math.PI * 0.11 * i / rate + ph1).toFloat()
                    val med = 0.8f + 0.2f * sin(2.0 * Math.PI * 0.37 * i / rate + ph2).toFloat()
                    x[i] *= slow * med
                }
                peakNormalize(x, 0.75f)
            }
            return seamless(chans, rate, 0.35f)
        }

        if (kind == "applause") {
            val n = (5.0 * rate).toInt()
            val env = attackRelease(n, rate, 0.12f, 4.4f, 0.2f)
            return Array(2) {
                val base = FloatArray(n) { gauss(rnd) }
                bandpass(base, rate, 700f, 5200f, 2)
                for (i in 0 until n) base[i] *= env[i]
                val clar = claps(n, rate, rnd, 420, 0.7f)
                for (i in 0 until n) base[i] += clar[i]
                peakNormalize(base, 0.85f)
            }
        }

        if (kind == "cheer") {
            val n = (4.6 * rate).toInt()
            val env = attackRelease(n, rate, 0.45f, 2.8f, 0.35f)
            return Array(2) {
                val x = FloatArray(n) { gauss(rnd) }
                voiceShape(x, rate)
                for (i in 0 until n) x[i] *= env[i]
                val clar = claps(n, rate, rnd, 90, 0.35f)
                for (i in 0 until n) x[i] += clar[i]
                peakNormalize(x, 0.85f)
            }
        }

        if (kind == "big_cheer") {
            val n = (5.5 * rate).toInt()
            val env = attackRelease(n, rate, 0.9f, 4.2f, 0.4f)
            val roarEnv = attackRelease(n, rate, 1.3f, 4.3f, 0.5f)
            return Array(2) {
                val x = FloatArray(n) { gauss(rnd) }
                voiceShape(x, rate)
                for (i in 0 until n) x[i] *= env[i]
                val roar = FloatArray(n) { gauss(rnd) }
                bandpass(roar, rate, 70f, 420f, 2)
                for (i in 0 until n) x[i] += roar[i] * roarEnv[i] * 0.7f
                val clar = claps(n, rate, rnd, 260, 0.5f)
                for (i in 0 until n) x[i] += clar[i]
                peakNormalize(x, 0.9f)
            }
        }

        // "build": a rising filtered-noise riser
        val n = (1.9 * rate).toInt()
        val edges = floatArrayOf(180f, 360f, 720f, 1440f, 2880f, 5760f, 11000f)
        return Array(2) {
            val out = FloatArray(n)
            for (bi in 0 until edges.size - 1) {
                val band = FloatArray(n) { gauss(rnd) }
                bandpass(band, rate, edges[bi], edges[bi + 1], 3)
                val start = bi.toFloat() / edges.size * 0.55f
                for (i in 0 until n) {
                    val curve = ((i.toFloat() / rate - start) / 0.6f).coerceIn(0f, 1f)
                    out[i] += band[i] * curve
                }
            }
            val ar = attackRelease(n, rate, 0.25f, 1.4f, 0.1f)
            for (i in 0 until n) out[i] *= ar[i]
            peakNormalize(out, 0.85f)
        }
    }

    // -- disk cache -----------------------------------------------------------

    private fun liveDir(context: Context): File {
        val d = File(context.filesDir, "live")
        if (!d.exists()) d.mkdirs()
        return d
    }

    private fun writeChannels(file: File, data: Array<FloatArray>) {
        try {
            DataOutputStream(BufferedOutputStream(FileOutputStream(file))).use { out ->
                out.writeInt(data.size)
                out.writeInt(data[0].size)
                for (c in data) for (v in c) out.writeFloat(v)
            }
        } catch (_: Throwable) {
            // cache is best-effort
        }
    }

    private fun readChannels(file: File, expectChannels: Int, minLen: Int): Array<FloatArray>? {
        if (!file.exists()) return null
        return try {
            DataInputStream(BufferedInputStream(FileInputStream(file))).use { ins ->
                val ch = ins.readInt()
                val n = ins.readInt()
                if (ch != expectChannels || n < minLen) null
                else Array(ch) { FloatArray(n) { ins.readFloat() } }
            }
        } catch (_: Throwable) {
            null
        }
    }

    fun loadIr(context: Context, rate: Int, venue: String): Array<FloatArray> {
        val file = File(liveDir(context), "ir_${venue}_${rate}_$LIVE_IR_VERSION.bin")
        readChannels(file, 2, 1)?.let { return it }
        val ir = buildIr(rate, venue)
        writeChannels(file, ir)
        return ir
    }

    fun loadTexture(context: Context, rate: Int, kind: String): Array<FloatArray> {
        val file = File(liveDir(context), "crowd_${kind}_${rate}_$LIVE_TEXTURE_VERSION.bin")
        readChannels(file, 2, 1)?.let { return it }
        val tex = buildTexture(kind, rate)
        writeChannels(file, tex)
        return tex
    }
}

// -- small DSP helpers --------------------------------------------------------

private fun lerp(a: Float, b: Float, t: Float): Float = a + (b - a) * t

private class OnePoleLP(freq: Float, fs: Float) {
    private val a = exp(-2.0 * Math.PI * freq / fs).toFloat()
    private val b = 1f - a
    private var y = 0f
    fun process(x: Float): Float {
        y = b * x + a * y
        return y
    }
}

private class OnePoleHP(freq: Float, fs: Float) {
    private val lp = OnePoleLP(freq, fs)
    fun process(x: Float): Float = x - lp.process(x)
}

/** RBJ band-pass (constant 0 dB peak gain). */
private class BandBiquad(fs: Float, f0: Float, q: Float) {
    private var b0 = 0f
    private var b2 = 0f
    private var a1 = 0f
    private var a2 = 0f
    private var x1 = 0f
    private var x2 = 0f
    private var y1 = 0f
    private var y2 = 0f

    init {
        val w0 = 2.0 * Math.PI * (f0 / fs)
        val alpha = sin(w0) / (2.0 * q)
        val a0 = 1.0 + alpha
        b0 = (alpha / a0).toFloat()
        b2 = (-alpha / a0).toFloat()
        a1 = (-2.0 * cos(w0) / a0).toFloat()
        a2 = ((1.0 - alpha) / a0).toFloat()
    }

    fun process(x: Float): Float {
        val y = b0 * x + b2 * x2 - a1 * y1 - a2 * y2
        x2 = x1; x1 = x
        y2 = y1; y1 = y
        return y
    }
}

// -- process-wide realtime bridge ---------------------------------------------

/**
 * Live Effect state shared lock-free between the UI thread and the audio thread,
 * exactly like [AudioEffects] for the equalizer.
 */
object LiveEffects {
    @Volatile
    var settings: LiveSettings = LiveSettings()

    @Volatile
    var durationMs: Long = 0L

    /** Playback position (ms), refreshed by the view model. */
    @Volatile
    var positionMs: Long = 0L

    /** Bumped on seek / track change / settings change to reset the DSP. */
    @Volatile
    var syncToken: Int = 0

    /** Combined automatic + manual reactions for the current track. */
    @Volatile
    var events: List<LiveEvent> = emptyList()

    @Volatile
    private var preparedValue: LivePrepared? = null

    private var appContext: Context? = null
    private val executor = Executors.newSingleThreadExecutor { r ->
        Thread(r, "live-prepare").apply { isDaemon = true }
    }
    private val lock = Any()
    private var pendingKey: String? = null

    fun init(context: Context) {
        appContext = context.applicationContext
    }

    fun current(): LivePrepared? = preparedValue

    fun ready(): Boolean = preparedValue != null

    /** Builds (or loads from cache) the IR + textures for a rate/venue pair. */
    fun ensurePrepared(rate: Int, venue: String) {
        val cur = preparedValue
        if (cur != null && cur.sampleRate == rate && cur.venue == venue) return
        val ctx = appContext ?: return
        val key = "$rate:$venue"
        synchronized(lock) {
            if (pendingKey == key) return
            pendingKey = key
        }
        executor.execute {
            val p = try {
                LiveSynthesis.build(ctx, rate, venue)
            } catch (_: Throwable) {
                null
            }
            if (p != null) preparedValue = p
            synchronized(lock) { if (pendingKey == key) pendingKey = null }
        }
    }

    /** New track / seek: replace the reactions and force a DSP resync. */
    fun compileTrack(durationMs: Long, events: List<LiveEvent>) {
        this.durationMs = max(0L, durationMs)
        this.events = events.sortedBy { it.time }
        syncToken++
    }

    /** Force the processor to re-seek to [positionMs] (settings toggled, etc.). */
    fun resync() {
        syncToken++
    }
}

// -- convolution runtime ------------------------------------------------------

/** Uniform partitioned overlap-save convolution on interleaved blocks. */
private class Convolver(private val k: ConvolverKernel, private val ch: Int) {
    private val hop = k.hop
    private val nfft = k.nfft
    private val nparts = k.nparts
    private val fft = SimpleFft(nfft)
    private val xr = Array(ch) { FloatArray(nparts * nfft) }
    private val xi = Array(ch) { FloatArray(nparts * nfft) }
    private val prev = Array(ch) { FloatArray(hop) }
    private val fre = FloatArray(nfft)
    private val fim = FloatArray(nfft)
    private val yre = FloatArray(nfft)
    private val yim = FloatArray(nfft)
    private var idx = 0
    private var inPending = FloatArray(0)
    private var outPending = FloatArray(0)

    fun reset() {
        for (c in 0 until ch) {
            java.util.Arrays.fill(xr[c], 0f)
            java.util.Arrays.fill(xi[c], 0f)
            java.util.Arrays.fill(prev[c], 0f)
        }
        idx = 0
        inPending = FloatArray(0)
        outPending = FloatArray(0)
    }

    fun process(input: FloatArray, frames: Int): FloatArray {
        val add = frames * ch
        val merged = FloatArray(inPending.size + add)
        System.arraycopy(inPending, 0, merged, 0, inPending.size)
        System.arraycopy(input, 0, merged, inPending.size, add)
        inPending = merged

        var avail = inPending.size / ch
        if (avail >= hop) {
            val chunks = ArrayList<FloatArray>()
            var offset = 0
            while (avail >= hop) {
                chunks.add(frame(inPending, offset))
                offset += hop * ch
                avail -= hop
            }
            val left = FloatArray(inPending.size - offset)
            System.arraycopy(inPending, offset, left, 0, left.size)
            inPending = left

            var total = outPending.size
            for (c in chunks) total += c.size
            val mergedOut = FloatArray(total)
            System.arraycopy(outPending, 0, mergedOut, 0, outPending.size)
            var p = outPending.size
            for (c in chunks) {
                System.arraycopy(c, 0, mergedOut, p, c.size)
                p += c.size
            }
            outPending = mergedOut
        }

        val need = frames * ch
        val out = FloatArray(need)
        if (outPending.size >= need) {
            System.arraycopy(outPending, 0, out, 0, need)
            val left = FloatArray(outPending.size - need)
            System.arraycopy(outPending, need, left, 0, left.size)
            outPending = left
        } else {
            System.arraycopy(outPending, 0, out, 0, outPending.size)
            outPending = FloatArray(0)
        }
        return out
    }

    /** Convolve one hop of interleaved input at [base]. */
    private fun frame(src: FloatArray, base: Int): FloatArray {
        for (c in 0 until ch) {
            java.util.Arrays.fill(fre, 0f)
            java.util.Arrays.fill(fim, 0f)
            System.arraycopy(prev[c], 0, fre, 0, hop)
            var s = base + c
            for (i in 0 until hop) {
                fre[hop + i] = src[s]
                s += ch
            }
            fft.forward(fre, fim)
            System.arraycopy(fre, 0, xr[c], idx * nfft, nfft)
            System.arraycopy(fim, 0, xi[c], idx * nfft, nfft)
        }

        val out = FloatArray(hop * ch)
        for (c in 0 until ch) {
            val kc = min(c, k.kr.size - 1)
            val kr = k.kr[kc]
            val ki = k.ki[kc]
            val xrc = xr[c]
            val xic = xi[c]
            java.util.Arrays.fill(yre, 0f)
            java.util.Arrays.fill(yim, 0f)
            for (p in 0 until nparts) {
                var j = idx - p
                if (j < 0) j += nparts
                val xo = j * nfft
                val ho = p * nfft
                for (kk in 0 until nfft) {
                    val hr = kr[ho + kk]
                    val hi = ki[ho + kk]
                    val br = xrc[xo + kk]
                    val bi = xic[xo + kk]
                    yre[kk] += hr * br - hi * bi
                    yim[kk] += hr * bi + hi * br
                }
            }
            fft.inverse(yre, yim)
            var d = c
            for (i in 0 until hop) {
                out[d] = yre[hop + i]
                d += ch
            }
        }

        for (c in 0 until ch) {
            var s = base + c
            for (i in 0 until hop) {
                prev[c][i] = src[s]
                s += ch
            }
        }
        idx = (idx + 1) % nparts
        return out
    }
}

/** One crowd reaction / ambient bed being mixed at the current position. */
private class Voice(
    val data: Array<FloatArray>,
    val gain: Float,
    val gl: Float,
    val gr: Float,
    var offset: Int,
    val loop: Boolean,
) {
    var pos = 0
    val n: Int = data[0].size
}

// -- realtime processor -------------------------------------------------------

/**
 * Applies the venue reverb (partitioned FFT convolution) and mixes the crowd
 * reactions on top of the equalized signal. Runs inside the playback thread.
 */
@androidx.annotation.OptIn(UnstableApi::class)
class LiveAudioProcessor : BaseAudioProcessor() {

    private var channels = 2
    private var floatInput = false
    private var sampleRate = 48000

    private var conv: Convolver? = null
    private var convPrepared: LivePrepared? = null

    private var voices = ArrayList<Voice>()
    private var bed: Voice? = null
    private var pendingIdx = 0
    private var lastEventsRef: List<LiveEvent>? = null

    private var posFrames = 0L
    private var lastToken = Int.MIN_VALUE
    private var appliedSettings: LiveSettings? = null

    private var wet = 0f
    private var width = 0.6f
    private var crowd = 0.5f
    private var intensity = 0.6f

    private val rng = Random(System.nanoTime())
    private var scratch = FloatArray(0)

    override fun onConfigure(inputAudioFormat: AudioProcessor.AudioFormat): AudioProcessor.AudioFormat {
        if (inputAudioFormat.encoding != C.ENCODING_PCM_16BIT &&
            inputAudioFormat.encoding != C.ENCODING_PCM_FLOAT
        ) {
            throw AudioProcessor.UnhandledAudioFormatException(inputAudioFormat)
        }
        channels = inputAudioFormat.channelCount
        floatInput = inputAudioFormat.encoding == C.ENCODING_PCM_FLOAT
        sampleRate = inputAudioFormat.sampleRate
        conv = null
        convPrepared = null
        lastToken = Int.MIN_VALUE
        appliedSettings = null
        lastEventsRef = null
        resetRuntime()
        return inputAudioFormat
    }

    override fun onFlush() = resetRuntime()

    override fun onReset() {
        resetRuntime()
        conv = null
        convPrepared = null
    }

    private fun resetRuntime() {
        // Keep the ambient bed running across seeks; drop the one-shot reactions.
        val b = bed
        voices = ArrayList()
        if (b != null) {
            b.pos = 0
            voices.add(b)
        }
        pendingIdx = 0
        conv?.reset()
    }

    override fun queueInput(inputBuffer: ByteBuffer) {
        val settings = LiveEffects.settings
        val frameBytes = channels * (if (floatInput) 4 else 2)
        val available = inputBuffer.remaining()
        val size = available - (available % frameBytes)
        if (size <= 0) return
        val frames = size / frameBytes

        // Pure passthrough when the effect is off (or the layout is exotic).
        if (!settings.enabled || channels > 2) {
            val out = replaceOutputBuffer(size)
            val dup = inputBuffer.duplicate()
            dup.limit(inputBuffer.position() + size)
            out.put(dup)
            inputBuffer.position(inputBuffer.position() + size)
            out.position(size)
            out.flip()
            return
        }

        val total = frames * channels
        if (scratch.size < total) scratch = FloatArray(total)
        if (floatInput) {
            inputBuffer.asFloatBuffer().get(scratch, 0, total)
        } else {
            val shorts = inputBuffer.asShortBuffer()
            for (i in 0 until total) scratch[i] = shorts.get(i) / 32768f
        }
        inputBuffer.position(inputBuffer.position() + size)

        syncState(settings)

        val out = replaceOutputBuffer(size)
        if (!isActive(settings)) {
            writeOut(out, scratch, total, size)
            posFrames += frames
            return
        }
        processLive(scratch, total, frames)
        writeOut(out, scratch, total, size)
        posFrames += frames
    }

    private fun writeOut(out: ByteBuffer, buf: FloatArray, total: Int, size: Int) {
        if (floatInput) {
            out.asFloatBuffer().put(buf, 0, total)
        } else {
            val shortBuf = out.asShortBuffer()
            for (i in 0 until total) {
                val v = buf[i] * 32768f
                shortBuf.put(i, v.coerceIn(-32768f, 32767f).toInt().toShort())
            }
        }
        out.position(size)
        out.flip()
    }

    /** React to seeks / track changes / settings changes (lock-free handoff). */
    private fun syncState(settings: LiveSettings) {
        val token = LiveEffects.syncToken
        if (token != lastToken) {
            lastToken = token
            posFrames = LiveEffects.positionMs * sampleRate / 1000
            resetRuntime()
        }
        val ev = LiveEffects.events
        if (ev !== lastEventsRef) {
            lastEventsRef = ev
            pendingIdx = 0
        }
        if (settings !== appliedSettings) {
            appliedSettings = settings
            val cfg = LiveVenues.get(settings.venue)
            intensity = settings.intensity.coerceIn(0f, 1f)
            wet = min(0.65f, cfg.wet * (0.35f + 1.15f * intensity))
            width = settings.width.coerceIn(0f, 1f)
            crowd = settings.crowd.coerceIn(0f, 1f)
            if (!settings.enabled) resetRuntime()
        }
    }

    private fun isActive(settings: LiveSettings): Boolean {
        val p = LiveEffects.current()
        if (p == null || p.sampleRate != sampleRate || p.venue != settings.venue) {
            LiveEffects.ensurePrepared(sampleRate, settings.venue)
            return false
        }
        if (p !== convPrepared) {
            convPrepared = p
            conv = Convolver(p.kernel, min(2, channels))
            startBed(settings)
        }
        return true
    }

    private fun startBed(settings: LiveSettings) {
        voices = ArrayList()
        bed = null
        val p = convPrepared ?: return
        val g = 0.16f * settings.crowd.coerceIn(0f, 1f)
        if (g <= 0.001f) return
        val tex = p.textures["bed"]?.firstOrNull() ?: return
        val v = Voice(tex, g, 0.70710678f, 0.70710678f, 0, true)
        bed = v
        voices.add(v)
    }

    private fun processLive(buf: FloatArray, total: Int, frames: Int) {
        val c = conv ?: return
        val rev = c.process(buf, frames)

        val dryK = 1f - 0.3f * wet
        var fade = 1f
        val durMs = LiveEffects.durationMs
        if (durMs > 0) {
            val posMs = posFrames * 1000.0 / sampleRate
            val remain = durMs - posMs
            if (remain < 600.0) fade = (remain / 600.0).coerceIn(0.0, 1.0).toFloat()
        }

        if (channels == 2 && width < 0.999f) {
            var i = 0
            while (i < total) {
                val mono = 0.5f * (rev[i] + rev[i + 1])
                rev[i] = mono + (rev[i] - mono) * width
                rev[i + 1] = mono + (rev[i + 1] - mono) * width
                i += 2
            }
        }

        val mix = wet * fade
        for (i in 0 until total) buf[i] = buf[i] * dryK + rev[i] * mix

        val t = posFrames.toDouble() / sampleRate
        val ev = LiveEffects.events
        val blockEnd = t + frames.toDouble() / sampleRate
        while (pendingIdx < ev.size) {
            val e = ev[pendingIdx]
            if (e.time > blockEnd) break
            spawn(e, t, fade)
            pendingIdx++
        }
        mixVoices(buf, frames)
    }

    private fun spawn(ev: LiveEvent, t: Double, fade: Float) {
        if (crowd <= 0.001f) return
        val p = convPrepared ?: return
        val variants = p.textures[ev.kind]
        if (variants.isNullOrEmpty()) return
        val tex = variants[rng.nextInt(variants.size)]
        val base = LiveKinds.gain(ev.kind)
        val gain = base * ev.gain * crowd * (0.45f + 0.95f * intensity) * fade
        val spread = LiveKinds.pan(ev.kind) * (0.25f + 0.85f * width)
        val pan = (rng.nextFloat() * 2f - 1f) * spread
        val theta = (pan.coerceIn(-1f, 1f) + 1f) * (Math.PI.toFloat() / 4f)
        val gl = cos(theta)
        val gr = sin(theta)
        val offset = max(0, ((ev.time - t) * sampleRate).toInt())
        voices.add(Voice(tex, gain, gl, gr, offset, false))
    }

    private fun mixVoices(buf: FloatArray, frames: Int) {
        if (voices.isEmpty()) return
        val keep = ArrayList<Voice>(voices.size)
        for (v in voices) {
            val a = v.offset
            v.offset = 0
            if (a < frames) {
                val m = min(frames - a, v.n - v.pos)
                if (m > 0) {
                    if (channels == 2) {
                        val d0 = v.data[0]
                        val d1 = v.data[1]
                        val g0 = v.gain * v.gl
                        val g1 = v.gain * v.gr
                        for (i in 0 until m) {
                            val o = (a + i) * 2
                            buf[o] += d0[v.pos + i] * g0
                            buf[o + 1] += d1[v.pos + i] * g1
                        }
                    } else {
                        val d0 = v.data[0]
                        val d1 = v.data[1]
                        for (i in 0 until m) {
                            buf[a + i] += (d0[v.pos + i] * v.gl + d1[v.pos + i] * v.gr) * v.gain
                        }
                    }
                    v.pos += m
                }
            }
            if (v.loop) {
                if (v.pos >= v.n) v.pos = 0
                keep.add(v)
            } else if (v.pos < v.n) {
                keep.add(v)
            }
        }
        voices = keep
    }
}

