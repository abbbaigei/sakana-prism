package dev.sakana.spw.prism.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.sakana.spw.prism.preview.SpectrumController
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt

enum class SoundFieldMode(val label: String) {
    POLAR_SAMPLE("极坐标采样"),
    POLAR_LEVEL("极坐标电平"),
    LISSAJOUS("李萨如"),
    LEVELS("电平"),
    REFERENCE("单色"),
}

private class PolarHistory(private val frames: Int = 8, private val pairs: Int = 768) {
    private val data = Array(frames) { FloatArray(pairs * 2) }
    private var head = 0
    var count = 0
        private set

    fun clear() {
        count = 0
        head = 0
    }

    fun push(field: FloatArray) {
        val target = data[head]
        val n = min(field.size, target.size)
        field.copyInto(target, 0, 0, n)
        if (n < target.size) java.util.Arrays.fill(target, n, target.size, 0f)
        head = (head + 1) % frames
        count = min(count + 1, frames)
    }

    fun frame(age: Int): FloatArray? {
        if (age < 0 || age >= count) return null
        return data[(head - 1 - age + frames) % frames]
    }
}

@Composable
fun SoundFieldPanel(
    controller: SpectrumController,
    metrics: RenderMetrics,
    palette: ArtworkPalette,
    modifier: Modifier = Modifier,
    onSettings: () -> Unit = {},
    onExpand: () -> Unit = {},
) {
    var mode by remember { mutableStateOf(SoundFieldMode.POLAR_SAMPLE) }
    var density by remember { mutableStateOf(768) }
    var trail by remember { mutableStateOf(3) }
    var trailVersion by remember { mutableIntStateOf(0) }
    var renderTick by remember { mutableIntStateOf(0) }
    val history = remember { PolarHistory() }
    val polarLevelState = remember { FloatArray(96) }
    val latestField = controller.field
    val panel = Color(0xFF0D121A)
    val line = Color(0xFF1D2A38)
    val muted = Color(0xFF7D8A9A)
    val leftColor = palette.left
    val rightColor = palette.right

    LaunchedEffect(controller) {
        history.clear()
        trailVersion++
    }
    LaunchedEffect(mode, controller.targetFps) {
        if (mode == SoundFieldMode.POLAR_SAMPLE || mode == SoundFieldMode.POLAR_LEVEL) {
            val interval = if (mode == SoundFieldMode.POLAR_LEVEL) 16L
                else (1000f / controller.targetFps.coerceAtLeast(1)).toLong().coerceAtLeast(1L)
            while (isActive) {
                delay(interval)
                renderTick++
            }
        }
    }
    LaunchedEffect(mode) {
        if (mode == SoundFieldMode.POLAR_LEVEL) polarLevelState.fill(0f)
    }
    LaunchedEffect(latestField, mode) {
        if (mode == SoundFieldMode.POLAR_SAMPLE && latestField.isNotEmpty()) {
            history.push(latestField)
            trailVersion++
        } else if (mode != SoundFieldMode.POLAR_SAMPLE) {
            history.clear()
        }
    }

    Column(
        modifier.fillMaxSize().background(panel, RoundedCornerShape(20.dp))
            .border(1.dp, Color(0x1FEAF2F8), RoundedCornerShape(20.dp))
            .padding(start = 16.dp, end = 16.dp, top = 14.dp, bottom = 24.dp),
    ) {
        if (mode == SoundFieldMode.REFERENCE) {
            Box(Modifier.fillMaxWidth().height(30.dp)) {
                Text(
                    "Sound Field",
                    modifier = Modifier.align(Alignment.Center),
                    fontSize = 18.sp, color = Color(0xFFF0F5FB), letterSpacing = 0.8.sp,
                )
                Row(Modifier.align(Alignment.CenterEnd).padding(end = 64.dp)) {
                    Text("设置", modifier = Modifier.clickable(onClick = onSettings).padding(horizontal = 5.dp, vertical = 6.dp), fontSize = 9.sp, color = muted)
                    Text("扩展", modifier = Modifier.clickable(onClick = onExpand).padding(horizontal = 5.dp, vertical = 6.dp), fontSize = 9.sp, color = muted)
                }
            }
        } else {
            Row(Modifier.fillMaxWidth().padding(end = 64.dp), verticalAlignment = Alignment.Bottom) {
                Column(Modifier.weight(1f)) {
                    Text("声场", fontSize = 22.sp, color = Color(0xFFF0F5FB), letterSpacing = 1.sp)
                    Text("立体声场 / STEREO FIELD VISUALIZATION", fontSize = 8.sp, color = muted, letterSpacing = 1.2.sp)
                }
                Text(
                    "设置",
                    modifier = Modifier.clickable(onClick = onSettings).padding(horizontal = 5.dp, vertical = 6.dp),
                    fontSize = 9.sp, color = muted,
                )
                Text(
                    "扩展",
                    modifier = Modifier.clickable(onClick = onExpand).padding(horizontal = 5.dp, vertical = 6.dp),
                    fontSize = 9.sp, color = muted,
                )
            }
            Spacer(Modifier.height(5.dp))
            Text(
                "宽度 ${"%.0f".format(controller.stereoWidth * 100f)}%   ·   " +
                    "相关 ${"%+.2f".format(controller.correlation)}   ·   " +
                    "峰值 ${"%.1f".format(db(max(controller.leftPeak, controller.rightPeak)))} dBFS" +
                    gainSuffix(controller),
                fontSize = 10.sp, color = muted, fontFamily = FontFamily.Monospace,
            )
            Spacer(Modifier.height(7.dp))
        }

        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            SoundFieldMode.entries.filter { it != SoundFieldMode.REFERENCE }.forEach { item ->
                ModeChip(item.label, item == mode, leftColor) { mode = item }
            }
        }
        if (!controller.stereo) {
            Text(
                "单声道源 · L/R 复制",
                fontSize = 8.sp, color = Color(0xFFFFC46B),
                modifier = Modifier.padding(top = 3.dp),
            )
        }
        if (mode == SoundFieldMode.POLAR_SAMPLE) {
            Spacer(Modifier.height(4.dp))
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text("密度", fontSize = 12.sp, color = muted)
            Spacer(Modifier.width(5.dp))
            listOf(96, 192, 384, 768).forEach { value ->
                SmallChip(value.toString(), density == value, leftColor) { density = value }
                Spacer(Modifier.width(3.dp))
            }
            Spacer(Modifier.width(10.dp))
            Text("残影", fontSize = 12.sp, color = muted)
            Spacer(Modifier.width(3.dp))
            listOf(0, 3, 6).forEach { value ->
                    SmallChip(if (value == 0) "关" else "$value", trail == value, leftColor) { trail = value }
                    Spacer(Modifier.width(3.dp))
                }
            }
            Spacer(Modifier.height(4.dp))
        }

        Box(Modifier.fillMaxWidth().weight(1f)) {
            val version = trailVersion
            val tick = renderTick
            val textMeasurer = rememberTextMeasurer()
            Canvas(Modifier.fillMaxSize()) {
                val drawStarted = System.nanoTime()
                metrics.recordFrame(drawStarted)
                val w = size.width
                val h = size.height
                val cx = w * 0.50f
                val bottom = h * 0.84f
                val radius = min(w * 0.40f, h * 0.72f)

                val lissajousAxis = min(w * 0.31f, h * 0.44f)
                val lissajousCenterY = h * 0.48f
                val polarGrid = mode != SoundFieldMode.LISSAJOUS && mode != SoundFieldMode.LEVELS
                if (polarGrid) {
                    drawLine(line.copy(alpha = .42f), Offset(cx - radius, bottom), Offset(cx + radius, bottom), 0.8.dp.toPx())
                    for (ring in 1..4) {
                        val r = radius * ring / 4f
                        drawArc(
                            color = line.copy(alpha = if (ring == 4) 0.9f else 0.52f),
                            startAngle = 180f, sweepAngle = 180f, useCenter = false,
                            topLeft = Offset(cx - r, bottom - r), size = Size(r * 2f, r * 2f),
                            style = Stroke(if (ring == 4) 1.15.dp.toPx() else 0.75.dp.toPx()),
                        )
                        // Radius FS values belong to Polar Sample only.
                        if (mode == SoundFieldMode.POLAR_SAMPLE) {
                            val sideX = r * 0.866f
                            val sideY = r * 0.5f
                            val sideLabel = "%.2f".format(ring * 0.25f)
                            val sideStyle = TextStyle(
                                fontSize = 8.sp,
                                color = muted.copy(alpha = .78f),
                                fontFamily = FontFamily.Monospace,
                            )
                            drawText(textMeasurer, sideLabel,
                                Offset(cx - sideX - 22.dp.toPx(), bottom - sideY - 6.dp.toPx()), sideStyle)
                            drawText(textMeasurer, sideLabel,
                                Offset(cx + sideX + 4.dp.toPx(), bottom - sideY - 6.dp.toPx()), sideStyle)
                        }
                    }
                    val labelStyle = TextStyle(fontSize = 11.sp, color = muted, fontFamily = FontFamily.Monospace)
                    for (deg in listOf(-90, -60, -30, 0, 30, 60, 90)) {
                        val theta = (90.0 - deg) * PI / 180.0
                        val ux = cos(theta).toFloat()
                        val uy = sin(theta).toFloat()
                        drawLine(
                            line.copy(alpha = if (deg == 0) 0.55f else 0.32f),
                            Offset(cx, bottom),
                            Offset(cx + ux * radius, bottom - uy * radius),
                            0.7.dp.toPx(),
                        )
                        val lx = cx + ux * radius * 1.075f
                        val ly = bottom - uy * radius * 1.075f
                        if (mode != SoundFieldMode.REFERENCE) {
                            drawText(
                                textMeasurer = textMeasurer,
                                text = "$deg°",
                                topLeft = Offset(lx - 10.dp.toPx(), ly - 9.dp.toPx()),
                                style = labelStyle,
                            )
                        }
                    }
                    if (mode == SoundFieldMode.REFERENCE) {
                        drawCircle(Color(0xFF58D6B0).copy(alpha = 0.80f), 2.5.dp.toPx(), Offset(cx, bottom))
                    } else {
                        drawCircle(leftColor.copy(alpha = 0.09f * controller.fieldOpacity), 12.dp.toPx(), Offset(cx, bottom))
                        drawCircle(leftColor.copy(alpha = 0.20f * controller.fieldOpacity), 6.dp.toPx(), Offset(cx, bottom))
                        drawCircle(Color(0xFFE8FFF9), 2.2.dp.toPx(), Offset(cx, bottom))
                    }
                } else if (mode == SoundFieldMode.LISSAJOUS) {
                    // Larger, borderless equal-gain XY frame with ticks.
                    val left = cx - lissajousAxis
                    val top = lissajousCenterY - lissajousAxis
                    for (k in listOf(-0.5f, 0f, 0.5f)) {
                        val offset = lissajousAxis * k
                        drawLine(line.copy(alpha = if (k == 0f) .55f else .20f),
                            Offset(left, lissajousCenterY + offset),
                            Offset(left + lissajousAxis * 2f, lissajousCenterY + offset), 0.8.dp.toPx())
                        drawLine(line.copy(alpha = if (k == 0f) .55f else .20f),
                            Offset(cx + offset, top),
                            Offset(cx + offset, top + lissajousAxis * 2f), 0.8.dp.toPx())
                    }
                    val tickStyle = TextStyle(fontSize = 9.sp, color = muted, fontFamily = FontFamily.Monospace)
                    val unitStyle = TextStyle(fontSize = 9.sp, color = leftColor, fontFamily = FontFamily.Monospace)
                    for (k in listOf(-1f, -0.5f, 0f, 0.5f, 1f)) {
                        val label = when (k) {
                            -1f -> "-1"; -0.5f -> "-.5"; 0f -> "0"; 0.5f -> "+.5"; else -> "+1"
                        }
                        val x = cx + lissajousAxis * k
                        drawText(textMeasurer, label,
                            Offset(x - 8.dp.toPx(), lissajousCenterY + lissajousAxis + 2.dp.toPx()), tickStyle)
                        val y = lissajousCenterY - lissajousAxis * k
                        drawText(textMeasurer, label,
                            Offset(left - 25.dp.toPx(), y - 6.dp.toPx()), tickStyle)
                    }
                    drawText(textMeasurer, "L (FS)",
                        Offset(cx + lissajousAxis - 30.dp.toPx(), lissajousCenterY + lissajousAxis - 14.dp.toPx()), unitStyle)
                    drawText(textMeasurer, "R (FS)",
                        Offset(left + 4.dp.toPx(), top + 2.dp.toPx()), unitStyle)
                } else {
                    // Levels: horizontal dBFS guides, no polar rings.
                    val left = cx - radius * 0.48f
                    val right = cx + radius * 0.48f
                    for (fraction in listOf(0f, .25f, .5f, 1f)) {
                        val y = bottom - radius * (1f - fraction)
                        drawLine(line.copy(alpha = if (fraction == 0f) .65f else .22f),
                            Offset(left, y), Offset(right, y), 0.8.dp.toPx())
                    }
                }

                val field = controller.field
                when (mode) {
                    SoundFieldMode.REFERENCE -> {
                        drawReferenceCloud(field, cx, bottom, radius, controller.fieldOpacity)
                    }
                    SoundFieldMode.POLAR_SAMPLE -> {
                        if (version >= 0 && tick >= 0) {
                            val maxAge = min(trail, history.count - 1).coerceAtLeast(0)
                            for (age in maxAge downTo 0) {
                                val snapshot = history.frame(age) ?: continue
                                val fade = if (age == 0) 1f else (1f - age / (trail + 1f)) * 0.24f
                                drawPolarCloud(
                                    snapshot, cx, bottom, radius, density,
                                    controller.fieldOpacity * fade, leftColor, rightColor,
                                    recordStats = age == 0,
                                )
                            }
                        }
                    }
                    SoundFieldMode.POLAR_LEVEL -> {
                        drawPolarLevel(field, polarLevelState, cx, bottom, radius, leftColor, controller.fieldOpacity)
                    }
                    SoundFieldMode.LISSAJOUS -> {
                        // Same gain on both axes: a mono L=R signal must be 45deg.
                        val axis = lissajousAxis
                        val centerY = lissajousCenterY
                        val liss = Path()
                        var first = true
                        var i = 0
                        while (i + 1 < field.size) {
                            val l = field[i].coerceIn(-1f, 1f)
                            val r = field[i + 1].coerceIn(-1f, 1f)
                            val x = cx + l * axis
                            val y = centerY - r * axis
                            if (first) { liss.moveTo(x, y); first = false } else liss.lineTo(x, y)
                            drawCircle(leftColor.copy(alpha = .28f * controller.fieldOpacity), 0.8.dp.toPx() * soundFieldParticleScale(radius), Offset(x, y))
                            i += 2
                        }
                        drawPath(liss, leftColor.copy(alpha = .55f * controller.fieldOpacity), style = Stroke(1f))
                    }
                    SoundFieldMode.LEVELS -> {
                        val xL = cx - radius * 0.34f
                        val xR = cx + radius * 0.34f
                        val barTop = bottom - radius
                        val barW = 22.dp.toPx()
                        drawLevelBar(xL - barW / 2f, barTop, bottom, barW,
                            dbNorm(db(controller.leftPeak)), dbNorm(db(controller.leftHold)), dbNorm(db(controller.leftRms)), leftColor,
                            Color(0xFF22303E))
                        drawLevelBar(xR - barW / 2f, barTop, bottom, barW,
                            dbNorm(db(controller.rightPeak)), dbNorm(db(controller.rightHold)), dbNorm(db(controller.rightRms)), rightColor,
                            Color(0xFF22303E))
                    }
                }

                // Correlation scale for sample / Lissajous / Polar Level;
                // centered dBFS scale for Levels.
                val dbScale = mode == SoundFieldMode.LEVELS
                val scaleX = if (dbScale) cx else w - 9.dp.toPx()
                val scaleTop = bottom - radius
                val scaleBottom = bottom
                drawLine(line.copy(alpha = .55f), Offset(scaleX, scaleTop), Offset(scaleX, scaleBottom), 0.8.dp.toPx())
                val scaleStyle = TextStyle(fontSize = 11.sp, color = muted, fontFamily = FontFamily.Monospace)
                val scaleLabels = if (dbScale) listOf("0", "-24", "-48", "-96") else listOf("+1", "0", "-1")
                scaleLabels.forEachIndexed { index, label ->
                    val y = scaleTop + (scaleBottom - scaleTop) * index / (scaleLabels.size - 1).coerceAtLeast(1)
                    drawLine(line.copy(alpha = .7f), Offset(scaleX - 3.dp.toPx(), y), Offset(scaleX + 1.dp.toPx(), y), 0.8.dp.toPx())
                    drawText(
                        textMeasurer = textMeasurer,
                        text = label,
                        topLeft = Offset(
                            if (dbScale) scaleX + 7.dp.toPx() else scaleX - 25.dp.toPx(),
                            y - 6.dp.toPx(),
                        ),
                        style = scaleStyle,
                    )
                }
                if (!dbScale) {
                    val corr = controller.correlation.coerceIn(-1f, 1f)
                    val markerY = scaleTop + (1f - corr) * 0.5f * (scaleBottom - scaleTop)
                    drawCircle(Color(0xFF74EFC2), 2.8.dp.toPx(), Offset(scaleX, markerY))
                    drawLine(Color(0xFF74EFC2).copy(alpha = .75f), Offset(scaleX - 5.dp.toPx(), markerY), Offset(scaleX + 3.dp.toPx(), markerY), 1.dp.toPx())
                }
                metrics.recordDraw(System.nanoTime() - drawStarted)
            }

            val lissStats = mode == SoundFieldMode.LISSAJOUS
            val levelStats = mode == SoundFieldMode.POLAR_LEVEL
            val modeStats = lissStats || levelStats
            val leftBottom = when {
                mode == SoundFieldMode.REFERENCE -> "L"
                lissStats -> "L PK ${"%.2f".format(controller.leftPeak)} FS"
                levelStats -> "L PK ${"%.1f".format(db(controller.leftPeak))} dBFS"
                else -> "L / LEFT"
            }
            val rightBottom = when {
                mode == SoundFieldMode.REFERENCE -> "R"
                lissStats -> "R PK ${"%.2f".format(controller.rightPeak)} FS"
                levelStats -> "R PK ${"%.1f".format(db(controller.rightPeak))} dBFS"
                else -> "R / RIGHT"
            }
            val centerBottom = when {
                lissStats -> "Δ PK ${"%+.2f".format(controller.leftPeak - controller.rightPeak)} FS"
                levelStats -> "Δ PK ${"%+.1f".format(db(controller.leftPeak) - db(controller.rightPeak))} dB"
                mode == SoundFieldMode.LEVELS -> "dBFS"
                mode == SoundFieldMode.REFERENCE -> ""
                else -> "0"
            }
            if (modeStats) {
                // Three equal cells keep the right-channel value inside the
                // panel instead of letting BottomEnd text get clipped.
                Row(
                    Modifier.align(Alignment.BottomCenter).padding(bottom = 10.dp),
                    horizontalArrangement = Arrangement.spacedBy(16.dp),
                    verticalAlignment = Alignment.Bottom,
                ) {
                    Text(leftBottom, fontSize = 9.sp,
                        color = leftColor, fontFamily = FontFamily.Monospace, maxLines = 1)
                    Text(centerBottom, fontSize = 9.sp,
                        color = muted, fontFamily = FontFamily.Monospace, maxLines = 1)
                    Text(rightBottom, fontSize = 9.sp,
                        color = rightColor, fontFamily = FontFamily.Monospace, maxLines = 1)
                }
            } else {
                Text(
                    leftBottom,
                    modifier = Modifier.align(Alignment.BottomStart).padding(start = 4.dp, bottom = 14.dp),
                    fontSize = if (mode == SoundFieldMode.REFERENCE) 15.sp else 12.sp,
                    color = if (mode == SoundFieldMode.REFERENCE) muted else leftColor,
                    fontFamily = FontFamily.Monospace,
                )
                Text(
                    rightBottom,
                    modifier = Modifier.align(Alignment.BottomEnd).padding(end = 4.dp, bottom = 14.dp),
                    fontSize = if (mode == SoundFieldMode.REFERENCE) 15.sp else 12.sp,
                    color = if (mode == SoundFieldMode.REFERENCE) muted else rightColor,
                    fontFamily = FontFamily.Monospace,
                )
                if (mode != SoundFieldMode.REFERENCE) {
                    Text(
                        centerBottom,
                        modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 14.dp),
                        fontSize = 12.sp,
                        color = muted, fontFamily = FontFamily.Monospace,
                    )
                }
            }
        }

        if (mode != SoundFieldMode.REFERENCE) {
        Spacer(Modifier.height(5.dp))
        val fsUnit = mode == SoundFieldMode.LISSAJOUS || mode == SoundFieldMode.POLAR_SAMPLE
        val legendUnit = if (fsUnit) "FS" else "dBFS"
        val leftValue = if (fsUnit) "%.2f".format(controller.leftPeak) else "%.1f".format(db(controller.leftRms))
        val rightValue = if (fsUnit) "%.2f".format(controller.rightPeak) else "%.1f".format(db(controller.rightRms))
        val hint = when (mode) {
            SoundFieldMode.POLAR_SAMPLE -> "角度° · 半径 FS"
            SoundFieldMode.POLAR_LEVEL -> "RMS dBFS"
            SoundFieldMode.LISSAJOUS -> "X=L / Y=R · FS"
            SoundFieldMode.LEVELS -> "峰值 / 保持 / RMS"
            SoundFieldMode.REFERENCE -> ""
        }
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            LegendDot(leftColor)
            Text("L $leftValue $legendUnit", fontSize = 8.sp, color = muted, fontFamily = FontFamily.Monospace)
            Spacer(Modifier.width(10.dp))
            LegendDot(rightColor)
            Text("R $rightValue $legendUnit", fontSize = 8.sp, color = muted, fontFamily = FontFamily.Monospace)
            Spacer(Modifier.weight(1f))
            Text(hint, fontSize = 8.sp, color = muted, fontFamily = FontFamily.Monospace)
        }
        }
    }
}

