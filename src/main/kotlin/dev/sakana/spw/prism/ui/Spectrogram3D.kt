package dev.sakana.spw.prism.ui

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.unit.dp
import dev.sakana.spw.prism.audio.SpectrumLayout
import dev.sakana.spw.prism.settings.SpectrogramGlow
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
private const val RIDGE_HEIGHT = 0.20f

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
    glow: SpectrogramGlow = SpectrogramGlow.MEDIUM,
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

    // Include the peak relief in the bounds so taller ridges cannot clip at the top.
    val corners = listOf(
        raw(-1f, 0f, 0f), raw(1f, 0f, 0f), raw(-1f, 0f, 1f), raw(1f, 0f, 1f),
        raw(-1f, RIDGE_HEIGHT, 0f), raw(1f, RIDGE_HEIGHT, 0f),
        raw(-1f, RIDGE_HEIGHT, 1f), raw(1f, RIDGE_HEIGHT, 1f),
    )
    val minX = corners.minOf { it.x }
    val maxX = corners.maxOf { it.x }
    val minY = corners.minOf { it.y }
    val maxY = corners.maxOf { it.y }
    val padding = 8.dp.toPx()
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
    // Depth is the frequency coordinate itself: 20 Hz sits on the front edge, 20 kHz on the
    // back edge, and both the surface and the tick labels go through SpectrumLayout, so a
    // feature at 1 kHz is drawn exactly where the "1000" tick is.
    val binStep = if (columns > 1) (bins - 1).toDouble() / (columns - 1) else 0.0
    fun binOf(column: Int): Int =
        (column * binStep).toInt().coerceIn(0, bins - 1)
    fun hzOf(column: Int): Double =
        SpectrumLayout.frequencyAt(column * binStep / (bins - 1) * SpectrumLayout.POINTS)
    fun depthOf(column: Int): Float =
        DEPTH_MARGIN + (1f - DEPTH_MARGIN) * SpectrumLayout.fractionForHz(hzOf(column)).toFloat()

    // Floor grid first, so the surface floats above it. The outermost time lines are
    // skipped: they read as hard side borders, which the reference does not have.
    val gridPath = Path()
    for (step in 0..6) {
        val z = DEPTH_MARGIN + (1f - DEPTH_MARGIN) * step / 6f
        val a = position(-1f, 0f, z)
        val b = position(1f, 0f, z)
        gridPath.moveTo(a.x, a.y)
        gridPath.lineTo(b.x, b.y)
    }
    for (step in 1 until 12) {
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

    // Heightfield vertices: one per (time, frequency) sample. Buffers and the index list
    // are reused between frames — rebuilding 68,580 triangle indices every frame was pure
    // garbage, and the layout only changes with the grid size.
    val vertexCount = rows * columns
    val positions = SpectrogramCache.positions(vertexCount)
    val colors = SpectrogramCache.colors(vertexCount)
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
            colors[index] = greyAt((level * 0.75f + 0.25f) * haze)
        }
    }

    // Triangles, emitted from the back row forwards so nearer cells win.
    val indices = SpectrogramCache.indices(rows, columns)

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

    // Ridges: one polyline per frequency band. This is the most expensive part of the
    // frame (a path segment per vertex, stroked three times), so every other band is
    // enough to keep the striated look at roughly half the cost.
    val ridgePath = Path()
    var ridgeColumn = columns - 1
    while (ridgeColumn >= 1) {
        for (i in 0 until rows) {
            val level = levelAt(history[i], ridgeColumn)
            val point = position(timeX(i), level * RIDGE_HEIGHT, depthOf(ridgeColumn))
            if (i == 0) ridgePath.moveTo(point.x, point.y) else ridgePath.lineTo(point.x, point.y)
        }
        ridgeColumn -= 1
    }
    // Halo under the crisp ridge line. Wide strokes over a path this long are the most
    // expensive thing in the frame, so the strength is a user setting, not a guess.
    when (glow) {
        SpectrogramGlow.OFF -> Unit
        SpectrogramGlow.MEDIUM -> drawPath(
            ridgePath,
            Color(0xFFCFE9FF).copy(alpha = 0.09f),
            style = Stroke(2.6.dp.toPx()),
        )
        SpectrogramGlow.STRONG -> {
            drawPath(ridgePath, Color(0xFFBFE4FF).copy(alpha = 0.05f), style = Stroke(5.0.dp.toPx()))
            drawPath(ridgePath, Color(0xFFCFE9FF).copy(alpha = 0.09f), style = Stroke(2.6.dp.toPx()))
        }
    }
    drawPath(ridgePath, Color(0x99E8F1FA), style = Stroke(0.9.dp.toPx()))

    // Axes.
    val backLeft = position(-1f, 0f, 1f)
    val backRight = position(1f, 0f, 1f)
    val frontLeft = position(-1f, 0f, DEPTH_MARGIN)
    val frontRight = position(1f, 0f, DEPTH_MARGIN)
    // Only the front edge stays: the side edges read as hard borders around the sheet.
    drawPath(
        Path().apply {
            moveTo(frontLeft.x, frontLeft.y)
            lineTo(frontRight.x, frontRight.y)
        },
        axisColor,
        style = Stroke(1.dp.toPx()),
    )

    // The depth axis is exactly SpectrumLayout's domain: 20 Hz at the front edge to 20 kHz
    // at the back. The axis line runs along the right edge and every tick is placed with
    // the same mapping the surface uses, so the labels are the coordinate.
    drawLine(
        axisColor,
        Offset(frontRight.x, frontRight.y),
        Offset(backRight.x, backRight.y),
        1.dp.toPx(),
    )
    // Dark shadow keeps the numbers readable where bright ridges run underneath them.
    val axisLabel = labelStyle.copy(shadow = Shadow(Color(0xC0000000), Offset(1f, 1f), 2.5f))
    val ticks = listOf(20.0, 100.0, 1000.0, 10000.0, 20000.0)
    ticks.forEachIndexed { index, frequency ->
        val fraction = SpectrumLayout.fractionForHz(frequency).toFloat()
        val z = DEPTH_MARGIN + (1f - DEPTH_MARGIN) * fraction
        val anchor = position(1f, 0f, z)
        val label = when {
            frequency >= 20000.0 -> "20k"
            frequency >= 10000.0 -> "10k"
            frequency >= 1000.0 -> "1k"
            else -> frequency.toInt().toString()
        }
        // Tick on the axis, then the label just outside it.
        drawLine(
            axisColor,
            Offset(anchor.x, anchor.y),
            Offset(anchor.x + 11.dp.toPx(), anchor.y),
            1.dp.toPx(),
        )
        // 10k and 20k are only a tenth of the depth apart, so the last one is lifted to
        // keep the two from touching.
        val lift = if (index == ticks.lastIndex) 13.dp.toPx() else 6.dp.toPx()
        drawText(
            textMeasurer = textMeasurer,
            text = label,
            topLeft = Offset(anchor.x + 18.dp.toPx(), anchor.y - lift),
            style = axisLabel,
        )
    }
    // "Hz" sits under the front tick, clear of the labels themselves.
    drawText(
        textMeasurer = textMeasurer,
        text = "Hz",
        topLeft = Offset(frontRight.x + 18.dp.toPx(), frontRight.y + 10.dp.toPx()),
        style = axisLabel,
    )
    // The sheet runs from the newest frame back to `span` seconds, so its middle is half
    // of that — label the centre tick accordingly instead of just "s".
    val span = (rows - 1) * 0.033f
    val centre = position(0f, 0f, DEPTH_MARGIN)
    drawText(
        textMeasurer = textMeasurer,
        text = "${"%.1f".format(span / 2f)} s",
        topLeft = Offset(centre.x - 8.dp.toPx(), centre.y + 8.dp.toPx()),
        style = labelStyle,
    )
    if (rows > 1) {
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

/**
 * Reusable per-frame buffers. The renderer runs on the UI thread only, and the grid size
 * stays constant between resizes, so these arrays (and the 68k-entry index list) are built
 * once instead of every frame.
 */
private object SpectrogramCache {
    private var cachedRows = -1
    private var cachedColumns = -1
    private var positions = FloatArray(0)
    private var colors = IntArray(0)
    private var indices = ShortArray(0)

    fun positions(vertexCount: Int): FloatArray {
        if (positions.size != vertexCount * 2) positions = FloatArray(vertexCount * 2)
        return positions
    }

    fun colors(vertexCount: Int): IntArray {
        if (colors.size != vertexCount) colors = IntArray(vertexCount)
        return colors
    }

    fun indices(rows: Int, columns: Int): ShortArray {
        if (cachedRows == rows && cachedColumns == columns) return indices
        cachedRows = rows
        cachedColumns = columns
        val out = ShortArray((rows - 1) * (columns - 1) * 6)
        var cursor = 0
        for (column in columns - 1 downTo 1) {
            for (i in 0 until rows - 1) {
                val a = (i * columns + column).toShort()
                val b = ((i + 1) * columns + column).toShort()
                val c = ((i + 1) * columns + column - 1).toShort()
                val d = (i * columns + column - 1).toShort()
                out[cursor++] = a
                out[cursor++] = b
                out[cursor++] = c
                out[cursor++] = a
                out[cursor++] = c
                out[cursor++] = d
            }
        }
        indices = out
        return out
    }
}

private val GREY_STOPS = intArrayOf(
    0x1A222B,
    0x384453,
    0x4E5D6C,
    0x7C8B9A,
    0xAEBBC7,
    0xD8E1EA,
    0xF4F8FC,
)

/** Monochrome ramp with the mid-tones lifted, baked into a lookup table. */
private fun greyAt(level: Float): Int = GREY_LUT[(level.coerceIn(0f, 1f) * 255f).toInt()]

private val GREY_LUT = IntArray(256) { step ->
    val t = (step / 255f).pow(0.72f) * (GREY_STOPS.size - 1)
    val index = t.toInt().coerceIn(0, GREY_STOPS.size - 2)
    val frac = t - index
    val from = GREY_STOPS[index]
    val to = GREY_STOPS[index + 1]
    val r = lerpChannel((from shr 16) and 0xFF, (to shr 16) and 0xFF, frac)
    val g = lerpChannel((from shr 8) and 0xFF, (to shr 8) and 0xFF, frac)
    val b = lerpChannel(from and 0xFF, to and 0xFF, frac)
    (0xFF shl 24) or (r shl 16) or (g shl 8) or b
}

private fun lerpChannel(from: Int, to: Int, t: Float): Int = (from + (to - from) * t).toInt().coerceIn(0, 255)
