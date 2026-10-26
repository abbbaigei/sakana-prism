package dev.sakana.spw.prism.audio

import dev.sakana.spw.prism.settings.SpectrumSettings
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.sqrt

internal data class SpectrumFrame(
    val bands: FloatArray,
    val peakHz: Int,
    val rms: Float,
    val leftPeak: Float,
    val rightPeak: Float,
    val leftRms: Float,
    val rightRms: Float,
    val stereo: Boolean,
    val correlation: Float,
    val stereoWidth: Float,
    val field: FloatArray,
)

/**
 * Main 16384 FFT + an independent 32768 low-band FFT. The low FFT is only
 * allowed to contribute below 100 Hz; above that the main FFT is authoritative.
 */
internal class SpectrumAnalyzer(
    private val bass: BassNative,
    path: String,
) : AutoCloseable {
    private val stream = BassDecodeStream(bass, path)
    private val use8192 = SpectrumSettings.mainFft8192 || System.getProperty("spw.fft8192") == "true"
    private val fft = FloatArray(if (use8192) 4096 else 8192)
    private val bands = FloatArray(SpectrumLayout.POINTS)
    private val lowIndexForPoint = IntArray(SpectrumLayout.POINTS) { p ->
        val hz = kotlin.math.sqrt(SpectrumLayout.frequencyAt(p.toDouble()) * SpectrumLayout.frequencyAt(p + 1.0))
        var index = 0
        while (index < BandLayout.LOW_BANDS - 1 && hz > BandLayout.high(index)) index++
        index
    }
    private val lowAligned = FloatArray(BandLayout.LOW_BANDS)
    private val lowBands = LowBandAnalyzer(bass, path).also { it.start() }
    private val nyquist = stream.sampleRate / 2.0
    private val pcmFrames = 1024
    private val fieldPairs = 768

    val sampleRate: Int get() = stream.sampleRate
    val channels: Int get() = stream.channels

    fun analyze(positionMs: Long): SpectrumFrame? {
        val fftStart = (positionMs - 90L).coerceAtLeast(0L)
        // Main 16384 FFT and low 32768 FFT must share the same window centre.
        val analysisCenter = fftStart + (16384.0 / stream.sampleRate * 500.0).toLong()
        lowBands.centerMs = analysisCenter
        val hasLow = lowBands.readAligned(analysisCenter, lowAligned)

        stream.seekMs(fftStart)
        val main = (if (use8192) stream.readFft8192() else stream.readFft16384()) ?: return null
        main.copyInto(fft)

        var peakBin = 1
        var peakValue = 0f
        for (i in 1 until fft.size) {
            val hz = i.toDouble() / fft.size * nyquist
            if (hz < 20.0) continue
            val m = fft[i]
            if (m > peakValue) {
                peakValue = m
                peakBin = i
            }
        }

        for (point in 0 until SpectrumLayout.POINTS) {
            val low = SpectrumLayout.frequencyAt(point.toDouble())
            val high = SpectrumLayout.frequencyAt((point + 1).toDouble())
            val from = (low / nyquist * fft.size).toInt().coerceIn(1, fft.size - 1)
            val to = (high / nyquist * fft.size).toInt().coerceIn(from + 1, fft.size)
            var peak = 0f
            for (i in from until to) if (fft[i] > peak) peak = fft[i]
            val mainValue = toDbNorm(peak)
            val centerHz = kotlin.math.sqrt(low * high)
            if (centerHz < 100.0 && hasLow) {
                val lowValue = lowAligned[lowIndexForPoint[point]]
                val weight = lowWeight(centerHz)
                bands[point] = (lowValue * weight + mainValue * (1f - weight)).coerceIn(0f, 1f)
            } else {
                bands[point] = mainValue
            }
        }

        val peakHz = (peakBin.toDouble() / fft.size * nyquist).toInt()
            .coerceIn(20, 20000)
        val pcm = readStereoWindow(positionMs)
        return SpectrumFrame(
            bands = bands.copyOf(),
            peakHz = peakHz,
            rms = sqrt(bands.fold(0.0) { acc, v -> acc + v * v } / bands.size).toFloat(),
            leftPeak = pcm.leftPeak,
            rightPeak = pcm.rightPeak,
            leftRms = pcm.leftRms,
            rightRms = pcm.rightRms,
            stereo = pcm.stereo,
            correlation = pcm.correlation,
            stereoWidth = pcm.stereoWidth,
            field = pcm.field,
        )
    }

    /** 60–100 Hz crossfade: low FFT dominates below 60, main dominates above 100. */
    private fun lowWeight(hz: Double): Float {
        if (hz <= 60.0) return 1f
        if (hz >= 100.0) return 0f
        val t = ((hz - 60.0) / 40.0).coerceIn(0.0, 1.0)
        val smooth = t * t * (3.0 - 2.0 * t)
        return (1.0 - smooth).toFloat()
    }

    private fun toDbNorm(magnitude: Float): Float {
        val db = 20.0 * log10(max(magnitude.toDouble(), 1e-6))
        return ((db + 96.0) / 96.0).coerceIn(0.0, 1.0).toFloat()
    }

    private data class StereoWindow(
        val leftPeak: Float, val rightPeak: Float,
        val leftRms: Float, val rightRms: Float,
        val stereo: Boolean, val correlation: Float, val stereoWidth: Float, val field: FloatArray,
    )

    private fun readStereoWindow(positionMs: Long): StereoWindow {
        stream.seekMs((positionMs - 90L).coerceAtLeast(0L))
        val interleaved = stream.readFloatPcm(pcmFrames)
            ?: return StereoWindow(0f, 0f, 0f, 0f, stream.channels > 1, 0f, 0f, FloatArray(0))
        val channels = stream.channels
        val frames = interleaved.size / channels
        if (frames <= 0) return StereoWindow(0f, 0f, 0f, 0f, channels > 1, 0f, 0f, FloatArray(0))
        var peakL = 0f
        var peakR = 0f
        var sumL = 0.0
        var sumR = 0.0
        var sumLR = 0.0
        var sumLL = 0.0
        var sumRR = 0.0
        var sumMid = 0.0
        var sumSide = 0.0
        val field = FloatArray(fieldPairs * 2)
        val stride = (frames / fieldPairs).coerceAtLeast(1)
        var fieldIndex = 0
        for (i in 0 until frames) {
            val l = interleaved[i * channels]
            val r = if (channels > 1) interleaved[i * channels + 1] else l
            peakL = max(peakL, kotlin.math.abs(l))
            peakR = max(peakR, kotlin.math.abs(r))
            sumL += l.toDouble() * l
            sumR += r.toDouble() * r
            sumLL += l.toDouble() * l
            sumRR += r.toDouble() * r
            sumLR += l.toDouble() * r
            val mid = (l + r) * 0.5
            val side = (l - r) * 0.5
            sumMid += mid.toDouble() * mid
            sumSide += side.toDouble() * side
            if (i % stride == 0 && fieldIndex < fieldPairs) {
                field[fieldIndex * 2] = l
                field[fieldIndex * 2 + 1] = r
                fieldIndex++
            }
        }
        return StereoWindow(
            leftPeak = peakL,
            rightPeak = peakR,
            leftRms = sqrt(sumL / frames).toFloat(),
            rightRms = sqrt(sumR / frames).toFloat(),
            stereo = channels > 1,
            correlation = if (sumLL > 1e-9 && sumRR > 1e-9) {
                (sumLR / sqrt(sumLL * sumRR)).toFloat().coerceIn(-1f, 1f)
            } else 1f,
            stereoWidth = if (sumMid + sumSide > 1e-9) {
                sqrt(sumSide / (sumMid + sumSide)).toFloat().coerceIn(0f, 1f)
            } else 0f,
            field = if (fieldIndex * 2 == field.size) field else field.copyOf(fieldIndex * 2),
        )
    }

    override fun close() {
        lowBands.close()
        stream.close()
    }
}
