package com.portable.musicplayer.player

import androidx.media3.common.C
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.audio.BaseAudioProcessor
import androidx.media3.common.util.UnstableApi
import java.nio.ByteBuffer
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

/** 10-band equalizer layout + factory presets (mirrors the desktop engine). */
object EqBands {
    val FREQS = floatArrayOf(31.25f, 62.5f, 125f, 250f, 500f, 1000f, 2000f, 4000f, 8000f, 16000f)
    val LABELS = arrayOf("31", "63", "125", "250", "500", "1k", "2k", "4k", "8k", "16k")
    const val COUNT = 10
    const val GAIN_MIN = -12f
    const val GAIN_MAX = 12f
    const val BASS_MAX = 15f
    const val PREAMP_MIN = -12f
    const val PREAMP_MAX = 6f
    const val BASS_FREQ = 110f
    const val Q = 1.4f
    const val NORMALIZE_TARGET_DB = -18f
    const val NORMALIZE_MAX_BOOST_DB = 9f
    const val NORMALIZE_MAX_CUT_DB = -15f
    fun flat(): FloatArray = FloatArray(COUNT)
}

/** Factory presets: gain (dB) per band, keyed by a stable id. */
object EqPresets {
    val GAINS: Map<String, FloatArray> = linkedMapOf(
        "flat" to floatArrayOf(0f, 0f, 0f, 0f, 0f, 0f, 0f, 0f, 0f, 0f),
        "rock" to floatArrayOf(5f, 4f, -1f, -3f, -1f, 2f, 4f, 5f, 5f, 5f),
        "pop" to floatArrayOf(-1f, 2f, 4f, 4f, 2f, 0f, -1f, -1f, 1f, 2f),
        "jazz" to floatArrayOf(3f, 2f, 1f, 2f, -2f, -2f, 0f, 1f, 2f, 3f),
        "classical" to floatArrayOf(4f, 3f, 2f, 0f, 0f, 0f, -1f, -1f, -2f, -3f),
        "dance" to floatArrayOf(6f, 5f, 3f, 0f, 0f, -3f, -4f, -4f, 0f, 0f),
        "live" to floatArrayOf(-2f, 0f, 2f, 3f, 3f, 3f, 2f, 1f, 0f, -1f),
        "vocal" to floatArrayOf(-3f, -2f, 0f, 3f, 5f, 5f, 4f, 2f, 0f, -1f),
        "bass" to floatArrayOf(9f, 7f, 5f, 2f, 0f, 0f, 0f, 0f, 0f, 0f),
        "treble" to floatArrayOf(0f, 0f, 0f, 0f, 0f, 1f, 3f, 5f, 6f, 7f),
    )

    val LABELS: Map<String, String> = linkedMapOf(
        "flat" to "フラット",
        "rock" to "ロック",
        "pop" to "ポップ",
        "jazz" to "ジャズ",
        "classical" to "クラシック",
        "dance" to "ダンス",
        "live" to "ライブ",
        "vocal" to "ボーカル強調",
        "bass" to "低音強調",
        "treble" to "高音強調",
    )

    /** Display name for a preset id; custom names are returned unchanged. */
    fun label(id: String): String = LABELS[id] ?: id
}

/**
 * Immutable equalizer snapshot. The UI thread publishes a new instance and the
 * audio thread picks it up by reference comparison, so no locking is needed.
 */
data class EqSettings(
    val enabled: Boolean = true,
    val gains: FloatArray = EqBands.flat(),
    val bass: Float = 0f,
    val preampDb: Float = 0f,
    val normalizationDb: Float = 0f,
) {
    val filtersActive: Boolean
        get() = enabled && (bass != 0f || gains.any { it != 0f })

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is EqSettings) return false
        return enabled == other.enabled &&
            bass == other.bass &&
            preampDb == other.preampDb &&
            normalizationDb == other.normalizationDb &&
            gains.contentEquals(other.gains)
    }

    override fun hashCode(): Int {
        var result = enabled.hashCode()
        result = 31 * result + gains.contentHashCode()
        result = 31 * result + bass.hashCode()
        result = 31 * result + preampDb.hashCode()
        result = 31 * result + normalizationDb.hashCode()
        return result
    }
}

