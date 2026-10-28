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
import androidx.compose.foundation.layout.heightIn
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
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.ui.draw.clip
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

// Surfaces, borders and text weights in one place so the three panels agree.
private val Surface = Color(0xFF101823)
private val SurfaceHigh = Color(0xFF16202C)
private val Hairline = Color(0x14FFFFFF)
private val HairlineStrong = Color(0x24FFFFFF)
private val TextPrimary = Color(0xFFE8EFF7)
private val TextSecondary = Color(0xFF9AA9BA)
private val TextTertiary = Color(0xFF67758A)
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
    val hostAppearance = remember {
        runCatching { HostTheme.detect() }.getOrDefault(HostAppearance.DARK)
    }
    val hostTokens = remember(hostAppearance, SpectrumSettings.theme) {
        // 主题仍是手动覆盖项；默认跟随宿主时用宿主强调色。
        val override = if (SpectrumSettings.theme == ThemePreset.AURORA) null else SpectrumSettings.theme.accent
        HostTheme.tokens(hostAppearance, override)
    }
    CompositionLocalProvider(
        LocalDensity provides Density(baseDensity.density, baseDensity.fontScale * 1.2f),
        LocalHostTokens provides hostTokens,
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
            Row(Modifier.fillMaxSize()) {
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
            Row(verticalAlignment = Alignment.CenterVertically) {
                // Accent bar: cheap way to anchor the title without a logo image.
                Box(
                    Modifier
                        .width(4.dp)
                        .height(30.dp)
                        .background(
                            Brush.verticalGradient(listOf(theme.accent, theme.accent.copy(alpha = 0.15f))),
                            RoundedCornerShape(2.dp),
                        ),
                )
                Spacer(Modifier.width(12.dp))
                Text(
                    if (viewMode == ViewMode.FIELD) Strings["title.field"] else Strings["title.spectrum"],
                    style = Type.title,
                    color = LocalHostTokens.current.onSurface,
                )
            }
            Spacer(Modifier.height(4.dp))
            Text(
                Strings.brand + "  ·  " + if (viewMode == ViewMode.FIELD) Strings["sub.field"] else Strings["sub.spectrum"],
                style = Type.caption,
                color = LocalHostTokens.current.onSurfaceVariant,
            )
        }
        Spacer(Modifier.weight(1f))
        // Same animated segmented control as the settings rail: the pill slides.
        SegmentedControl(
            options = ViewMode.entries,
            selected = viewMode,
            label = { if (it.name == "FIELD") Strings["tab.field"] else Strings["tab.spectrum"] },
            onSelect = onViewMode,
        )
        Spacer(Modifier.width(10.dp))
        StatusDot(controller, theme)
    }
}