private fun androidx.compose.ui.graphics.drawscope.DrawScope.drawPolarCloud(
    field: FloatArray,
    cx: Float,
    bottom: Float,
    radius: Float,
    density: Int,
    opacity: Float,
    leftColor: Color,
    rightColor: Color,
    recordStats: Boolean = false,
) {
    if (opacity <= 0.01f) return
    val pairs = min(768, field.size / 2)
    val particleScale = soundFieldParticleScale(radius)
    if (recordStats) SoundFieldDebugStats.record(field, radius, opacity, density)
    if (pairs <= 1) return
    val count = density.coerceIn(1, 768)
    for (i in 0 until count) {
        val pair = if (count == 1) 0 else
            (i.toDouble() * (pairs - 1) / (count - 1).toDouble()).roundToInt().coerceIn(0, pairs - 1)
        val l = field[pair * 2].coerceIn(-1f, 1f)
        val r = field[pair * 2 + 1].coerceIn(-1f, 1f)
        val mid = (l + r) * 0.5f
        val side = (l - r) * 0.5f
        val energy = min(1f, abs(mid) + abs(side))
        // Normalize stereo position by level. Using side directly makes the
        // angular span shrink with radius and creates a fixed funnel near the
        // centre (|side| <= energy). Radius and angle must be independent.
        val pan = if (energy > 1e-6f) (side / energy).coerceIn(-1f, 1f) else 0f
        val angle = (PI / 2.0 - pan * PI * 0.48).toFloat()
        val distance = radius * (0.025f + 0.975f * energy)
        val aL = ((0.018f + abs(l) * 0.58f) * opacity).coerceIn(0f, 0.82f)
        val aR = ((0.018f + abs(r) * 0.58f) * opacity).coerceIn(0f, 0.82f)
        if (aL > 0.008f) {
            val x = cx + cos(angle + 0.018f) * distance
            val y = bottom - sin(angle + 0.018f) * distance
            drawCircle(leftColor.copy(alpha = aL), (0.65f + abs(l) * 2.0f) * particleScale * 1.dp.toPx(), Offset(x, y))
        }
        if (aR > 0.008f) {
            val x = cx + cos(angle - 0.018f) * distance
            val y = bottom - sin(angle - 0.018f) * distance
            drawCircle(rightColor.copy(alpha = aR), (0.65f + abs(r) * 2.0f) * particleScale * 1.dp.toPx(), Offset(x, y))
        }
    }
}

