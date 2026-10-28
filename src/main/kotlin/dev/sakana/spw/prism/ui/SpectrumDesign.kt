package dev.sakana.spw.prism.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.sakana.spw.prism.audio.AnalysisScope
import dev.sakana.spw.prism.diagnostics.BuildInfo
import dev.sakana.spw.prism.i18n.AppLanguage
import dev.sakana.spw.prism.i18n.Strings
import dev.sakana.spw.prism.diagnostics.DiagnosticsLog
import dev.sakana.spw.prism.audio.SpectrumLayout
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import dev.sakana.spw.prism.preview.FrameMode
import dev.sakana.spw.prism.preview.SpectrumController
import dev.sakana.spw.prism.settings.ColorMode
import dev.sakana.spw.prism.settings.GraphMode
import dev.sakana.spw.prism.settings.SpectrumSettings
import dev.sakana.spw.prism.settings.ThemePreset
import dev.sakana.spw.prism.settings.SpectrogramGlow
import dev.sakana.spw.prism.settings.SpectrogramView
import kotlin.math.max
import kotlin.math.pow

private val Ink = Color(0xFF070A0F)
private val Panel = Color(0xFF0D121A)
private val Line = Color(0xFF1D2835)
private val Muted = Color(0xFF7D8A9A)
private val Teal = Color(0xFF4DD8C4)
private val Blue = Color(0xFF5B8CFF)
private val Violet = Color(0xFFA77BFF)
private val Amber = Color(0xFFFFC46B)

private val Dark = darkColorScheme(
    background = Ink,
    surface = Panel,
    onBackground = Color(0xFFE8EEF5),
    onSurface = Color(0xFFE8EEF5),
    onSurfaceVariant = Muted,
    primary = Teal,
)

private enum class ViewMode(val label: String) {
    SPECTRUM("频谱"),
    FIELD("声场"),
}

