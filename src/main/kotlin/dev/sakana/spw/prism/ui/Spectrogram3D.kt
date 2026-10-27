package dev.sakana.spw.prism.ui

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PointMode
import androidx.compose.ui.graphics.StrokeCap
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

/** Frequency samples across the depth axis. */
private const val FREQ_SAMPLES = 384

/** Brightness buckets, so the whole cloud goes out in a handful of draw calls. */
private const val LEVELS = 9

/** How tall the amplitude peaks are, in slab space. */
private const val RIDGE_HEIGHT = 0.26f

/** The cloud starts a little behind the front edge so the floor stays visible. */
private const val DEPTH_MARGIN = 0.12f

/** Frames kept in the history buffer. */
const val SPECTROGRAM_ROWS = 91

/**
 * Point-cloud spectrogram: time runs left to right, frequency recedes from the front edge
 * (low) to the back (log-scaled high), and amplitude lifts a sample off the floor.
 *
 * Everything is drawn as particles — one dot per (time, frequency) sample, bucketed by
 * brightness and depth so a handful of `drawPoints` calls cover the whole cloud.
 * Particles have no surfaces to intersect, which removes the clipping ("穿模") that the
 * old filled ribbons showed where a near ridge crossed a far one.
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
    val columns = min(FREQ_SAMPLES, bins)

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
        DEPTH_MARGIN + (1f - DEPTH_MARGIN) * (if (columns > 1) column.toFloat() / (columns - 1) else 0f)

    // Floor grid first, so the cloud floats above it.
    val gridPath = Path()
    for (step in 0..6) {
        val z = DEPTH_MARGIN + (1f - DEPTH_MARGIN) * step / 6f
        val a = position(-1f, 0f, z)
        val b = position(1f, 0f, z)
        gridPath.moveTo(a.x, a.y)
        gridPath.lineTo(b.x, b.y)
    }
    for (step in 0..12) {
        val x = -1f + 2f * step / 12f
        val a = position(x, 0f, DEPTH_MARGIN)
        val b = position(x, 0f, 1f)
        gridPath.moveTo(a.x, a.y)
        gridPath.lineTo(b.x, b.y)
    }
    drawPath(gridPath, axisColor.copy(alpha = 0.30f), style = Stroke(1f))

    // Bucket the cloud into brightness × depth bands before drawing anything.
    val buckets = Array(LEVELS * 2) { ArrayList<Offset>(rows * columns / (LEVELS * 2) + 16) }
    for (index in 0 until rows) {
        val row = history[index]
        val x = timeX(index)
        for (column in 0 until columns) {
            val bin = (column * (bins - 1) / max(columns - 1, 1)).coerceIn(0, bins - 1)
            val level = levelOf(row.getOrElse(bin) { 0f })
            if (level <= 0f) continue
            val z = depthOf(column)
            buckets[(level * (LEVELS - 1)).toInt().coerceIn(0, LEVELS - 1) * 2 + (if (z < 0.55f) 0 else 1)]
                .add(position(x, level * RIDGE_HEIGHT, z))
        }
    }

    val nearWidth = 3.2.dp.toPx()
    val farWidth = 2.2.dp.toPx()
    // Far band first so near particles sit on top, bright levels last so peaks read.
    for (band in 1 downTo 0) {
        val width = if (band == 0) nearWidth else farWidth
        for (level in 0 until LEVELS) {
            val points = buckets[level * 2 + band]
            if (points.isEmpty()) continue
            drawPoints(
                points = points,
                pointMode = PointMode.Points,
                color = greyAt(level.toFloat() / (LEVELS - 1)),
                strokeWidth = width,
                cap = StrokeCap.Round,
            )
        }
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

/** Stretch the useful part of the dBFS scale, like the flat graph modes do. */
private fun levelOf(value: Float): Float = ((value - 0.30f) / 0.62f).coerceIn(0f, 1f)

/** Near-monochrome ramp matching the reference visualiser. */
private fun greyAt(position: Float): Color {
    val scaled = position.coerceIn(0f, 1f) * (GREY_STOPS.size - 1)
    val index = scaled.toInt().coerceIn(0, GREY_STOPS.size - 2)
    return lerp(GREY_STOPS[index], GREY_STOPS[index + 1], scaled - index)
}

private val GREY_STOPS = listOf(
    Color(0xFF1A222B),
    Color(0xFF384453),
    Color(0xFF4E5D6C),
    Color(0xFF7C8B9A),
    Color(0xFFAEBBC7),
    Color(0xFFD8E1EA),
    Color(0xFFF4F8FC),
)
