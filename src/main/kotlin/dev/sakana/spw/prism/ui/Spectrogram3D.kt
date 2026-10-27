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
import dev.sakana.spw.prism.settings.SpectrogramView
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

/** Frequency curtains drawn across the depth axis. */
private const val CURTAINS = 112

/** Horizontal colour samples per curtain, so time still shows through. */
private const val SHADE_STEPS = 20

/** How tall the amplitude ridges are, in slab space. */
private const val RIDGE_HEIGHT = 0.19f

/** The surface starts a little behind the front edge so the floor stays visible. */
private const val DEPTH_MARGIN = 0.12f

/** Frames kept in the history buffer. */
const val SPECTROGRAM_ROWS = 64

/**
 * Classic 3D spectrogram: time runs left to right, frequency recedes from the front edge
 * (low) to the back (log-scaled high), amplitude is drawn as height.
 *
 * The surface is a stack of "curtains" — one filled ribbon per frequency bin, painted far
 * to near. Opaque fills hide what is behind them, so only the ridges poke out, which is
 * what makes it read as a surface without a full hidden-surface pass.
 */
internal fun DrawScope.drawSpectrogram(
    history: List<FloatArray>,
    view: SpectrogramView,
    area: Rect,
    textMeasurer: TextMeasurer,
    labelStyle: TextStyle,
    axisColor: Color,
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

    fun position(x: Float, h: Float, z: Float): Offset {
        val p = raw(x, h, z)
        return Offset(centerX + (p.x - midX) * scale, centerY - (p.y - midY) * scale)
    }

    fun timeX(index: Int): Float = if (rows > 1) 1f - 2f * index / (rows - 1) else 0f
    fun depthOf(column: Int): Float =
        (DEPTH_MARGIN + (1f - DEPTH_MARGIN) * (if (columns > 1) column.toFloat() / (columns - 1) else 0f))

    // Floor grid first: the surface sits behind the front margin, so these stay visible.
    val gridPath = Path()
    for (step in 0..6) {
        val z = DEPTH_MARGIN + (1f - DEPTH_MARGIN) * step / 6f
        val a = position(-1f, 0f, z)
        val b = position(1f, 0f, z)
        gridPath.moveTo(a.x, a.y)
        gridPath.lineTo(b.x, b.y)
    }
    val timeTicks = 12
    for (step in 0..timeTicks) {
        val x = -1f + 2f * step / timeTicks
        val a = position(x, 0f, DEPTH_MARGIN)
        val b = position(x, 0f, 1f)
        gridPath.moveTo(a.x, a.y)
        gridPath.lineTo(b.x, b.y)
    }
    drawPath(gridPath, axisColor.copy(alpha = 0.35f), style = Stroke(1f))

    fun levelOf(value: Float): Float = ((value - 0.30f) / 0.62f).coerceIn(0f, 1f)

    // Far (high frequency) curtains first; nearer ones paint over them.
    for (column in columns - 1 downTo 0) {
        val bin = (column * (bins - 1) / max(columns - 1, 1)).coerceIn(0, bins - 1)
        val z = depthOf(column)

        val ridge = Path()
        val body = Path()
        for (index in 0 until rows) {
            val amplitude = levelOf(history[index].getOrElse(bin) { 0f })
            val point = position(timeX(index), amplitude * RIDGE_HEIGHT, z)
            if (index == 0) {
                ridge.moveTo(point.x, point.y)
                body.moveTo(point.x, point.y)
            } else {
                ridge.lineTo(point.x, point.y)
                body.lineTo(point.x, point.y)
            }
        }
        for (index in rows - 1 downTo 0) {
            val floor = position(timeX(index), 0f, z)
            body.lineTo(floor.x, floor.y)
        }
        body.close()

        val stops = ArrayList<Color>(SHADE_STEPS + 1)
        for (step in 0..SHADE_STEPS) {
            val from = step * rows / (SHADE_STEPS + 1)
            val to = ((step + 1) * rows / (SHADE_STEPS + 1)).coerceAtLeast(from + 1)
            var sum = 0f
            var count = 0
            for (index in from until min(to, rows)) {
                sum += levelOf(history[index].getOrElse(bin) { 0f })
                count++
            }
            stops += spectrumGrey(if (count > 0) sum / count else 0f)
        }
        drawPath(
            body,
            Brush.linearGradient(stops, position(-1f, 0f, z), position(1f, 0f, z)),
        )
        drawPath(ridge, ridgeColor(0.55f), style = Stroke(1f))
    }

    // Axes.
    val backLeft = position(-1f, 0f, 1f)
    val backRight = position(1f, 0f, 1f)
    val frontLeft = position(-1f, 0f, DEPTH_MARGIN)
    val frontRight = position(1f, 0f, DEPTH_MARGIN)
    val floorEdge = Path().apply {
        moveTo(backLeft.x, backLeft.y)
        lineTo(backRight.x, backRight.y)
        lineTo(frontRight.x, frontRight.y)
        lineTo(frontLeft.x, frontLeft.y)
        close()
    }
    drawPath(floorEdge, axisColor, style = Stroke(1.dp.toPx()))

    listOf(10.0, 100.0, 1000.0, 10000.0).forEach { frequency ->
        val z = DEPTH_MARGIN + (1f - DEPTH_MARGIN) * SpectrumLayout.fractionForHz(frequency).toFloat()
        val anchor = position(1f, 0f, z)
        val label = if (frequency >= 10000) "10000" else if (frequency >= 1000) "1000" else frequency.toInt().toString()
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
    val centre = position(0f, 0f, DEPTH_MARGIN)
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

/** Near-monochrome ramp, matching the reference visualiser (dark floor → white peaks). */
private fun spectrumGrey(level: Float): Color {
    val stops = GREY_STOPS
    // Lift the mid-tones: the reference reads as a light grey sheet, not a high-contrast one.
    val scaled = Math.pow(level.coerceIn(0f, 1f).toDouble(), 0.72).toFloat() * (stops.size - 1)
    val index = scaled.toInt().coerceIn(0, stops.size - 2)
    return lerp(stops[index], stops[index + 1], scaled - index)
}

private val GREY_STOPS = listOf(
    Color(0xFF070A0F),
    Color(0xFF1B2530),
    Color(0xFF3E4C5A),
    Color(0xFF7B8B9B),
    Color(0xFFB9C6D2),
    Color(0xFFF2F7FC),
)

private fun ridgeColor(alpha: Float) = Color(0xFFE8F1FA).copy(alpha = alpha)
