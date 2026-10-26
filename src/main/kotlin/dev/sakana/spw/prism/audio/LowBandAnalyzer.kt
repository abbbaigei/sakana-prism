package dev.sakana.spw.prism.audio

import dev.sakana.spw.prism.settings.SpectrumSettings
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import kotlin.math.abs
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * Long-window low-band FFT on its own decode stream. Keeping this off the
 * 60 Hz analysis loop prevents a 32768-point transform from delaying visible
 * spectrum snapshots.
 */
internal class LowBandAnalyzer(
    private val bass: BassNative,
    private val path: String,
) : AutoCloseable {
    /** Center of the analysis window, shared with the main 16384 FFT. */
    @Volatile var centerMs: Long = 0L

    private val running = AtomicBoolean(false)
    private data class Frame(val bands: FloatArray, val positionMs: Long)
    private data class State(val latest: Frame?, val previous: Frame?)

    private val state = AtomicReference(State(null, null))
    private var thread: Thread? = null

    fun start() {
        if (!running.compareAndSet(false, true)) return
        thread = Thread({
            try {
                BassDecodeStream(bass, path).use { stream ->
                    val output = FloatArray(BandLayout.LOW_BANDS)
                    while (running.get()) {
                        val windowMs = 32768.0 / stream.sampleRate * 1000.0
                        val center = centerMs
                        stream.seekMs((center - windowMs / 2.0).toLong().coerceAtLeast(0L))
                        val data = stream.readLowFft(SpectrumSettings.lowFftLarge)
                        if (data != null) {
                            aggregate(stream.sampleRate / 2.0, data, output)
                            val frame = Frame(output.copyOf(), center)
                            val old = state.get()
                            state.set(State(frame, old.latest))
                        }
                        Thread.sleep(60L)
                    }
                }
            } catch (_: Throwable) {
                // No low FFT: the main analyzer keeps the 8192-bin fallback.
            }
        }, "SpectrumLowFft").apply {
            isDaemon = true
            priority = Thread.MIN_PRIORITY + 1
        }
        thread!!.start()
    }

    /**
     * Returns the low-band state sampled at the same playback position as the
     * main frame. The low FFT updates at ~16.7 Hz, so adjacent complete frames
     * are interpolated; reading the newest frame directly would mix two times.
     */
    fun readAligned(centerMs: Long, out: FloatArray): Boolean {
        val current = state.get()
        val latest = current.latest ?: return false
        // A seek can leave the low worker far behind; fall back to the main FFT.
        if (abs(centerMs - latest.positionMs) > 300L) return false
        val previous = current.previous
        if (previous != null && latest.positionMs > previous.positionMs) {
            val span = (latest.positionMs - previous.positionMs).toFloat()
            val t = ((centerMs - previous.positionMs).toFloat() / span).coerceIn(0f, 1f)
            for (i in out.indices) {
                out[i] = previous.bands.getOrElse(i) { 0f } +
                    (latest.bands.getOrElse(i) { 0f } - previous.bands.getOrElse(i) { 0f }) * t
            }
        } else {
            latest.bands.copyInto(out, 0, 0, min(out.size, latest.bands.size))
        }
        return true
    }

    private fun aggregate(nyquist: Double, fft: FloatArray, output: FloatArray) {
        val bins = fft.size
        for (band in 0 until BandLayout.LOW_BANDS) {
            val low = BandLayout.low(band)
            val high = BandLayout.high(band)
            val from = (low / nyquist * bins).toInt().coerceIn(1, bins - 1)
            val to = (high / nyquist * bins).toInt().coerceIn(from + 1, bins)
            var peak = 0f
            var sum = 0.0
            for (i in from until to) {
                val v = fft[i]
                if (v > peak) peak = v
                sum += v.toDouble() * v
            }
            val rms = sqrt(sum / (to - from).coerceAtLeast(1)).toFloat()
            // Same dB normalization for both FFTs; the 32k window is true PCM history.
            val rawDb = 20.0 * log10(max(peak.toDouble(), 1e-6))
            val db = rawDb
            output[band] = ((db + 96.0) / 96.0).coerceIn(0.0, 1.0).toFloat()
        }
    }

    override fun close() {
        running.set(false)
        thread?.interrupt()
        thread = null
        state.set(State(null, null))
    }
}