@Composable
private fun StatusDot(controller: SpectrumController, theme: ThemePreset) {
    val error = controller.error
    val color = if (error == null) theme.accent else Amber
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .clip(RoundedCornerShape(50))
            .background(color.copy(alpha = 0.10f), RoundedCornerShape(50))
            .border(1.dp, color.copy(alpha = 0.30f), RoundedCornerShape(50))
            .padding(horizontal = 12.dp, vertical = 7.dp),
    ) {
        Box(Modifier.size(6.dp).background(color, RoundedCornerShape(50)))
        Spacer(Modifier.width(7.dp))
        Text(
            error ?: Strings["live"],
            fontSize = 10.sp,
            letterSpacing = 0.6.sp,
            color = color,
        )
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
    // Read once outside the draw scope: CompositionLocal access is not allowed inside Canvas.
    val axisTextColor = LocalHostTokens.current.onSurfaceVariant
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
        shape = RoundedCornerShape(22.dp),
        color = Surface,
        border = BorderStroke(1.dp, Hairline),
    ) {
        Column(Modifier.fillMaxSize().padding(18.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(Strings["response"], fontSize = 9.sp, letterSpacing = 1.8.sp, color = LocalHostTokens.current.onSurfaceVariant)
                Spacer(Modifier.weight(1f))
                Text("16384 点 FFT · $barCount 频段 · dBFS", fontSize = 9.sp, color = LocalHostTokens.current.onSurfaceVariant)
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
                val yStyle = TextStyle(fontSize = 10.sp, color = axisTextColor, fontFamily = FontFamily.Monospace)
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
                            labelStyle = TextStyle(fontSize = 10.sp, color = axisTextColor, fontFamily = FontFamily.Monospace),
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
                            Text(label, fontSize = 10.sp, color = LocalHostTokens.current.onSurfaceVariant)
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
        Text(Strings["rail.peaks"], fontSize = 9.sp, letterSpacing = 1.4.sp, color = LocalHostTokens.current.onSurfaceVariant)
        Spacer(Modifier.width(9.dp))
        Text("${controller.peakHz} Hz", fontSize = 12.sp, color = theme.accent, fontFamily = FontFamily.Monospace)
        Spacer(Modifier.weight(1f))
        // Real repaint rate: this counter only advances when the page actually composes,
        // so it shows host-side throttling that the plugin itself cannot cause.
        val stamps = remember { ArrayDeque<Long>(64) }
        val nowNs = System.nanoTime()
        stamps.addLast(nowNs)
        while (stamps.isNotEmpty() && nowNs - stamps.first() > 1_000_000_000L) stamps.removeFirst()
        Text("${stamps.size} fps  ·  ", fontSize = 9.sp, color = LocalHostTokens.current.onSurfaceVariant)
        Text(
            Strings["footer.draw"] + " ${"%.1f".format(metrics.drawMs)} ms  ·  " + Strings["footer.analyze"] + " ${"%.1f".format(controller.analyzeMs)} ms",
            fontSize = 9.sp,
            color = LocalHostTokens.current.onSurfaceVariant,
        )
        Spacer(Modifier.width(10.dp))
        Text("${controller.sampleRate / 1000.0} kHz  ·  16384 " + Strings["footer.window"], fontSize = 9.sp, color = LocalHostTokens.current.onSurfaceVariant)
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
    val tokens = LocalHostTokens.current
    Column(
        Modifier.width(320.dp).fillMaxHeight()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp, vertical = 8.dp),
    ) {
        Text(Strings["rail.control"], style = Type.section, color = tokens.onSurface)
        Spacer(Modifier.height(4.dp))

        if (showGraphModes) {
            SectionHeader(Strings["rail.display"])
            SettingCard {
                ControlRow {
                    SegmentedControl(
                        options = GraphMode.entries,
                        selected = mode,
                        label = { Strings["graph." + it.name] },
                        onSelect = onMode,
                    )
                }
            }
        }

        if (showGraphModes && mode == GraphMode.WATERFALL) {
            SectionHeader(Strings["rail.view"])
            SettingCard {
                ControlRow {
                    SegmentedControl(
                        options = SpectrogramView.entries.take(2),
                        selected = SpectrumSettings.spectrogramView,
                        label = { Strings["view." + it.name] },
                        onSelect = { SpectrumSettings.chooseSpectrogramView(it) },
                    )
                }
                ControlRow {
                    SegmentedControl(
                        options = SpectrogramView.entries.drop(2),
                        selected = SpectrumSettings.spectrogramView,
                        label = { Strings["view." + it.name] },
                        onSelect = { SpectrumSettings.chooseSpectrogramView(it) },
                    )
                }
            }
            SectionHeader(Strings["rail.glow"])
            SettingCard {
                ControlRow {
                    SegmentedControl(
                        options = SpectrogramGlow.entries,
                        selected = SpectrumSettings.spectrogramGlow,
                        label = { Strings["glow." + it.name] },
                        onSelect = { SpectrumSettings.chooseSpectrogramGlow(it) },
                    )
                }
            }
        }

        SectionHeader(Strings["rail.fps"])
        SettingCard {
            ControlRow {
                SegmentedControl(
                    options = FrameMode.entries,
                    selected = SpectrumSettings.frameMode,
                    label = { if (it == FrameMode.AUTO) Strings["frame.AUTO"] else it.label },
                    onSelect = {
                        SpectrumSettings.chooseFrameMode(it)
                        controller.applyMode(it)
                    },
                )
            }
        }

        if (showGraphModes) {
            SectionHeader(Strings["rail.bands"])
            SettingCard {
                ControlRow {
                    SegmentedControl(
                        options = listOf(64, 96, 128, 160),
                        selected = SpectrumSettings.barCount,
                        label = { it.toString() },
                        onSelect = { SpectrumSettings.chooseBarCount(it) },
                    )
                }
                ToggleRow(
                    label = Strings["rail.bass32"],
                    checked = SpectrumSettings.lowFftLarge,
                    onCheckedChange = { SpectrumSettings.updateLowFftLarge(it) },
                    description = "16k / 32k FFT window",
                )
            }
        }

        SectionHeader(Strings["rail.theme"])
        SettingCard {
            ControlRow {
                SegmentedControl(
                    options = ThemePreset.entries,
                    selected = SpectrumSettings.theme,
                    label = { Strings["theme." + it.name] },
                    onSelect = { SpectrumSettings.chooseTheme(it) },
                )
            }
            ControlRow {
                SegmentedControl(
                    options = ColorMode.entries,
                    selected = SpectrumSettings.colorMode,
                    label = { Strings["color." + it.name] },
                    onSelect = { SpectrumSettings.chooseColorMode(it) },
                )
            }
        }

        SectionHeader(Strings["rail.effects"])
        SettingCard {
            ToggleRow(
                label = Strings["rail.peaks"],
                checked = SpectrumSettings.showPeaks,
                onCheckedChange = { SpectrumSettings.updatePeaks(it) },
            )
            ToggleRow(
                label = Strings["rail.anim"],
                checked = SpectrumSettings.animations,
                onCheckedChange = { SpectrumSettings.updateAnimations(it) },
            )
            ToggleRow(
                label = Strings["rail.adaptive"],
                checked = SpectrumSettings.polarAdaptivePeak,
                onCheckedChange = { SpectrumSettings.updateAdaptivePolarPeak(it) },
            )
        }

        SectionHeader(Strings["rail.perf"])
        SettingCard {
            ToggleRow(
                label = Strings["rail.throttle"],
                checked = SpectrumSettings.lowFpsOnUnfocus,
                onCheckedChange = { SpectrumSettings.updateLowFpsOnUnfocus(it) },
            )
            ControlRow {
                ActionButton(Strings["rail.log"], onGenerateLog, accent = true)
            }
            if (lastLogPath != null) {
                Text(
                    Strings["log.saved"] + ": " + lastLogPath,
                    style = Type.caption,
                    color = tokens.onSurfaceVariant,
                    modifier = Modifier.padding(bottom = 8.dp),
                )
            }
        }

        SectionHeader(Strings["rail.language"])
        SettingCard {
            AppLanguage.entries.chunked(3).forEach { row ->
                Row(
                    Modifier.fillMaxWidth().padding(vertical = 4.dp),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    row.forEach { item ->
                        val selected = SpectrumSettings.language == item
                        Box(
                            Modifier
                                .weight(1f)
                                .clip(RoundedCornerShape(6.dp))
                                .background(
                                    if (selected) tokens.accent.copy(alpha = 0.16f) else Color.Transparent,
                                    RoundedCornerShape(6.dp),
                                )
                                .clickable { SpectrumSettings.chooseLanguage(item) }
                                .heightIn(min = 32.dp)
                                .padding(horizontal = 8.dp, vertical = 7.dp),
                            contentAlignment = Alignment.Center,
                        ) {
                            Text(
                                item.nativeName,
                                style = Type.caption,
                                color = if (selected) tokens.accent else tokens.onSurfaceVariant,
                                maxLines = 1,
                            )
                        }
                    }
                    repeat(3 - row.size) { Spacer(Modifier.weight(1f)) }
                }
            }
        }

        Spacer(Modifier.height(20.dp))
        ActionButton(
            Strings["rail.reset"],
            onClick = {
                SpectrumSettings.reset()
                controller.applyMode(SpectrumSettings.frameMode)
            },
        )
        Spacer(Modifier.height(24.dp))
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
        Text(label, fontSize = 8.sp, color = LocalHostTokens.current.onSurfaceVariant, modifier = Modifier.width(42.dp))
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
private fun Chip(
    label: String,
    selected: Boolean,
    accent: Color = Teal,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    val interaction = remember { MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()
    val shape = RoundedCornerShape(50)
    Box(
        modifier
            .clip(shape)
            .background(
                when {
                    selected -> accent.copy(alpha = 0.16f)
                    hovered -> SurfaceHigh
                    else -> Color.Transparent
                },
                shape,
            )
            .border(
                1.dp,
                when {
                    selected -> accent.copy(alpha = 0.55f)
                    hovered -> HairlineStrong
                    else -> Hairline
                },
                shape,
            )
            .clickable(interactionSource = interaction, indication = null, onClick = onClick)
            .padding(horizontal = 11.dp, vertical = 7.dp),
    ) {
        Text(
            label,
            fontSize = 11.sp,
            letterSpacing = 0.2.sp,
            color = if (selected) accent else TextSecondary,
            maxLines = 1,
        )
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