/**
 * Polar Level: fill a continuous stereo envelope. Each angular bin keeps the
 * peak sample energy at that pan position; the outline is the 96-bin envelope,
 * not two fixed lobes.
 */
private fun androidx.compose.ui.graphics.drawscope.DrawScope.drawPolarLevel(
    field: FloatArray,
    state: FloatArray,
    cx: Float,
    bottom: Float,
    radius: Float,
    color: Color,
    opacity: Float,
) {
    if (opacity <= 0.01f || field.size < 4) return
    val binCount = 96
    val levels = FloatArray(binCount)
    val counts = IntArray(binCount)
    var i = 0
    while (i + 1 < field.size) {
        val l = field[i].coerceIn(-1f, 1f)
        val r = field[i + 1].coerceIn(-1f, 1f)
        val mid = (l + r) * 0.5f
        val side = (l - r) * 0.5f
        val energy = min(1f, abs(mid) + abs(side))
        val pan = if (energy > 1e-6f) (side / energy).coerceIn(-1f, 1f) else 0f
        val bin = ((pan + 1f) * 0.5f * (binCount - 1)).roundToInt().coerceIn(0, binCount - 1)
        if (energy > levels[bin]) levels[bin] = energy
        counts[bin]++
        i += 2
    }
    // Keep the fill continuous across empty angular bins.
    var last = 0f
    for (b in 0 until binCount) {
        if (counts[b] == 0) levels[b] = last else last = levels[b]
    }
    val smooth = FloatArray(binCount)
    for (b in 0 until binCount) {
        val prev = levels[(b - 1).coerceAtLeast(0)]
        val next = levels[(b + 1).coerceAtMost(binCount - 1)]
        smooth[b] = (prev + levels[b] * 2f + next) * 0.25f
    }

    // Fill: instantaneous (lightly smoothed) peak envelope. The filled shape
    // must follow the current audio, not the release state.
    val fill = Path()
    fill.moveTo(cx, bottom)
    for (b in 0 until binCount) {
        val pan = b / (binCount - 1f) * 2f - 1f
        val angle = (PI / 2.0 - pan * PI * 0.48).toFloat()
        val distance = radius * (0.15f + 0.85f * smooth[b])
        fill.lineTo(cx + cos(angle) * distance, bottom - sin(angle) * distance)
    }
    fill.close()
    drawPath(fill, color.copy(alpha = .72f * opacity))

    // Line: peak-hold with a slow release. Only this line decays; it can
    // remain above the fill after a transient, as in the reference.
    for (b in state.indices) {
        val target = smooth[b]
        state[b] = if (target >= state[b]) target else max(target, state[b] * 0.995f)
    }
    val line = Path()
    for (b in 0 until binCount) {
        val pan = b / (binCount - 1f) * 2f - 1f
        val angle = (PI / 2.0 - pan * PI * 0.48).toFloat()
        val distance = radius * (0.15f + 0.85f * state[b])
        val x = cx + cos(angle) * distance
        val y = bottom - sin(angle) * distance
        if (b == 0) line.moveTo(x, y) else line.lineTo(x, y)
    }
    drawPath(line, color.copy(alpha = .92f * opacity), style = Stroke(1.35.dp.toPx()))
}

