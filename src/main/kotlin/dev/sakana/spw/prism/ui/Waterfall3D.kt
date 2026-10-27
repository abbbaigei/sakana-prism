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

/** Frequency columns used for the 3D surface. */
private const val COLUMNS = 96

/** Frames kept in the depth buffer (also the history length the panel builds). */
const val WATERFALL_ROWS = 46

/**
 * Draws the spectrum history as a perspective spectrogram slab: frequency runs left to
 * right on a logarithmic axis, time recedes into the depth of the picture, and each band
 * is coloured by amplitude.
 *
 * A real camera is not needed here — the slab is a flat plane, so a yaw + pitch pair plus
 * a mild perspective divide is enough to get the look, and it keeps the draw loop cheap
 * (one filled quad per history row).
 */
internal fun DrawScope.drawWaterfall(
    history: List<FloatArray>,
    view: WaterfallView,
    palette: ArtworkPalette,
    area: Rect,
    spanSeconds: Float,
    textMeasurer: TextMeasurer,
    labelStyle: TextStyle,
    edgeColor: Color,
) {
    if (history.isEmpty() || area.width <= 0f || area.height <= 0f) return

    val yaw = Math.toRadians(view.yaw.toDouble()).toFloat()
    val pitch = Math.toRadians(view.pitch.toDouble()).toFloat()
    val cosYaw = cos(yaw)
    val sinYaw = sin(yaw)
    val sinPitch = sin(pitch)
    val focus = 2.4f

    // Unit square in slab space: u = frequency (0..1), z = age (0 = now, 1 = oldest).
    fun raw(u: Float, z: Float): Offset {
        val x = u * 2f - 1f
        val xr = x * cosYaw + z * sinYaw
        val zr = -x * sinYaw + z * cosYaw
        val k = focus / (focus + zr * 0.55f)
        return Offset(xr * k, -zr * sinPitch * k)
    }

    val corners = listOf(raw(0f, 0f), raw(1f, 0f), raw(0f, 1f), raw(1f, 1f))
    val minX = corners.minOf { it.x }
    val maxX = corners.maxOf { it.x }
    val minY = corners.minOf { it.y }
    val maxY = corners.maxOf { it.y }
    val padding = 16.dp.toPx()
    val scale = min(
        (area.width - padding * 2f) / max(maxX - minX, 0.0001f),
        (area.height - padding * 2f) / max(maxY - minY, 0.0001f),
    )
    val centerX = area.left + area.width / 2f
    val centerY = area.top + area.height / 2f
    val midX = (minX + maxX) / 2f
    val midY = (minY + maxY) / 2f

    fun project(u: Float, z: Float): Offset {
        val p = raw(u, z)
        return Offset(centerX + (p.x - midX) * scale, centerY + (p.y - midY) * scale)
    }

    val rows = history.size
    val columns = min(COLUMNS, history.first().size).coerceAtLeast(1)
    val step = if (rows > 1) 1f / (rows - 1) else 1f
    val band = (step * 1.06f).coerceAtLeast(0.02f)

    // Far rows first so the near ones paint over them.
    for (row in rows - 1 downTo 0) {
        val values = history[row]
        val zCenter = row * step
        val z0 = (zCenter - band / 2f).coerceAtLeast(0f)
        val z1 = (zCenter + band / 2f).coerceAtMost(1f)
        if (z1 <= z0) continue
        val stops = ArrayList<Color>(columns)
        for (column in 0 until columns) {
            val index = (column * values.size / columns).coerceIn(0, values.size - 1)
            stops += cellColor(values[index], palette)
        }
        val brush = Brush.linearGradient(stops, project(0f, zCenter), project(1f, zCenter))
        val nearLeft = project(0f, z0)
        val nearRight = project(1f, z0)
        val farRight = project(1f, z1)
        val farLeft = project(0f, z1)
        val path = Path().apply {
            moveTo(nearLeft.x, nearLeft.y)
            lineTo(nearRight.x, nearRight.y)
            lineTo(farRight.x, farRight.y)
            lineTo(farLeft.x, farLeft.y)
            close()
        }
        drawPath(path, brush)
    }

    // Slab outline: the front edge is the only one that stays visible.
    val outline = Path().apply {
        val a = project(0f, 0f)
        val b = project(1f, 0f)
        val c = project(1f, 1f)
        val d = project(0f, 1f)
        moveTo(a.x, a.y)
        lineTo(b.x, b.y)
        lineTo(c.x, c.y)
        lineTo(d.x, d.y)
        close()
    }
    drawPath(outline, edgeColor, style = Stroke(1.dp.toPx()))

    // Frequency ticks along the front edge.
    listOf(100.0, 1000.0, 10000.0).forEach { frequency ->
        val fraction = SpectrumLayout.fractionForHz(frequency).toFloat()
        val anchor = project(fraction, 0f)
        val label = if (frequency >= 1000) "${(frequency / 1000).toInt()}k" else frequency.toInt().toString()
        drawText(
            textMeasurer = textMeasurer,
            text = label,
            topLeft = Offset(anchor.x - 7.dp.toPx(), anchor.y + 5.dp.toPx()),
            style = labelStyle,
        )
    }
    if (spanSeconds > 0.2f) {
        val anchor = project(0f, 1f)
        drawText(
            textMeasurer = textMeasurer,
            text = "${"%.1f".format(spanSeconds)} s",
            topLeft = Offset(anchor.x - 4.dp.toPx(), anchor.y - 16.dp.toPx()),
            style = labelStyle,
        )
    }
}

/** Dark panel at silence, palette at full scale, with a brighter top end. */
private fun cellColor(value: Float, palette: ArtworkPalette): Color {
    // Music lives well above -60 dBFS, so stretch the useful part of the scale;
    // otherwise nearly every cell lands on the bright end and the surface goes flat.
    val normalised = ((value - 0.30f) / 0.62f).coerceIn(0f, 1f)
    val level = normalised * normalised * 0.35f + normalised * 0.65f
    val dark = Color(0xFF070B12)
    val low = palette.spectrum.getOrElse(0) { Color(0xFF4DD8C4) }
    val high = palette.spectrum.getOrElse(palette.spectrum.lastIndex) { low }
    return if (level < 0.55f) {
        lerp(dark, low, level / 0.55f)
    } else {
        lerp(low, high, (level - 0.55f) / 0.45f)
    }
}