@Composable
fun SpectrumDesign(controller: SpectrumController) {
    DisposableEffect(controller) {
        controller.start()
        onDispose { controller.stop() }
    }
    LaunchedEffect(controller, SpectrumSettings.frameMode) {
        controller.applyMode(SpectrumSettings.frameMode)
    }
    // LocalWindowInfo reports false inside a host hook composition, which would pin the
    // whole plugin to the background frame cap. Ask AWT instead: is any of this
    // application's windows active right now?
    LaunchedEffect(controller) {
        while (true) {
            val focused = java.awt.Window.getWindows().any { it.isActive && it.isVisible }
            controller.setUnfocused(!focused)
            controller.setThrottleEnabled(SpectrumSettings.lowFpsOnUnfocus)
            kotlinx.coroutines.delay(300)
        }
    }
    val baseDensity = LocalDensity.current
    CompositionLocalProvider(
        LocalDensity provides Density(baseDensity.density, baseDensity.fontScale * 1.2f)
    ) {
        MaterialTheme(colorScheme = Dark) {
        var viewMode by remember { mutableStateOf(ViewMode.SPECTRUM) }
        var showSettings by remember { mutableStateOf(true) }
        var fieldExpanded by remember { mutableStateOf(false) }
        var lastLog by remember { mutableStateOf<String?>(null) }
        // Keep the invisible view from paying for analysis it will never draw.
        LaunchedEffect(viewMode, fieldExpanded) {
            AnalysisScope.fieldVisible = viewMode == ViewMode.FIELD || fieldExpanded
            AnalysisScope.spectrumVisible = viewMode == ViewMode.SPECTRUM
        }
        val renderMetrics = remember { RenderMetrics() }
        val graphMode = SpectrumSettings.graphMode
        val theme = SpectrumSettings.theme
        val colorMode = SpectrumSettings.colorMode
        var coverPalette by remember(controller.trackPath) { mutableStateOf<ArtworkPalette?>(null) }
        LaunchedEffect(controller.trackPath, colorMode) {
            coverPalette = if (colorMode == ColorMode.COVER) {
                withContext(Dispatchers.IO) { ArtworkPaletteExtractor.load(controller.trackPath) }
            } else {
                null
            }
        }
        val themePalette = remember(theme) { ArtworkPalette.fallback(theme) }
        val customPalette = remember(
            SpectrumSettings.customSpectrumA, SpectrumSettings.customSpectrumB,
            SpectrumSettings.customLeft, SpectrumSettings.customRight, theme,
        ) {
            val a = ArtworkPalette.parseHex(SpectrumSettings.customSpectrumA) ?: theme.accent
            val b = ArtworkPalette.parseHex(SpectrumSettings.customSpectrumB) ?: theme.secondary
            val left = ArtworkPalette.parseHex(SpectrumSettings.customLeft) ?: a
            val right = ArtworkPalette.parseHex(SpectrumSettings.customRight) ?: b
            ArtworkPalette.fromCustom(a, b, left, right)
        }
        val artworkPalette = when (colorMode) {
            ColorMode.COVER -> coverPalette ?: themePalette
            ColorMode.CUSTOM -> customPalette
            ColorMode.THEME -> themePalette
        }
        val barCount = if (SpectrumSettings.lowLoad) minOf(SpectrumSettings.barCount, 96) else SpectrumSettings.barCount
        val showPeaks = SpectrumSettings.showPeaks && !SpectrumSettings.lowLoad
        val animations = SpectrumSettings.animations
        if (viewMode == ViewMode.FIELD && fieldExpanded) {
            SoundFieldPanel(
                controller = controller,
                metrics = renderMetrics,
                palette = artworkPalette,
                modifier = Modifier.fillMaxSize().padding(12.dp),
                onSettings = { fieldExpanded = false },
                onExpand = { fieldExpanded = false },
            )
        } else {
            Row(Modifier.fillMaxSize().background(Panel)) {
                Column(Modifier.weight(1f).fillMaxHeight().padding(22.dp)) {
                    Header(controller, theme, viewMode) { viewMode = it }
                    Spacer(Modifier.height(14.dp))
                    if (viewMode == ViewMode.SPECTRUM) {
                        SpectrumPanel(controller, graphMode, theme, artworkPalette, barCount, showPeaks, animations, renderMetrics, Modifier.weight(1f))
                        Spacer(Modifier.height(16.dp))
                        Footer(controller, theme, renderMetrics)
                    } else {
                        SoundFieldPanel(
                            controller = controller,
                            metrics = renderMetrics,
                            palette = artworkPalette,
                            modifier = Modifier.weight(1f),
                            onSettings = { showSettings = !showSettings },
                            onExpand = { fieldExpanded = true },
                        )
                    }
                }
                if (showSettings) {
                    SettingsRail(
                        controller = controller,
                        mode = graphMode,
                        theme = theme,
                        showGraphModes = viewMode == ViewMode.SPECTRUM,
                        onMode = { SpectrumSettings.chooseGraphMode(it) },
                        onGenerateLog = {
                            lastLog = DiagnosticsLog.write(controller, renderMetrics)?.toString()
                        },
                        lastLogPath = lastLog,
                    )
                }
            }
        }
    }
    }
}

@Composable
private fun Header(
    controller: SpectrumController,
    theme: ThemePreset,
    viewMode: ViewMode,
    onViewMode: (ViewMode) -> Unit,
) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Bottom) {
        Column {
            Text(if (viewMode == ViewMode.FIELD) "声场" else "频谱",
                fontSize = 30.sp, fontWeight = FontWeight.Bold,
                letterSpacing = 2.sp, color = Color(0xFFF0F5FB))
            Text(if (viewMode == ViewMode.FIELD) Strings["sub.field"] else Strings["sub.spectrum"],
                fontSize = 9.sp, letterSpacing = 2.2.sp, color = Muted)
        }
        Spacer(Modifier.weight(1f))
        Row(horizontalArrangement = Arrangement.spacedBy(5.dp)) {
            ViewMode.entries.forEach { item ->
                Chip(if (item.name == "FIELD") Strings["tab.field"] else Strings["tab.spectrum"], item == viewMode, theme.accent) { onViewMode(item) }
            }
        }
        Spacer(Modifier.width(8.dp))
        StatusDot(controller, theme)
    }
}

