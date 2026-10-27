@file:OptIn(com.xuncorp.spw.workshop.api.UnstableSpwWorkshopApi::class)

package dev.sakana.spw.prism.settings

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Color
import com.xuncorp.spw.workshop.api.config.ConfigHelper
import dev.sakana.spw.prism.preview.FrameMode

enum class ColorMode(val label: String) {
    COVER("封面"),
    CUSTOM("自定义"),
    THEME("主题"),
}

enum class ThemePreset(
    val label: String,
    val accent: Color,
    val secondary: Color,
    val tertiary: Color,
    val highlight: Color,
) {
    AURORA("极光", Color(0xFF4DD8C4), Color(0xFF5B8CFF), Color(0xFFA77BFF), Color(0xFFFFC46B)),
    AMBER("琥珀", Color(0xFFFFB45B), Color(0xFFFF7A59), Color(0xFFD9558B), Color(0xFFFFE0A3)),
    MONO("单色", Color(0xFFD7E2EE), Color(0xFF9FB0C4), Color(0xFF6F8298), Color(0xFFEAF2F9)),
}

/** Small, version-tolerant settings surface persisted by the host config helper. */
object SpectrumSettings {
    var frameMode by mutableStateOf(FrameMode.SIXTY)
        private set
    var graphMode by mutableStateOf(GraphMode.BARS)
        private set
    var spectrogramView by mutableStateOf(SpectrogramView.FRONT_LOW)
        private set
    var barCount by mutableIntStateOf(160)
        private set
    var theme by mutableStateOf(ThemePreset.AURORA)
        private set
    var colorMode by mutableStateOf(ColorMode.COVER)
        private set
    var customSpectrumA by mutableStateOf("#4DD8C4")
        private set
    var customSpectrumB by mutableStateOf("#FFB45B")
        private set
    var customLeft by mutableStateOf("#4DD8C4")
        private set
    var customRight by mutableStateOf("#FFB45B")
        private set
    var showPeaks by mutableStateOf(true)
        private set
    var animations by mutableStateOf(true)
        private set
    var lowLoad by mutableStateOf(false)
        private set
    var autoOpen by mutableStateOf(false)
        private set
    var autoPlay by mutableStateOf(false)
        private set
    var lowFftLarge by mutableStateOf(true)
        private set
    var mainFft8192 by mutableStateOf(false)
        private set
    var spectrogramGlow by mutableStateOf(SpectrogramGlow.MEDIUM)
        private set
    var lowFpsOnUnfocus by mutableStateOf(true)
        private set
    /** Scale the polar cloud by a sliding peak so quiet masters still reach the outer ring. */
    var polarAdaptivePeak by mutableStateOf(false)
        private set
    var peakHoldMs by mutableIntStateOf(1000)
        private set
    var peakDecayDbPerSec by mutableIntStateOf(21)
        private set

    private var helper: ConfigHelper? = null

    fun attach(config: ConfigHelper) {
        helper = config
        frameMode = runCatching { FrameMode.valueOf(config.get("frameMode", "SIXTY") ?: "SIXTY") }
            .getOrDefault(FrameMode.SIXTY)
        graphMode = runCatching { GraphMode.valueOf(config.get("graphMode", "BARS") ?: "BARS") }
            .getOrDefault(GraphMode.BARS)
        spectrogramView = runCatching { SpectrogramView.valueOf(config.get("spectrogramView", "FRONT_LOW") ?: "FRONT_LOW") }
            .getOrDefault(SpectrogramView.FRONT_LOW)
        barCount = (config.get("barCount", 160) ?: 160).coerceIn(32, 160)
        theme = runCatching { ThemePreset.valueOf(config.get("theme", "AURORA") ?: "AURORA") }
            .getOrDefault(ThemePreset.AURORA)
        colorMode = runCatching { ColorMode.valueOf(config.get("colorMode", "COVER") ?: "COVER") }
            .getOrDefault(ColorMode.COVER)
        customSpectrumA = config.get("customSpectrumA", "#4DD8C4") ?: "#4DD8C4"
        customSpectrumB = config.get("customSpectrumB", "#FFB45B") ?: "#FFB45B"
        customLeft = config.get("customLeft", "#4DD8C4") ?: "#4DD8C4"
        customRight = config.get("customRight", "#FFB45B") ?: "#FFB45B"
        showPeaks = config.get("showPeaks", true) ?: true
        animations = config.get("animations", true) ?: true
        lowLoad = config.get("lowLoad", false) ?: false
        autoOpen = config.get("autoOpen", false) ?: false
        autoPlay = config.get("autoPlay", false) ?: false
        lowFftLarge = config.get("lowFftLarge", true) ?: true
        mainFft8192 = config.get("mainFft8192", false) ?: false
        spectrogramGlow = runCatching {
            SpectrogramGlow.valueOf(config.get("spectrogramGlow", SpectrogramGlow.MEDIUM.name) ?: SpectrogramGlow.MEDIUM.name)
        }.getOrDefault(SpectrogramGlow.MEDIUM)
        lowFpsOnUnfocus = config.get("lowFpsOnUnfocus", true) ?: true
        polarAdaptivePeak = config.get("polarAdaptivePeak", false) ?: false
        peakHoldMs = (config.get("peakHoldMs", 1000) ?: 1000).coerceIn(200, 4000)
        peakDecayDbPerSec = (config.get("peakDecayDbPerSec", 21) ?: 21).coerceIn(6, 60)
        runCatching {
            java.io.File(System.getProperty("java.io.tmpdir"), "spw-prism-settings.txt")
                .writeText("frame=$frameMode bars=$barCount theme=$theme peaks=$showPeaks anim=$animations low=$lowLoad path=${config.getConfigPath()} auto=$autoOpen play=$autoPlay graph=$graphMode hold=$peakHoldMs decay=$peakDecayDbPerSec low32k=$lowFftLarge")
        }
    }