private fun androidx.compose.ui.graphics.drawscope.DrawScope.drawReferenceCloud(
    field: FloatArray,
    cx: Float,
    bottom: Float,
    radius: Float,
    opacity: Float,
) {
    if (opacity <= 0.01f) return
    val pairs = min(768, field.size / 2)
    val particleScale = soundFieldParticleScale(radius)
    for (pair in 0 until pairs) {
        val l = field[pair * 2].coerceIn(-1f, 1f)
        val r = field[pair * 2 + 1].coerceIn(-1f, 1f)
        val mid = (l + r) * 0.5f
        val side = (l - r) * 0.5f
        val energy = min(1f, abs(mid) + abs(side))
        if (energy < 0.002f) continue
        val pan = if (energy > 1e-6f) (side / energy).coerceIn(-1f, 1f) else 0f
        val angle = (PI / 2.0 - pan * PI * 0.48).toFloat()
        val distance = radius * (0.025f + 0.975f * energy)
        val alpha = ((0.018f + energy * 0.74f) * opacity).coerceIn(0f, 0.9f)
        val x = cx + cos(angle) * distance
        val y = bottom - sin(angle) * distance
        drawCircle(
            Color(0xFFE6EEF4).copy(alpha = alpha),
            (0.45f + energy * 1.25f) * particleScale * 1.dp.toPx(),
            Offset(x, y),
        )
    }
}