@Composable
private fun StatusDot(controller: SpectrumController, theme: ThemePreset) {
    val error = controller.error
    val color = if (error == null) theme.accent else Amber
    Row(verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.background(Panel, RoundedCornerShape(50)).padding(horizontal = 13.dp, vertical = 8.dp)) {
        Box(Modifier.size(6.dp).background(color, RoundedCornerShape(50)))
        Spacer(Modifier.width(7.dp))
        Text(error ?: Strings["live"], fontSize = 10.sp, color = if (error == null) Color(0xFFBFE9E2) else Amber)
    }
}

@Composable
private fun SpectrumPanel(
    controller: SpectrumController,
    mode: GraphMode,
    theme: ThemePreset,
    palette: ArtworkPalette,
    barCount: Int,
    showPeaks: Boolean,
    animations: Boolean,
    metrics: RenderMetrics,
    modifier: Modifier,
) {
    val bars = SpectrumLayout.aggregateBars(controller.bands, barCount)
    val textMeasurer = rememberTextMeasurer()
    // The waterfall needs a steady time base: sample at ~30 Hz instead of once per
    // recomposition, otherwise the slab depth would depend on the refresh rate.
    val history = remember(barCount) { ArrayDeque<FloatArray>() }
    val lastSampleAt = remember { longArrayOf(0L) }
    if (mode == GraphMode.WATERFALL) {
        val sampledAt = System.nanoTime()
        if (sampledAt - lastSampleAt[0] >= 33_000_000L) {
            lastSampleAt[0] = sampledAt
            if (history.size >= SPECTROGRAM_ROWS) history.removeLast()
            history.addFirst(bars)
        }
    }
    val brush = remember(palette) { Brush.horizontalGradient(palette.spectrum) }
    val linePath = remember { Path() }
    val fillPath = remember { Path() }
    val peakPath = remember { Path() }
    val lastPeakAt = remember { longArrayOf(0L) }
    val peaks = remember(barCount) { FloatArray(barCount) }
    // Peak tracking only exists for the two modes that draw peak markers, and it falls on
    // wall-clock time so the release speed does not change with the refresh rate.
    if (mode == GraphMode.BARS || mode == GraphMode.LINE) {
        val nowNs = System.nanoTime()
        val lastNs = lastPeakAt[0]
        lastPeakAt[0] = nowNs
        val dt = if (lastNs == 0L) 0f else ((nowNs - lastNs) / 1_000_000_000f).coerceIn(0f, 0.25f)
        val fall = 0.42f * dt
        for (i in peaks.indices) {
            peaks[i] = if (animations) max(bars.getOrElse(i) { 0f }, peaks[i] - fall)
            else bars.getOrElse(i) { 0f }
        }
    }
    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(20.dp),
        color = Panel,
        border = BorderStroke(1.dp, Color(0x1FEAF2F8)),
    ) {
        Column(Modifier.fillMaxSize().padding(18.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(Strings["response"], fontSize = 9.sp, letterSpacing = 1.8.sp, color = Muted)
                Spacer(Modifier.weight(1f))
                Text("16384 点 FFT · $barCount 频段 · dBFS", fontSize = 9.sp, color = Muted)
            }
            Spacer(Modifier.height(12.dp))
            Canvas(Modifier.fillMaxWidth().weight(1f)) {
                val drawStarted = System.nanoTime()
                metrics.recordFrame(drawStarted)
                val w = size.width
                val h = size.height
                val left = 42.dp.toPx()
                val bottom = h - 18.dp.toPx()
                val plotW = w - left
                val plotH = bottom
                // The 2D grid belongs to the flat graph modes; the waterfall draws its
                // own axes on the projected slab.
                if (mode != GraphMode.WATERFALL) {
                val grid = Line.copy(alpha = 0.55f)
                for (i in 0..8) {
                    val y = plotH * i / 8f
                    drawLine(grid, Offset(left, y), Offset(w, y), 1f)
                }
                val yStyle = TextStyle(fontSize = 10.sp, color = Muted, fontFamily = FontFamily.Monospace)
                for (i in 0..8) {
                    val y = plotH * i / 8f
                    val db = -12 * i
                    drawLine(grid.copy(alpha = 0.85f), Offset(left - 4.dp.toPx(), y), Offset(left, y), 1f)
                    drawText(
                        textMeasurer = textMeasurer,
                        text = if (db == 0) "0" else "$db",
                        topLeft = Offset(1.dp.toPx(), (y - 7.dp.toPx()).coerceAtLeast(0f)),
                        style = yStyle,
                    )
                }
                listOf(20, 30, 40, 50, 60, 70, 80, 100, 200, 300, 400, 500,
                    700, 900, 2000, 3000, 4000, 5000, 6000, 7000, 8000, 10000, 20000).forEach { hz ->
                    val x = xForHz(hz.toDouble(), left, plotW)
                    drawLine(grid, Offset(x, 0f), Offset(x, plotH), 1f)
                }
                }
                val n = bars.size
                val cellW = plotW / n
                fun xAt(i: Int): Float = left + (i + 0.5f) * cellW
                fun x0At(i: Int): Float = left + i * cellW
                fun x1At(i: Int): Float = left + (i + 1) * cellW
                fun yAt(v: Float): Float = plotH * (1f - v.coerceIn(0f, 1f))
                when (mode) {
                    GraphMode.BARS -> {
                        val gap = (cellW * 0.18f).coerceAtMost(1.2.dp.toPx())
                        val barW = (cellW - gap).coerceAtLeast(0.8f)
                        for (i in 0 until n) {
                            val top = yAt(bars[i])
                            drawRoundRect(
                                brush = brush,
                                topLeft = Offset(x0At(i) + gap / 2f, top),
                                size = androidx.compose.ui.geometry.Size(barW, (bottom - top).coerceAtLeast(1f)),
                                cornerRadius = androidx.compose.ui.geometry.CornerRadius(1.5.dp.toPx()),
                            )
                        }
                    }
                    GraphMode.LINE -> {
                        val fill = fillPath.also { it.rewind() }
                        val line = linePath.also { it.rewind() }
                        for (i in 0 until n) {
                            val x = xAt(i)
                            val y = yAt(bars[i])
                            if (i == 0) {
                                fill.moveTo(x, bottom)
                                fill.lineTo(x, y)
                                line.moveTo(x, y)
                            } else {
                                fill.lineTo(x, y)
                                line.lineTo(x, y)
                            }
                        }
                        fill.lineTo(xAt(n - 1), bottom)
                        fill.close()
                        drawPath(fill, Brush.verticalGradient(listOf((palette.spectrum.lastOrNull() ?: theme.tertiary).copy(alpha = .42f), Ink.copy(alpha = .02f))))
                        drawPath(line, brush, style = Stroke(1.8.dp.toPx()))
                    }
                    GraphMode.WATERFALL -> {
                        drawSpectrogram(
                            history = history.toList(),
                            view = SpectrumSettings.spectrogramView,
                            area = androidx.compose.ui.geometry.Rect(
                                2.dp.toPx(),
                                2.dp.toPx(),
                                w - 46.dp.toPx(),
                                plotH - 2.dp.toPx(),
                            ),
                            textMeasurer = textMeasurer,
                            labelStyle = TextStyle(fontSize = 9.sp, color = Muted, fontFamily = FontFamily.Monospace),
                            axisColor = Line.copy(alpha = 0.75f),
                            // A slow frame means the rasterizer is the bottleneck; drop the
                            // wide halo first instead of dropping the whole frame rate.
                            glow = SpectrumSettings.spectrogramGlow,
                        )
                    }
                    GraphMode.MIRROR -> {
                        val mid = plotH / 2f
                        val top = Path()
                        val bottomPath = Path()
                        for (i in 0 until n) {
                            val x = xAt(i)
                            val d = (plotH / 2f) * bars[i] * 0.92f
                            if (i == 0) {
                                top.moveTo(x, mid - d)
                                bottomPath.moveTo(x, mid + d)
                            } else {
                                top.lineTo(x, mid - d)
                                bottomPath.lineTo(x, mid + d)
                            }
                        }
                        drawPath(top, brush, style = Stroke(1.5.dp.toPx()))
                        drawPath(bottomPath, brush, style = Stroke(1.2.dp.toPx()))
                    }
                }
                if (showPeaks && (mode == GraphMode.BARS || mode == GraphMode.LINE)) {
                    peakPath.rewind()
                    for (i in 0 until n) {
                        val x = xAt(i)
                        val y = yAt(peaks[i])
                        if (i == 0) peakPath.moveTo(x, y) else peakPath.lineTo(x, y)
                    }
                    drawPath(peakPath, Color.White.copy(alpha = .34f), style = Stroke(1.4.dp.toPx()))
                }
                metrics.recordDraw(System.nanoTime() - drawStarted)
            }
            Spacer(Modifier.height(6.dp))
            if (mode != GraphMode.WATERFALL) {
                val ticks = listOf(
                    20 to "20", 30 to "30", 40 to "40", 50 to "50", 60 to "60", 80 to "80",
                    100 to "100", 200 to "200", 500 to "500", 1000 to "1k",
                    2000 to "2k", 5000 to "5k", 10000 to "10k", 20000 to "20k",
                )
                BoxWithConstraints(Modifier.fillMaxWidth().padding(start = 42.dp).height(20.dp)) {
                    ticks.forEach { (hz, label) ->
                        val frac = frequencyFraction(hz.toDouble()).toFloat()
                        val x = (maxWidth * frac - 12.dp).coerceIn(0.dp, maxWidth - 32.dp)
                        Box(Modifier.offset(x = x)) {
                            Text(label, fontSize = 10.sp, color = Muted)
                        }
                    }
                }
            }
            Spacer(Modifier.height(6.dp))
        }
    }
}

