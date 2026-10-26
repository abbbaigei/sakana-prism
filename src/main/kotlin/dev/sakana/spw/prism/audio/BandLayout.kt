package dev.sakana.spw.prism.audio

import kotlin.math.pow

/**
 * Hybrid display layout: more points below 250 Hz, logarithmic above.
 * The same function is used by the analyzer and by the renderer, so a
 * painted x position and the analyzed band boundary can never disagree.
 */
object BandLayout {
    const val COUNT = 160
    const val LOW_BANDS = 64
    const val HIGH_BANDS = 96
    const val LOW_MAX_HZ = 250.0
    const val MIN_HZ = 20.0
    const val MAX_HZ = 20000.0

    fun isLow(index: Int): Boolean = index < LOW_BANDS

    fun frequencyAt(position: Double): Double {
        val p = position.coerceIn(0.0, COUNT.toDouble())
        return if (p <= LOW_BANDS) {
            MIN_HZ * (LOW_MAX_HZ / MIN_HZ).pow(p / LOW_BANDS)
        } else {
            LOW_MAX_HZ * (MAX_HZ / LOW_MAX_HZ).pow((p - LOW_BANDS) / HIGH_BANDS)
        }
    }

    fun low(index: Int): Double = frequencyAt(index.toDouble())
    fun high(index: Int): Double = frequencyAt((index + 1).toDouble())
    fun center(index: Int): Double =
        kotlin.math.sqrt(low(index) * high(index))

}