private fun androidx.compose.ui.graphics.drawscope.DrawScope.drawLobe(
    cx: Float, bottom: Float, radius: Float, side: Float, level: Float,
    color: Color, line: Color, opacity: Float,
) {
    val angle = (PI / 2.0 + side * 0.55).toFloat()
    val distance = radius * (0.15f + 0.85f * level.coerceIn(0f, 1f))
    val path = Path()
    path.moveTo(cx, bottom)
    path.lineTo(cx + cos(angle - 0.32f) * distance, bottom - sin(angle - 0.32f) * distance)
    path.lineTo(cx + cos(angle + 0.32f) * distance, bottom - sin(angle + 0.32f) * distance)
    path.close()
    drawPath(path, color.copy(alpha = .22f * opacity))
    drawLine(line.copy(alpha = .5f * opacity), Offset(cx, bottom),
        Offset(cx + cos(angle) * radius, bottom - sin(angle) * radius), 0.8.dp.toPx())
    drawCircle(color.copy(alpha = .75f * opacity), 3.dp.toPx(),
        Offset(cx + cos(angle) * distance, bottom - sin(angle) * distance))
}

private fun androidx.compose.ui.graphics.drawscope.DrawScope.drawLevelBar(
    x: Float, top: Float, bottom: Float, width: Float,
    value: Float, hold: Float, rms: Float, color: Color, track: Color,
) {
    drawRoundRect(track, Offset(x, top), Size(width, bottom - top), CornerRadius(4.dp.toPx()))
    val fillTop = bottom - (bottom - top) * value.coerceIn(0f, 1f)
    drawRoundRect(color, Offset(x, fillTop), Size(width, (bottom - fillTop).coerceAtLeast(1f)), CornerRadius(4.dp.toPx()))
    if (hold > 0.001f) {
        val y = bottom - (bottom - top) * hold.coerceIn(0f, 1f)
        drawLine(Color.White.copy(alpha = .8f), Offset(x, y), Offset(x + width, y), 1.2.dp.toPx())
    }
    val rmsY = bottom - (bottom - top) * rms.coerceIn(0f, 1f)
    drawLine(Color.White.copy(alpha = .42f), Offset(x, rmsY), Offset(x + width, rmsY), 0.8.dp.toPx())
}

