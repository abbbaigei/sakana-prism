package dev.sakana.spw.prism.ui

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.unit.dp
import dev.sakana.spw.prism.audio.SpectrumLayout
import dev.sakana.spw.prism.settings.WaterfallView
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

/** Frequency curtains drawn across the depth axis. */
private const val CURTAINS = 96

/** Horizontal colour samples per curtain, so time still shows through. */
private const val SHADE_STEPS = 16

/** How tall the amplitude ridges are, in slab space. */
private const val RIDGE_HEIGHT = 0.20f

/** Frames kept in the history buffer. */
const val WATERFALL_ROWS = 46

/**
 * Draws a classic 3D waterfall: time runs left to right, frequency recedes from the
 * front edge (low) to the back (log-scaled high), and amplitude is drawn as height.
 *
 * Rendering is a stack of "curtains" — one filled ribbon per frequency bin, far to near,
 * with its top edge following the amplitude over time. Opaque fills hide everything
 * behind them except the ridges that poke up, which is what makes a waterfall read as a
 * surface without paying for a full hidden-surface mesh.
 */
internal fun DrawScope.drawWaterfall(
    history: List<FloatArray>,
    view: WaterfallView,
    palette: ArtworkPalette,
    area: Rect,
    textMeasurer: TextMeasurer,
    labelStyle: TextStyle,
    axisColor: Color,
    ridgeColor: Color,
) {
    if (history.isEmpty() || area.width <= 0f || area.height <= 0f) return

    val rows = history.size
    val bins = history.first().size
    if (bins == 0) return
    val columns = min(CURTAINS, bins)

    val yaw = Math.toRadians(view.yaw.toDouble()).toFloat()
    val pitch = Math.toRadians(view.pitch.toDouble()).toFloat()
    val cosYaw = cos(yaw)
    val sinYaw = sin(yaw)
    val sinPitch = sin(pitch)
    val cosPitch = cos(pitch)
    val focus = 2.2f
    val perspective = 1.1f

    // x = time (-1 oldest, +1 newest), z = frequency (0 = low, 1 = high), h = amplitude.
    fun raw(x: Float, h: Float, z: Float): Offset {
        val xr = x * cosYaw + z * sinYaw
        val zr = -x * sinYaw + z * cosYaw
        val k = focus / (focus + zr * perspective)
        return Offset(xr * k, (zr * sinPitch + h * cosPitch) * k)
    }

    // Fit the slab footprint (all four corners at rest height) into the plot area.
    val corners = listOf(raw(-1f, 0f, 0f), raw(1f, 0f, 0f), raw(-1f, 0f, 1f), raw(1f, 0f, 1f))
    val minX = corners.minOf { it.x }
    val maxX = corners.maxOf { it.x }
    val minY = corners.minOf { it.y }
    val maxY = corners.maxOf { it.y }
    val padding = 18.dp.toPx()
    val scale = min(
        (area.width - padding * 2f) / max(maxX - minX, 0.0001f),
        (area.height - padding * 2f) / max(maxY - minY, 0.0001f),
    )
    val centerX = area.left + area.width / 2f
    val centerY = area.top + area.height / 2f
    val midX = (minX + maxX) / 2f
    val midY = (minY + maxY) / 2f

    /** Screen position; the projected y is flipped so taller ridges go up. */
    fun position(x: Float, h: Float, z: Float): Offset {
        val p = raw(x, h, z)
        return Offset(centerX + (p.x - midX) * scale, centerY - (p.y - midY) * scale)
    }

    fun timeX(index: Int): Float = if (rows > 1) 1f - 2f * index / (rows - 1) else 0f

    val dark = Color(0xFF070B12)

    // Far (high frequency) curtains first; nearer ones paint over them.
    for (column in columns - 1 downTo 0) {
        val bin = (column * (bins - 1) / max(columns - 1, 1)).coerceIn(0, bins - 1)
        val z = if (columns > 1) column.toFloat() / (columns - 1) else 0f

        val ridge = Path()
        var ampSum = 0f
        for (index in 0 until rows) {
            val amplitude = shaped(history[index].getOrElse(bin) { 0f })
            ampSum += amplitude
            val point = position(timeX(index), amplitude * RIDGE_HEIGHT, z)
            if (index == 0) ridge.moveTo(point.x, point.y) else ridge.lineTo(point.x, point.y)
        }

        // One closed contour: ridge across, then the floor run back. Building it by hand
        // (instead of addPath + lineTo) keeps the winding simple, otherwise the fill
        // rule punches holes where the two runs overlap.
        val body = Path()
        for (index in 0 until rows) {
            val amplitude = shaped(history[index].getOrElse(bin) { 0f })
            val point = position(timeX(index), amplitude * RIDGE_HEIGHT, z)
            if (index == 0) body.moveTo(point.x, point.y) else body.lineTo(point.x, point.y)
        }
        for (index in rows - 1 downTo 0) {
            val floor = position(timeX(index), 0f, z)
            body.lineTo(floor.x, floor.y)
        }
        body.close()

        // Shade along time: average the amplitude into a handful of gradient stops.
        val stops = ArrayList<Color>(SHADE_STEPS + 1)
        for (step in 0..SHADE_STEPS) {
            val from = step * rows / (SHADE_STEPS + 1)
            val to = ((step + 1) * rows / (SHADE_STEPS + 1)).coerceAtLeast(from + 1)
            var sum = 0f
            var count = 0
            for (index in from until min(to, rows)) {
                sum += shaped(history[index].getOrElse(bin) { 0f })
                count++
            }
            stops += curtainColor(if (count > 0) sum / count else 0f, palette, dark)
        }
        val start = position(-1f, 0f, z)
        val end = position(1f, 0f, z)
        drawPath(body, Brush.linearGradient(stops, start, end))
        drawPath(ridge, ridgeColor.copy(alpha = 0.75f), style = Stroke(1f))
    }

    // Floor edges + frequency ticks on the right, time label under the front edge.
    val backLeft = position(-1f, 0f, 1f)
    val backRight = position(1f, 0f, 1f)
    val frontLeft = position(-1f, 0f, 0f)
    val frontRight = position(1f, 0f, 0f)
    val floor = Path().apply {
        moveTo(backLeft.x, backLeft.y)
        lineTo(backRight.x, backRight.y)
        lineTo(frontRight.x, frontRight.y)
        lineTo(frontLeft.x, frontLeft.y)
        close()
    }
    drawPath(floor, axisColor, style = Stroke(1.dp.toPx()))

    listOf(10.0, 100.0, 1000.0, 10000.0).forEach { frequency ->
        val z = SpectrumLayout.fractionForHz(frequency).toFloat()
        val anchor = position(1f, 0f, z)
        val label = when {
            frequency >= 10000 -> "10000"
            frequency >= 1000 -> "1000"
            else -> frequency.toInt().toString()
        }
        drawText(
            textMeasurer = textMeasurer,
            text = label,
            topLeft = Offset(anchor.x + 6.dp.toPx(), anchor.y - 6.dp.toPx()),
            style = labelStyle,
        )
    }
    drawText(
        textMeasurer = textMeasurer,
        text = "Hz",
        topLeft = Offset(frontRight.x + 6.dp.toPx(), frontRight.y - 20.dp.toPx()),
        style = labelStyle,
    )
    val centre = position(0f, 0f, 0f)
    drawText(
        textMeasurer = textMeasurer,
        text = "s",
        topLeft = Offset(centre.x - 2.dp.toPx(), centre.y + 8.dp.toPx()),
        style = labelStyle,
    )
    if (rows > 1) {
        val span = (rows - 1) * 0.033f
        drawText(
            textMeasurer = textMeasurer,
            text = "${"%.1f".format(span)} s",
            topLeft = Offset(frontLeft.x + 2.dp.toPx(), frontLeft.y + 8.dp.toPx()),
            style = labelStyle,
        )
    }
}

/** Stretch the useful part of the dBFS scale, like the flat graph modes do. */
private fun shaped(value: Float): Float = ((value - 0.30f) / 0.62f).coerceIn(0f, 1f)

/** Dark floor to palette at full amplitude; kept dim so ridges carry the contrast. */
private fun curtainColor(amplitude: Float, palette: ArtworkPalette, dark: Color): Color {
    val level = shaped(amplitude)
    val low = palette.spectrum.getOrElse(0) { Color(0xFF4DD8C4) }
    val high = palette.spectrum.getOrElse(palette.spectrum.lastIndex) { low }
    val base = if (level < 0.5f) lerp(dark, low, level / 0.5f) else lerp(low, high, (level - 0.5f) / 0.5f)
    return lerp(dark, base, 0.38f)
}