@Composable
private fun Footer(controller: SpectrumController, theme: ThemePreset, metrics: RenderMetrics) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(Strings["rail.peaks"], fontSize = 9.sp, letterSpacing = 1.4.sp, color = Muted)
        Spacer(Modifier.width(9.dp))
        Text("${controller.peakHz} Hz", fontSize = 12.sp, color = theme.accent, fontFamily = FontFamily.Monospace)
        Spacer(Modifier.weight(1f))
        // Real repaint rate: this counter only advances when the page actually composes,
        // so it shows host-side throttling that the plugin itself cannot cause.
        val stamps = remember { ArrayDeque<Long>(64) }
        val nowNs = System.nanoTime()
        stamps.addLast(nowNs)
        while (stamps.isNotEmpty() && nowNs - stamps.first() > 1_000_000_000L) stamps.removeFirst()
        Text("${stamps.size} fps  ·  ", fontSize = 9.sp, color = Muted)
        Text(
            "绘制 ${"%.1f".format(metrics.drawMs)} ms  ·  分析 ${"%.1f".format(controller.analyzeMs)} ms",
            fontSize = 9.sp,
            color = Muted,
        )
        Spacer(Modifier.width(10.dp))
        Text("${controller.sampleRate / 1000.0} kHz  ·  16384 窗口", fontSize = 9.sp, color = Muted)
        Spacer(Modifier.width(12.dp))
        Text(
            "v${BuildInfo.version}",
            fontSize = 11.sp,
            letterSpacing = 0.6.sp,
            color = theme.accent.copy(alpha = 0.85f),
            fontFamily = FontFamily.Monospace,
        )
    }
}

