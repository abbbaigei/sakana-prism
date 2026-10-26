package dev.sakana.spw.prism.ui

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * Opt-in diagnostics for the sound-field radius mapping.
 *
 * Enable with -Dspw.spectrum.debug=true. Logs once per second:
 * point count, rejected points, min/max radius fraction, and an 8-bin
 * radius histogram. This measures the same mapping used by drawPolarCloud;
 * it does not alter rendering.
 */
internal object SoundFieldDebugStats {
    private val enabled = System.getProperty("spw.spectrum.debug") == "true"
    private var lastLogNanos = 0L

    fun record(field: FloatArray, radius: Float, opacity: Float, density: Int) {
        if (!enabled || field.size < 4 || radius <= 0f) return
        val now = System.nanoTime()
        if (now - lastLogNanos < 1_000_000_000L) return
        lastLogNanos = now

        val count = density.coerceIn(1, 768)
        val pairs = min(768, field.size / 2)
        if (pairs <= 1) return
        var accepted = 0
        var rejected = 0
        var minR = 1f
        var maxR = 0f
        val bins = IntArray(8)

        for (i in 0 until count) {
            val pair = if (count == 1) 0 else
                (i.toDouble() * (pairs - 1) / (count - 1).toDouble()).toInt().coerceIn(0, pairs - 1)
            val l = field[pair * 2].coerceIn(-1f, 1f)
            val r = field[pair * 2 + 1].coerceIn(-1f, 1f)
            val mid = (l + r) * 0.5f
            val side = (l - r) * 0.5f
            val energy = min(1f, abs(mid) + abs(side))
            val aL = ((0.018f + abs(l) * 0.58f) * opacity).coerceIn(0f, 0.82f)
            val aR = ((0.018f + abs(r) * 0.58f) * opacity).coerceIn(0f, 0.82f)
            if (aL <= 0.008f && aR <= 0.008f) {
                rejected++
                continue
            }
            accepted++
            val fraction = (0.025f + 0.975f * energy).coerceIn(0f, 1f)
            minR = min(minR, fraction)
            maxR = max(maxR, fraction)
            bins[(fraction * bins.size).toInt().coerceIn(0, bins.lastIndex)]++
        }
        println(
            "[SoundField] points=$accepted rejected=$rejected " +
                "minR=${"%.3f".format(minR)} maxR=${"%.3f".format(maxR)} " +
                "bins=${bins.joinToString(",")}",
        )
    }
}