    fun chooseFrameMode(value: FrameMode) {
        frameMode = value
        persist("frameMode", value.name)
    }

    fun chooseGraphMode(value: GraphMode) {
        graphMode = value
        persist("graphMode", value.name)
    }

    fun chooseSpectrogramView(value: SpectrogramView) {
        spectrogramView = value
        persist("spectrogramView", value.name)
    }

    fun chooseBarCount(value: Int) {
        barCount = value.coerceIn(32, 160)
        persist("barCount", barCount)
    }

    fun chooseTheme(value: ThemePreset) {
        theme = value
        persist("theme", value.name)
    }

    fun chooseColorMode(value: ColorMode) {
        colorMode = value
        persist("colorMode", value.name)
    }

    fun updateCustomSpectrumA(value: String) {
        customSpectrumA = value
        persist("customSpectrumA", value)
    }

    fun updateCustomSpectrumB(value: String) {
        customSpectrumB = value
        persist("customSpectrumB", value)
    }

    fun updateCustomLeft(value: String) {
        customLeft = value
        persist("customLeft", value)
    }

    fun updateCustomRight(value: String) {
        customRight = value
        persist("customRight", value)
    }

    fun updatePeaks(value: Boolean) {
        showPeaks = value
        persist("showPeaks", value)
    }

    fun chooseSpectrogramGlow(value: SpectrogramGlow) {
        spectrogramGlow = value
        persist("spectrogramGlow", value.name)
    }

    fun updateLowFpsOnUnfocus(value: Boolean) {
        lowFpsOnUnfocus = value
        persist("lowFpsOnUnfocus", value)
    }

    fun updateAnimations(value: Boolean) {
        animations = value
        persist("animations", value)
    }

    fun updateLowLoad(value: Boolean) {
        lowLoad = value
        persist("lowLoad", value)
    }

    fun updateAutoOpen(value: Boolean) {
        autoOpen = value
        persist("autoOpen", value)
    }

    fun updateAutoPlay(value: Boolean) {
        autoPlay = value
        persist("autoPlay", value)
    }

    fun updateLowFftLarge(value: Boolean) {
        lowFftLarge = value
        persist("lowFftLarge", value)
    }

    fun updateAdaptivePolarPeak(value: Boolean) {
        polarAdaptivePeak = value
        persist("polarAdaptivePeak", value)
    }

    fun choosePeakHold(value: Int) {
        peakHoldMs = value.coerceIn(200, 4000)
        persist("peakHoldMs", peakHoldMs)
    }

    fun choosePeakDecay(value: Int) {
        peakDecayDbPerSec = value.coerceIn(6, 60)
        persist("peakDecayDbPerSec", peakDecayDbPerSec)
    }

    fun flush() {
        helper?.save()
    }

    fun reset() {
        frameMode = FrameMode.SIXTY
        graphMode = GraphMode.BARS
        spectrogramView = SpectrogramView.FRONT_LOW
        spectrogramGlow = SpectrogramGlow.MEDIUM
        barCount = 160
        theme = ThemePreset.AURORA
        colorMode = ColorMode.COVER
        customSpectrumA = "#4DD8C4"
        customSpectrumB = "#FFB45B"
        customLeft = "#4DD8C4"
        customRight = "#FFB45B"
        showPeaks = true
        animations = true
        lowLoad = false
        autoOpen = false
        autoPlay = false
        lowFftLarge = true
        mainFft8192 = false
        polarAdaptivePeak = false
        peakHoldMs = 1000
        peakDecayDbPerSec = 21
        helper?.let {
            it.set("frameMode", frameMode.name)
            it.set("graphMode", graphMode.name)
            it.set("spectrogramView", spectrogramView.name)
            it.set("barCount", barCount)
            it.set("theme", theme.name)
            it.set("colorMode", colorMode.name)
            it.set("customSpectrumA", customSpectrumA)
            it.set("customSpectrumB", customSpectrumB)
            it.set("customLeft", customLeft)
            it.set("customRight", customRight)
            it.set("showPeaks", showPeaks)
            it.set("animations", animations)
            it.set("lowLoad", lowLoad)
            it.set("autoOpen", autoOpen)
            it.set("autoPlay", autoPlay)
            it.set("lowFftLarge", lowFftLarge)
            it.set("mainFft8192", mainFft8192)
            it.set("polarAdaptivePeak", polarAdaptivePeak)
            it.set("spectrogramGlow", spectrogramGlow.name)
            it.set("lowFpsOnUnfocus", lowFpsOnUnfocus)
            it.set("peakHoldMs", peakHoldMs)
            it.set("peakDecayDbPerSec", peakDecayDbPerSec)
            it.save()
        }
    }

    private fun persist(key: String, value: Any) {
        helper?.let {
            it.set(key, value)
            it.save()
        }
    }
}