@Composable
private fun SettingsRail(
    controller: SpectrumController,
    mode: GraphMode,
    theme: ThemePreset,
    showGraphModes: Boolean,
    onMode: (GraphMode) -> Unit,
    onGenerateLog: () -> Unit,
    lastLogPath: String?,
) {
    Column(
        Modifier.width(286.dp).fillMaxHeight().background(Panel)
            .border(1.dp, Color(0x1FEAF2F8))
            .verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(18.dp),
    ) {
        Text(Strings["rail.control"], fontSize = 9.sp, letterSpacing = 2.sp, color = Muted)
        // Graph modes only mean something on the spectrum page; the field view hides them.
        if (showGraphModes) {
            Column {
                Text(Strings["rail.display"], fontSize = 8.sp, letterSpacing = 1.2.sp, color = Muted)
                Spacer(Modifier.height(8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    GraphMode.entries.forEach { item ->
                        Chip(Strings["graph." + item.name], item == mode, theme.accent) { onMode(item) }
                    }
                }
            }
        }
        if (showGraphModes && mode == GraphMode.WATERFALL) {
            Column {
                Text(Strings["rail.view"], fontSize = 8.sp, letterSpacing = 1.2.sp, color = Muted)
                Spacer(Modifier.height(8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    SpectrogramView.entries.take(2).forEach { item ->
                        Chip(Strings["view." + item.name], SpectrumSettings.spectrogramView == item, theme.accent) {
                            SpectrumSettings.chooseSpectrogramView(item)
                        }
                    }
                }
                Spacer(Modifier.height(6.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    SpectrogramView.entries.drop(2).forEach { item ->
                        Chip(Strings["view." + item.name], SpectrumSettings.spectrogramView == item, theme.accent) {
                            SpectrumSettings.chooseSpectrogramView(item)
                        }
                    }
                }
            }
        }
        if (showGraphModes && mode == GraphMode.WATERFALL) {
            Column {
                Text(Strings["rail.glow"], fontSize = 8.sp, letterSpacing = 1.2.sp, color = Muted)
                Spacer(Modifier.height(8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    SpectrogramGlow.entries.forEach { item ->
                        Chip(Strings["glow." + item.name], SpectrumSettings.spectrogramGlow == item, theme.accent) {
                            SpectrumSettings.chooseSpectrogramGlow(item)
                        }
                    }
                }
            }
        }
        Column {
            Text(Strings["rail.fps"], fontSize = 8.sp, letterSpacing = 1.2.sp, color = Muted)
            Spacer(Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                FrameMode.entries.forEach { item ->
                    Chip(if (item == FrameMode.AUTO) Strings["frame.AUTO"] else item.label, SpectrumSettings.frameMode == item, theme.accent) {
                        SpectrumSettings.chooseFrameMode(item)
                        controller.applyMode(item)
                    }
                }
            }
        }
        // Band count and the low-band FFT choice also only shape the spectrum page.
        if (showGraphModes) {
            Column {
                Text(Strings["rail.bands"], fontSize = 8.sp, letterSpacing = 1.2.sp, color = Muted)
                Spacer(Modifier.height(8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    listOf(64, 96, 128, 160).forEach { n ->
                        Chip(n.toString(), SpectrumSettings.barCount == n, theme.accent) {
                            SpectrumSettings.chooseBarCount(n)
                        }
                    }
                }
                Spacer(Modifier.height(6.dp))
                Text("16384 点 FFT · 低频 32k", fontSize = 8.sp, color = Muted)
                Spacer(Modifier.height(4.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Chip(Strings["rail.bass16"], !SpectrumSettings.lowFftLarge, theme.accent) {
                        SpectrumSettings.updateLowFftLarge(false)
                    }
                    Chip(Strings["rail.bass32"], SpectrumSettings.lowFftLarge, theme.accent) {
                        SpectrumSettings.updateLowFftLarge(true)
                    }
                }
            }
        }
        Column {
            Text(Strings["rail.theme"], fontSize = 8.sp, letterSpacing = 1.2.sp, color = Muted)
            Spacer(Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                ThemePreset.entries.forEach { item ->
                    Chip(Strings["theme." + item.name], SpectrumSettings.theme == item, item.accent) {
                        SpectrumSettings.chooseTheme(item)
                    }
                }
            }
        }
        Column {
            Text(Strings["rail.color"], fontSize = 8.sp, letterSpacing = 1.2.sp, color = Muted)
            Spacer(Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                ColorMode.entries.forEach { item ->
                    Chip(Strings["color." + item.name], SpectrumSettings.colorMode == item, theme.accent) {
                        SpectrumSettings.chooseColorMode(item)
                    }
                }
            }
            if (SpectrumSettings.colorMode == ColorMode.CUSTOM) {
                Spacer(Modifier.height(8.dp))
                ColorField("频谱 A", SpectrumSettings.customSpectrumA, theme.accent) {
                    SpectrumSettings.updateCustomSpectrumA(it)
                }
                Spacer(Modifier.height(5.dp))
                ColorField("频谱 B", SpectrumSettings.customSpectrumB, theme.accent) {
                    SpectrumSettings.updateCustomSpectrumB(it)
                }
                Spacer(Modifier.height(5.dp))
                ColorField("声场 L", SpectrumSettings.customLeft, theme.accent) {
                    SpectrumSettings.updateCustomLeft(it)
                }
                Spacer(Modifier.height(5.dp))
                ColorField("声场 R", SpectrumSettings.customRight, theme.accent) {
                    SpectrumSettings.updateCustomRight(it)
                }
            }
        }
        Column {
            Text(Strings["rail.effects"], fontSize = 8.sp, letterSpacing = 1.2.sp, color = Muted)
            Spacer(Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Chip(Strings.toggle("rail.peaks", SpectrumSettings.showPeaks),
                    SpectrumSettings.showPeaks, theme.accent) {
                    SpectrumSettings.updatePeaks(!SpectrumSettings.showPeaks)
                }
                Chip(Strings.toggle("rail.anim", SpectrumSettings.animations),
                    SpectrumSettings.animations, theme.accent) {
                    SpectrumSettings.updateAnimations(!SpectrumSettings.animations)
                }
            }
            Spacer(Modifier.height(6.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Chip(Strings.toggle("rail.adaptive", SpectrumSettings.polarAdaptivePeak),
                    SpectrumSettings.polarAdaptivePeak, theme.accent) {
                    SpectrumSettings.updateAdaptivePolarPeak(!SpectrumSettings.polarAdaptivePeak)
                }
            }
        }
        Column {
            Text(Strings["rail.perf"], fontSize = 8.sp, letterSpacing = 1.2.sp, color = Muted)
            Spacer(Modifier.height(8.dp))
            Chip(
                Strings.toggle("rail.throttle", SpectrumSettings.lowFpsOnUnfocus),
                SpectrumSettings.lowFpsOnUnfocus, theme.accent,
            ) {
                SpectrumSettings.updateLowFpsOnUnfocus(!SpectrumSettings.lowFpsOnUnfocus)
            }
            Spacer(Modifier.height(6.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Chip(Strings["rail.log"], false, theme.accent) { onGenerateLog() }
            }
            if (lastLogPath != null) {
                Spacer(Modifier.height(4.dp))
                Text(
                    "${Strings["log.saved"]}：$lastLogPath",
                    fontSize = 7.sp,
                    color = Muted,
                    lineHeight = 9.sp,
                )
            }
        }
        Column {
            Text(Strings["rail.language"], fontSize = 8.sp, letterSpacing = 1.2.sp, color = Muted)
            Spacer(Modifier.height(8.dp))
            // Native names only: a user who cannot read the current UI language still
            // recognises their own.
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                AppLanguage.entries.take(5).forEach { item ->
                    Chip(item.nativeName, SpectrumSettings.language == item, theme.accent) {
                        SpectrumSettings.chooseLanguage(item)
                    }
                }
            }
            Spacer(Modifier.height(6.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                AppLanguage.entries.drop(5).forEach { item ->
                    Chip(item.nativeName, SpectrumSettings.language == item, theme.accent) {
                        SpectrumSettings.chooseLanguage(item)
                    }
                }
            }
        }
        Chip(Strings["rail.reset"], false, theme.accent) {
            SpectrumSettings.reset()
            controller.applyMode(SpectrumSettings.frameMode)
        }
        Text("SAKANA PRISM", fontSize = 8.sp, letterSpacing = 1.3.sp, color = Muted.copy(alpha = .7f))
    }
}

@Composable
private fun ColorField(label: String, value: String, accent: Color, onValue: (String) -> Unit) {
    var text by remember(value) { mutableStateOf(value) }
    val parsed = ArtworkPalette.parseHex(text)
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Box(
            Modifier.size(14.dp).background(parsed ?: Muted, RoundedCornerShape(3.dp))
                .border(1.dp, Color(0xFF304052), RoundedCornerShape(3.dp)),
        )
        Spacer(Modifier.width(6.dp))
        Text(label, fontSize = 8.sp, color = Muted, modifier = Modifier.width(42.dp))
        BasicTextField(
            value = text,
            onValueChange = { raw ->
                val next = raw.take(7)
                text = next
                val color = ArtworkPalette.parseHex(next)
                if (color != null) onValue(ArtworkPalette.toHex(color))
            },
            singleLine = true,
            textStyle = TextStyle(color = Color(0xFFE8EEF5), fontSize = 9.sp, fontFamily = FontFamily.Monospace),
            modifier = Modifier.weight(1f)
                .background(Color(0xFF111923), RoundedCornerShape(4.dp))
                .border(1.dp, if (parsed != null) accent.copy(alpha = .45f) else Color(0xFF3A2530), RoundedCornerShape(4.dp))
                .padding(horizontal = 7.dp, vertical = 4.dp),
        )
    }
}

@Composable
private fun Chip(label: String, selected: Boolean, accent: Color = Teal, onClick: () -> Unit) {
    Box(
        Modifier
            .border(1.dp, if (selected) accent.copy(alpha = .75f) else Line, RoundedCornerShape(7.dp))
            .background(if (selected) accent.copy(alpha = .12f) else Color.Transparent, RoundedCornerShape(7.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 6.dp),
    ) {
        Text(label, fontSize = 9.sp, color = if (selected) accent else Muted)
    }
}

private fun resample(source: FloatArray, count: Int): FloatArray {
    if (source.isEmpty() || count <= 0) return FloatArray(0)
    if (source.size == count) return source
    return FloatArray(count) { index ->
        val pos = index.toFloat() / (count - 1).coerceAtLeast(1) * (source.size - 1)
        val low = pos.toInt().coerceIn(0, source.size - 1)
        val high = (low + 1).coerceAtMost(source.size - 1)
        val t = pos - low
        source[low] * (1f - t) + source[high] * t
    }
}

private const val MIN_HZ = 20.0
private const val MAX_HZ = 20000.0

private fun frequencyFraction(hz: Double): Double =
    (ln(hz / MIN_HZ) / ln(MAX_HZ / MIN_HZ)).coerceIn(0.0, 1.0)

private fun xForHz(hz: Double, left: Float, width: Float): Float =
    left + width * frequencyFraction(hz).toFloat()

private fun bandLow(index: Int, count: Int): Double =
    MIN_HZ * (MAX_HZ / MIN_HZ).pow(index.toDouble() / count)

private fun bandHigh(index: Int, count: Int): Double =
    MIN_HZ * (MAX_HZ / MIN_HZ).pow((index + 1).toDouble() / count)

private fun bandCenter(index: Int, count: Int): Double =
    kotlin.math.sqrt(bandLow(index, count) * bandHigh(index, count))

private fun ln(value: Double): Double = kotlin.math.ln(value)
