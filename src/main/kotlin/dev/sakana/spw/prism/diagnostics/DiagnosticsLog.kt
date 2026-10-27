@file:OptIn(com.xuncorp.spw.workshop.api.UnstableSpwWorkshopApi::class)

package dev.sakana.spw.prism.diagnostics

import com.xuncorp.spw.workshop.api.WorkshopApi
import dev.sakana.spw.prism.preview.SpectrumController
import dev.sakana.spw.prism.settings.SpectrumSettings
import dev.sakana.spw.prism.ui.RenderMetrics
import java.nio.file.Files
import java.nio.file.Path
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.Properties

/** Plugin version, read from the packaged manifest so it never drifts from the build. */
object BuildInfo {
    val version: String by lazy {
        runCatching {
            val stream = BuildInfo::class.java.classLoader
                ?.getResourceAsStream("META-INF/plugin.properties") ?: return@runCatching null
            stream.use { Properties().apply { load(it) }.getProperty("plugin.version") }
        }.getOrNull() ?: "?"
    }
}

/**
 * Writes one plain-text report the user can hand over when something looks wrong. It avoids
 * their system paths beyond the plugin's own data folder, and never touches the audio files.
 */
object DiagnosticsLog {

    fun write(controller: SpectrumController, metrics: RenderMetrics): Path? = runCatching {
        val directory = dataDirectory().resolve("logs")
        Files.createDirectories(directory)
        val stamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss"))
        val target = directory.resolve("sakana-$stamp.log")

        val report = buildString {
            appendLine("Sakana Prism diagnostics")
            appendLine("time      : ${LocalDateTime.now()}")
            appendLine("version   : ${BuildInfo.version}")
            appendLine("os        : ${System.getProperty("os.name")} ${System.getProperty("os.version")} (${System.getProperty("os.arch")})")
            appendLine("java      : ${System.getProperty("java.version")} / ${System.getProperty("java.vendor")}")
            appendLine("jvm args  : ${runCatching { java.lang.management.ManagementFactory.getRuntimeMXBean().inputArguments.joinToString(" ") }.getOrNull()}")
            appendLine("processors: ${Runtime.getRuntime().availableProcessors()}")
            appendLine()
            appendLine("[audio]")
            appendLine("sampleRate: ${controller.sampleRate}")
            appendLine("track     : ${controller.trackPath}")
            appendLine()
            appendLine("[performance]")
            appendLine("fps       : ${"%.1f".format(metrics.fps)}")
            appendLine("drawMs    : ${"%.2f".format(metrics.drawMs)}")
            appendLine("p95Ms     : ${"%.2f".format(metrics.p95Ms)}")
            appendLine("p99Ms     : ${"%.2f".format(metrics.p99Ms)}")
            appendLine("analyzeMs : ${"%.2f".format(controller.analyzeMs)}")
            appendLine("actualFps : ${"%.1f".format(controller.actualFps)}")
            appendLine()
            appendLine("[settings]")
            appendLine("graphMode : ${SpectrumSettings.graphMode}")
            appendLine("view      : ${SpectrumSettings.spectrogramView}")
            appendLine("frameMode : ${SpectrumSettings.frameMode}")
            appendLine("barCount  : ${SpectrumSettings.barCount}")
            appendLine("glow      : ${SpectrumSettings.spectrogramGlow}")
            appendLine("theme     : ${SpectrumSettings.theme}")
            appendLine("peaks     : ${SpectrumSettings.showPeaks}")
            appendLine("lowFft32k : ${SpectrumSettings.lowFftLarge}")
            appendLine("throttle  : ${SpectrumSettings.lowFpsOnUnfocus}")
            appendLine()
            appendLine("dataDir   : ${dataDirectory()}")
        }
        Files.writeString(target, report)
        target
    }.getOrNull()

    private fun dataDirectory(): Path {
        val fromHost: Path? = runCatching {
            val config = WorkshopApi.manager.createConfigManager().getConfig()
            config.getConfigPath().parent
        }.getOrNull()
        return fromHost ?: Path.of(
            System.getenv("APPDATA") ?: System.getProperty("user.home"),
            "Salt Player for Windows",
            "workshop",
            "data",
            "dev.sakana.spw.prism",
        )
    }
}