/** Process-wide equalizer state: written by the UI, read by the audio thread. */
object AudioEffects {
    @Volatile
    var settings: EqSettings = EqSettings()

    fun update(transform: (EqSettings) -> EqSettings) {
        settings = transform(settings)
    }
}

/** Second-order IIR section (direct form I). */
private class Biquad {
    private var b0 = 1f
    private var b1 = 0f
    private var b2 = 0f
    private var a1 = 0f
    private var a2 = 0f
    private var x1 = 0f
    private var x2 = 0f
    private var y1 = 0f
    private var y2 = 0f

    fun identity() {
        b0 = 1f; b1 = 0f; b2 = 0f; a1 = 0f; a2 = 0f
        reset()
    }

    fun peaking(fs: Float, f0: Float, gainDb: Float, q: Float) {
        val a = 10.0.pow(gainDb / 40.0)
        val w0 = 2.0 * Math.PI * (f0 / fs)
        val alpha = sin(w0) / (2.0 * q)
        val a0 = 1.0 + alpha / a
        b0 = ((1.0 + alpha * a) / a0).toFloat()
        b1 = ((-2.0 * cos(w0)) / a0).toFloat()
        b2 = ((1.0 - alpha * a) / a0).toFloat()
        a1 = ((-2.0 * cos(w0)) / a0).toFloat()
        a2 = ((1.0 - alpha / a) / a0).toFloat()
        reset()
    }

    fun lowShelf(fs: Float, f0: Float, gainDb: Float) {
        val a = 10.0.pow(gainDb / 40.0)
        val w0 = 2.0 * Math.PI * (f0 / fs)
        val cw = cos(w0)
        val alpha = sin(w0) / 2.0 * sqrt((a + 1.0 / a) * (1.0 / 1.0 - 1.0) + 2.0)
        val beta = 2.0 * sqrt(a) * alpha
        val a0 = (a + 1.0) + (a - 1.0) * cw + beta
        b0 = ((a * ((a + 1.0) - (a - 1.0) * cw + beta)) / a0).toFloat()
        b1 = ((2.0 * a * ((a - 1.0) - (a + 1.0) * cw)) / a0).toFloat()
        b2 = ((a * ((a + 1.0) - (a - 1.0) * cw - beta)) / a0).toFloat()
        a1 = ((-2.0 * ((a - 1.0) + (a + 1.0) * cw)) / a0).toFloat()
        a2 = (((a + 1.0) + (a - 1.0) * cw - beta) / a0).toFloat()
        reset()
    }

    fun reset() {
        x1 = 0f; x2 = 0f; y1 = 0f; y2 = 0f
    }

    fun process(x: Float): Float {
        val y = b0 * x + b1 * x1 + b2 * x2 - a1 * y1 - a2 * y2
        x2 = x1
        x1 = x
        y2 = y1
        y1 = y
        return y
    }
}

/**
 * Ten peaking bands + a low shelf (bass boost), driven by [AudioEffects].
 * Runs inside the playback thread; settings are swapped lock-free.
 */
@androidx.annotation.OptIn(UnstableApi::class)
class EqualizerAudioProcessor : BaseAudioProcessor() {

    private var channels = 2
    private var floatInput = false
    private var sampleRate = 48000f
    private var filters: Array<Biquad> = Array((EqBands.COUNT + 1) * 2) { Biquad() }
    private var applied: EqSettings? = null
    private var bypass = true
    private var gain = 1f
    private var scratch = FloatArray(0)

    override fun onConfigure(inputAudioFormat: AudioProcessor.AudioFormat): AudioProcessor.AudioFormat {
        if (inputAudioFormat.encoding != C.ENCODING_PCM_16BIT &&
            inputAudioFormat.encoding != C.ENCODING_PCM_FLOAT
        ) {
            throw AudioProcessor.UnhandledAudioFormatException(inputAudioFormat)
        }
        channels = inputAudioFormat.channelCount
        floatInput = inputAudioFormat.encoding == C.ENCODING_PCM_FLOAT
        sampleRate = inputAudioFormat.sampleRate.toFloat()
        filters = Array((EqBands.COUNT + 1) * channels) { Biquad() }
        applied = null
        return inputAudioFormat
    }

