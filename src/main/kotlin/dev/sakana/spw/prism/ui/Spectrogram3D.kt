package dev.sakana.spw.prism.ui

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.unit.dp
import dev.sakana.spw.prism.audio.SpectrumLayout
import dev.sakana.spw.prism.settings.SpectrogramView
import org.jetbrains.skia.BlendMode
import org.jetbrains.skia.Paint
import org.jetbrains.skia.PaintStrokeCap
import org.jetbrains.skia.VertexMode
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sin

/** Frequency columns of the mesh (the analysis grid has 512 bins). */
private const val MESH_COLS = 128

/** How tall the amplitude peaks are, in slab space. */
private const val RIDGE_HEIGHT = 0.10f

/** The surface starts a little behind the front edge so the floor stays visible. */
private const val DEPTH_MARGIN = 0.12f

/** Frames kept in the history buffer (33 ms each). */
const val SPECTROGRAM_ROWS = 91

/**
 * Shaded-surface spectrogram: time runs left to right, frequency recedes from the front
 * edge (low) to the back (log-scaled high), amplitude is drawn as height.
 *
 * The whole surface goes out as one `drawVertices` call: a heightfield mesh with
 * per-vertex colours that Skia interpolates across each triangle. Triangles are emitted
 * far-to-near so nearer cells paint over farther ones (painter's algorithm on a
 * heightfield is order-correct for a camera above the sheet), and a set of thin ridge
 * polylines on top gives the striated texture the reference has.
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
    if (bins == 0 || rows < 2) return
    val columns = min(MESH_COLS, bins).coerceAtLeast(2)

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

    fun timeX(index: Int): Float = 1f - 2f * index / (rows - 1)
    fun depthOf(column: Int): Float =
        DEPTH_MARGIN + (1f - DEPTH_MARGIN) * (if (columns > 1) column.toFloat() / (columns - 1) else 0f)
    fun binOf(column: Int): Int =
        (column.toFloat() * (bins - 1) / (columns - 1)).toInt().coerceIn(0, bins - 1)

    // Floor grid first, so the surface floats above it.
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

    // A row's width can differ from the newest one right after the bar count changes,
    // so clamp instead of trusting the shared column index.
    fun levelAt(row: FloatArray, column: Int): Float {
        if (row.isEmpty()) return 0f
        val bin = binOf(column).coerceIn(0, row.size - 1)
        return levelOf(row[bin])
    }

    // Heightfield vertices: one per (time, frequency) sample.
    val vertexCount = rows * columns
    val positions = FloatArray(vertexCount * 2)
    val colors = IntArray(vertexCount)
    for (i in 0 until rows) {
        val row = history[i]
        val x = timeX(i)
        for (column in 0 until columns) {
            val level = levelAt(row, column)
            val z = depthOf(column)
            val point = position(x, level * RIDGE_HEIGHT, z)
            val index = i * columns + column
            positions[index * 2] = point.x
            positions[index * 2 + 1] = point.y
            // Far rows sit back in haze; the ridge tops get the brightest values.
            val haze = 1f - 0.25f * z
            colors[index] = greyArgb((level * 0.75f + 0.25f) * haze)
        }
    }

    // Triangles, emitted from the back row forwards so nearer cells win.
    val indices = ShortArray((rows - 1) * (columns - 1) * 6)
    var cursor = 0
    for (column in columns - 1 downTo 1) {
        for (i in 0 until rows - 1) {
            val a = (i * columns + column).toShort()
            val b = ((i + 1) * columns + column).toShort()
            val c = ((i + 1) * columns + column - 1).toShort()
            val d = (i * columns + column - 1).toShort()
            indices[cursor++] = a
            indices[cursor++] = b
            indices[cursor++] = c
            indices[cursor++] = a
            indices[cursor++] = c
            indices[cursor++] = d
        }
    }

    val canvas = drawContext.canvas.nativeCanvas
    val paint = Paint().apply { isAntiAlias = true }
    canvas.drawVertices(
        VertexMode.TRIANGLES,
        positions,
        colors,
        null,
        indices,
        BlendMode.SRC_OVER,
        paint,
    )

    // Ridges: one polyline per frequency band. Drawn in three passes — two wide and faint
    // for the halo, one crisp on top — which fakes the bloom the reference has without
    // paying for an offscreen blur.
    val ridgePath = Path()
    for (column in columns - 1 downTo 1) {
        for (i in 0 until rows) {
            val level = levelAt(history[i], column)
            val point = position(timeX(i), level * RIDGE_HEIGHT, depthOf(column))
            if (i == 0) ridgePath.moveTo(point.x, point.y) else ridgePath.lineTo(point.x, point.y)
        }
    }
    val glowSteps = floatArrayOf(6.5f, 3.2f)
    val glowAlphas = floatArrayOf(0.05f, 0.10f)
    for (step in glowSteps.indices) {
        drawPath(
            ridgePath,
            Color(0xFFBFE4FF).copy(alpha = glowAlphas[step]),
            style = Stroke(glowSteps[step].dp.toPx()),
        )
    }
    drawPath(ridgePath, Color(0x99E8F1FA), style = Stroke(0.9.dp.toPx()))

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
    // "Hz" sits clear of the tick labels instead of colliding with the 10 Hz one.
    drawText(
        textMeasurer = textMeasurer,
        text = "Hz",
        topLeft = Offset(frontRight.x + 34.dp.toPx(), frontRight.y - 4.dp.toPx()),
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

/** Monochrome ramp with the mid-tones lifted, matching the reference sheet. */
internal fun greyArgb(level: Float): Int {
    val t = level.coerceIn(0f, 1f).pow(0.72f) * (GREY_STOPS.size - 1)
    val index = t.toInt().coerceIn(0, GREY_STOPS.size - 2)
    val frac = t - index
    val from = GREY_STOPS[index]
    val to = GREY_STOPS[index + 1]
    val r = lerpChannel((from shr 16) and 0xFF, (to shr 16) and 0xFF, frac)
    val g = lerpChannel((from shr 8) and 0xFF, (to shr 8) and 0xFF, frac)
    val b = lerpChannel(from and 0xFF, to and 0xFF, frac)
    return (0xFF shl 24) or (r shl 16) or (g shl 8) or b
}

private fun lerpChannel(from: Int, to: Int, t: Float): Int = (from + (to - from) * t).toInt().coerceIn(0, 255)

private val GREY_STOPS = intArrayOf(
    0x1A222B,
    0x384453,
    0x4E5D6C,
    0x7C8B9A,
    0xAEBBC7,
    0xD8E1EA,
    0xF4F8FC,
)