@Composable
private fun LegendDot(color: Color) {
    Box(Modifier.padding(end = 4.dp).size(6.dp).background(color, RoundedCornerShape(50)))
}

@Composable
private fun SmallChip(label: String, selected: Boolean, accent: Color, onClick: () -> Unit) {
    Box(
        Modifier.border(1.dp, if (selected) accent else Color(0xFF1D2835), RoundedCornerShape(6.dp))
            .background(if (selected) accent.copy(alpha = .12f) else Color.Transparent, RoundedCornerShape(6.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 6.dp, vertical = 4.dp),
    ) {
        Text(label, fontSize = 11.sp, color = if (selected) accent else Color(0xFF7D8A9A))
    }
}

@Composable
private fun ModeChip(label: String, selected: Boolean, accent: Color, onClick: () -> Unit) {
    Box(
        Modifier.border(1.dp, if (selected) accent else Color(0xFF1D2835), RoundedCornerShape(7.dp))
            .background(if (selected) accent.copy(alpha = .12f) else Color.Transparent, RoundedCornerShape(7.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 7.dp),
    ) {
        Text(label, fontSize = 10.sp, color = if (selected) accent else Color(0xFF7D8A9A))
    }
}

/**
 * Particle diameter grows sub-linearly with the canvas. A maximised window
 * gets roughly 1.5x particles instead of the old 2.8x, while the normal
 * canvas keeps its 1.0x reference size.
 */
private fun soundFieldParticleScale(radius: Float): Float =
    (radius / 300f).coerceAtLeast(0.01f).pow(0.62f).coerceIn(0.7f, 2.0f)

private fun db(amplitude: Float): Float =
    if (amplitude <= 1e-6f) -96f else (20.0 * log10(amplitude.toDouble())).toFloat().coerceIn(-96f, 0f)

private fun dbNorm(db: Float): Float = ((db + 96f) / 96f).coerceIn(0f, 1f)

/**
 * Shows the loudness metadata baked into the file (ReplayGain / R128), so the meter can be
 * read against what the track was mastered to instead of only its current level.
 */
private fun gainSuffix(controller: SpectrumController): String {
    val tags = controller.loudness ?: return "   ·   ReplayGain 无"
    val gain = tags.gainDb
    val parts = buildList {
        if (gain != null) add("增益 ${"%+.1f".format(gain)} dB")
        tags.trackPeak?.let { add("真峰值 ${"%.2f".format(it)}") }
        if (gain == null) tags.albumGainDb?.let { add("专辑增益 ${"%+.1f".format(it)} dB") }
    }
    return if (parts.isEmpty()) "   ·   ReplayGain 无" else "   ·   " + parts.joinToString(" · ")
}