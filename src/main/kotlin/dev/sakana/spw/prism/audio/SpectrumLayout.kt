package dev.sakana.spw.prism.audio

import kotlin.math.pow

/**
 * Analysis data lives on a dense logarithmic grid. Display bars are cut from
 * that grid by equal *screen* layout units, so FFT size and low/high analysis
 * density can never change bar width or spacing.
 */
object SpectrumLayout {
    const val POINTS = 512
    const val MIN_HZ = 20.0
    const val MAX_HZ = 20000.0

    fun frequencyAt(position: Double): Double {
        val p = position.coerceIn(0.0, POINTS.toDouble())
        return MIN_HZ * (MAX_HZ / MIN_HZ).pow(p / POINTS)
    }

    fun fractionForHz(hz: Double): Double =
        (kotlin.math.ln(hz / MIN_HZ) / kotlin.math.ln(MAX_HZ / MIN_HZ)).coerceIn(0.0, 1.0)

    fun barLow(index: Int, count: Int): Double =
        MIN_HZ * (MAX_HZ / MIN_HZ).pow(index.toDouble() / count)

    fun barHigh(index: Int, count: Int): Double =
        MIN_HZ * (MAX_HZ / MIN_HZ).pow((index + 1).toDouble() / count)

    /** One bar uses peak-preserving aggregation from the dense analysis grid. */
    fun aggregateBars(dense: FloatArray, count: Int): FloatArray {
        if (count <= 0) return FloatArray(0)
        val bars = FloatArray(count)
        for (i in 0 until count) {
            val low = barLow(i, count)
            val high = barHigh(i, count)
            val from = ((fractionForHz(low)) * POINTS).toInt().coerceIn(0, POINTS - 1)
            val to = ((fractionForHz(high)) * POINTS).toInt().coerceIn(from + 1, POINTS)
            var peak = 0f
            for (p in from until to) peak = kotlin.math.max(peak, dense[p])
            bars[i] = peak
        }
        return bars
    }
}