    override fun onFlush() {
        for (filter in filters) filter.reset()
    }

    override fun onReset() {
        for (filter in filters) filter.reset()
        applied = null
    }

    override fun queueInput(inputBuffer: ByteBuffer) {
        val settings = AudioEffects.settings
        if (applied !== settings) {
            applySettings(settings)
            applied = settings
        }

        val frameBytes = channels * (if (floatInput) 4 else 2)
        val available = inputBuffer.remaining()
        val size = available - (available % frameBytes)
        if (size <= 0) return
        val total = (size / frameBytes) * channels
        if (scratch.size < total) scratch = FloatArray(total)

        val out = replaceOutputBuffer(size)
        if (floatInput) {
            inputBuffer.asFloatBuffer().get(scratch, 0, total)
            process(scratch, total)
            out.asFloatBuffer().put(scratch, 0, total)
        } else {
            val shorts = inputBuffer.asShortBuffer()
            for (i in 0 until total) scratch[i] = shorts.get(i) / 32768f
            process(scratch, total)
            val outShorts = out.asShortBuffer()
            for (i in 0 until total) {
                val v = scratch[i] * 32768f
                outShorts.put(i, v.coerceIn(-32768f, 32767f).toInt().toShort())
            }
        }
        inputBuffer.position(inputBuffer.position() + size)
        out.position(size)
        out.flip()
    }

    private fun applySettings(settings: EqSettings) {
        val linear = dbToLinear(settings.normalizationDb) *
            (if (settings.enabled) dbToLinear(settings.preampDb) else 1f)
        gain = linear
        bypass = !settings.filtersActive
        if (bypass) return

        for (ch in 0 until channels) {
            for (b in 0 until EqBands.COUNT) {
                val filter = filters[b * channels + ch]
                val g = settings.gains.getOrElse(b) { 0f }
                if (g == 0f) filter.identity() else filter.peaking(sampleRate, EqBands.FREQS[b], g, EqBands.Q)
            }
            val shelf = filters[EqBands.COUNT * channels + ch]
            if (settings.bass == 0f) shelf.identity()
            else shelf.lowShelf(sampleRate, EqBands.BASS_FREQ, settings.bass)
        }
    }

    private fun process(buffer: FloatArray, total: Int) {
        if (bypass) {
            if (gain != 1f) {
                for (i in 0 until total) buffer[i] = (buffer[i] * gain).coerceIn(-1f, 1f)
            }
            return
        }
        val bands = EqBands.COUNT + 1
        var i = 0
        while (i < total) {
            for (ch in 0 until channels) {
                var x = buffer[i + ch]
                var b = 0
                while (b < bands) {
                    x = filters[b * channels + ch].process(x)
                    b++
                }
                buffer[i + ch] = (x * gain).coerceIn(-1f, 1f)
            }
            i += channels
        }
    }

    private fun dbToLinear(db: Float): Float =
        if (db == 0f) 1f else 10.0.pow(db / 20.0).toFloat()
}

/** RMS of a decoded block, in dBFS (used by the per-track normalizer). */
fun rmsDb(samples: FloatArray, count: Int): Float {
    if (count <= 0) return -Float.MAX_VALUE
    var sum = 0.0
    for (i in 0 until count) {
        val v = samples[i].toDouble()
        sum += v * v
    }
    val rms = sqrt(sum / count)
    return if (rms <= 1e-7) -120f else (20.0 * kotlin.math.log10(rms)).toFloat()
}

/** Gain (dB) that moves [measuredDb] towards the normalisation target. */
fun normalizationGainDb(
    measuredDb: Float,
    targetDb: Float = EqBands.NORMALIZE_TARGET_DB,
): Float {
    if (!measuredDb.isFinite()) return 0f
    return (targetDb - measuredDb)
        .coerceIn(EqBands.NORMALIZE_MAX_CUT_DB, EqBands.NORMALIZE_MAX_BOOST_DB)
}

/** True when every band, the shelf and the preamp are at 0 dB. */
fun EqSettings.isFlat(): Boolean =
    !enabled || (abs(bass) < 0.05f && abs(preampDb) < 0.05f && gains.all { abs(it) < 0.05f })
