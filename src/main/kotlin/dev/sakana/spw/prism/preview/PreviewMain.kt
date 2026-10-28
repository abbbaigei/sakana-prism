package dev.sakana.spw.prism.preview

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import dev.sakana.spw.prism.audio.BassBootstrap
import dev.sakana.spw.prism.audio.SpectrumAnalyzer
import dev.sakana.spw.prism.audio.LoudnessTags
import dev.sakana.spw.prism.audio.SpectrumLayout
import dev.sakana.spw.prism.settings.SpectrumSettings
import dev.sakana.spw.prism.ui.SpectrumDesign
import java.util.concurrent.locks.LockSupport
import java.awt.GraphicsEnvironment
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import kotlin.math.max

private const val UNFOCUSED_FPS = 15

/** How long (ns) before each deadline the loop spins instead of parking. */
private const val SPIN_WINDOW_NS = 2_000_000L
enum class FrameMode(val label: String) {
    LOW("30"),
    SIXTY("60"),
    AUTO("自动"),
}

class SpectrumController(
    val trackPath: Path,
    private val positionProvider: (() -> Long)? = null,
    private val playingProvider: (() -> Boolean)? = null,
) {
    var bands by mutableStateOf(FloatArray(SpectrumLayout.POINTS))
        private set
    var peakHz by mutableIntStateOf(0)
        private set
    var actualFps by mutableFloatStateOf(0f)
    /** Smoothed cost of one analysis frame, milliseconds. */
    var analyzeMs by mutableFloatStateOf(0f)
        private set
    var targetFps by mutableIntStateOf(60)
        private set
    var mode by mutableStateOf(FrameMode.SIXTY)
        private set
    var displayHz by mutableIntStateOf(60)
        private set
    var error by mutableStateOf<String?>(null)
        private set
    var sampleRate by mutableIntStateOf(0)
        private set
    /** ReplayGain / R128 metadata of the current track, when the file carries it. */
    var loudness by mutableStateOf<LoudnessTags?>(null)
        private set
    var leftPeak by mutableFloatStateOf(0f)
        private set
    var rightPeak by mutableFloatStateOf(0f)
        private set
    var leftRms by mutableFloatStateOf(0f)
        private set
    var rightRms by mutableFloatStateOf(0f)
        private set
    var leftHold by mutableFloatStateOf(0f)
        private set
    var rightHold by mutableFloatStateOf(0f)
        private set
    var stereo by mutableStateOf(true)
        private set
    var correlation by mutableFloatStateOf(1f)
        private set
    var stereoWidth by mutableFloatStateOf(0f)
        private set
    var field by mutableStateOf(FloatArray(0))
        private set
    var fieldOpacity by mutableFloatStateOf(1f)
        private set

    private var holdAtL = 0L
    private var holdAtR = 0L
    private val holdWindowNs: Long get() = dev.sakana.spw.prism.settings.SpectrumSettings.peakHoldMs * 1_000_000L
    private var unfocused = false
    @Volatile private var throttleEnabled = true
    private var fpsEma = 0f
    private var fpsPublishAt = 0L
    private val running = AtomicBoolean(false)
    private var worker: Thread? = null
    private var displayWorker: Thread? = null
    private val targetBands = AtomicReference<FloatArray?>(null)

    init {
        val screen = runCatching {
            GraphicsEnvironment.getLocalGraphicsEnvironment().defaultScreenDevice.displayMode.refreshRate
        }.getOrDefault(60)
        displayHz = if (screen in 24..360) screen else 60
    }

    fun applyMode(value: FrameMode) {
        mode = value
        targetFps = when (value) {
            FrameMode.LOW -> 30
            FrameMode.SIXTY -> 60
            FrameMode.AUTO -> displayHz.coerceAtMost(165)
        }
    }

    /**
     * Window focus, reported by the UI. When the host window is in the background the
     * analysis and display loops drop to [UNFOCUSED_FPS] so the plugin stops costing
     * frames nobody can see (opt-out via 非焦点时降低帧数).
     */
    fun setUnfocused(value: Boolean) {
        unfocused = value
    }

    /** Mirrors the 非焦点时降低帧数 switch so the worker threads never read UI state. */
    fun setThrottleEnabled(value: Boolean) {
        throttleEnabled = value
    }

    /** Frame cap actually used by the loops. */
    private val activeFps: Int
        get() = if (unfocused && throttleEnabled) minOf(targetFps, UNFOCUSED_FPS) else targetFps

    fun start() {
        if (!running.compareAndSet(false, true)) return
        applyMode(mode)
        Thread({
            loudness = LoudnessTags.read(trackPath)
        }, "SpectrumTags").apply {
            isDaemon = true
            start()
        }
        worker = Thread({
            try {
                val bass = BassBootstrap.load()
                BassBootstrap.initDecode(bass)
                SpectrumAnalyzer(bass, trackPath.toString()).use { analyzer ->
                    sampleRate = analyzer.sampleRate
                    var position = 0L
                    var last = System.nanoTime()
                    var lastAutoAdjust = last
                    while (running.get()) {
                        val playing = playingProvider?.invoke() ?: true
                        if (playing) {
                            val analyzeStarted = System.nanoTime()
                            val frame = analyzer.analyze(position)
                            val analyzeNs = System.nanoTime() - analyzeStarted
                            analyzeMs = if (analyzeMs <= 0f) analyzeNs / 1_000_000f
                            else analyzeMs * 0.9f + (analyzeNs / 1_000_000f) * 0.1f
                            var hasFieldSignal = false
                            if (frame != null) {
                                targetBands.set(frame.bands)
                                peakHz = frame.peakHz
                                leftPeak = frame.leftPeak
                                rightPeak = frame.rightPeak
                                leftRms = frame.leftRms
                                rightRms = frame.rightRms
                                stereo = frame.stereo
                                correlation = frame.correlation
                                stereoWidth = frame.stereoWidth
                                hasFieldSignal = frame.field.any { kotlin.math.abs(it) > 0.002f }
                                if (hasFieldSignal) field = frame.field
                            }
                            val now = System.nanoTime()
                            updateHold(now)
                            val delta = now - last
                            if (hasFieldSignal) {
                                fieldOpacity = 1f
                            } else {
                                fieldOpacity = max(0f, fieldOpacity - (delta / 1_000_000_000f / 0.55f))
                            }
                            position = positionProvider?.invoke()
                                ?: (position + (delta / 1_000_000L).coerceAtLeast(1L))
                            last = now
                        } else {
                            // Paused: do not keep analyzing a frozen playback
                            // position; that made bass/spectrum look "stuck".
                            peakHz = 0
                            leftPeak = 0f
                            rightPeak = 0f
                            leftRms = 0f
                            rightRms = 0f
                            correlation = 0f
                            stereoWidth = 0f
                            field = FloatArray(0)
                            fieldOpacity = 0f
                            updateHold(System.nanoTime())
                        }
                        val now = System.nanoTime()
                        val delta = now - last
                        last = now
                        if (delta > 0) {
                            val measured = 1_000_000_000f / delta
                            fpsEma = if (fpsEma <= 0f) measured else fpsEma * 0.9f + measured * 0.1f
                            if (now - fpsPublishAt >= 500_000_000L) {
                                actualFps = fpsEma
                                fpsPublishAt = now
                            }
                        }
                        if (mode == FrameMode.AUTO && now - lastAutoAdjust > 2_000_000_000L && fpsEma > 0f) {
                            lastAutoAdjust = now
                            targetFps = ((targetFps + fpsEma) / 2f).toInt().coerceIn(30, displayHz)
                        }
                        val interval = 1_000_000_000L / activeFps.coerceAtLeast(1)
                        if (!waitUntil(now + interval)) break
                    }
                }
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
            } catch (t: Throwable) {
                if (running.get()) error = t.message ?: t.javaClass.simpleName
            } finally {
                running.set(false)
            }
        }, "SpectrumPreview").apply {
            isDaemon = true
            priority = Thread.NORM_PRIORITY + 1
        }
        worker!!.start()
        displayWorker = Thread({
            try {
                // Start from silence. A new controller must not paste the
                // current FFT frame in at full height on the first display tick.
                var display = FloatArray(SpectrumLayout.POINTS)
                var gain = 0f
                var next = System.nanoTime()
                while (running.get()) {
                    val interval = 1_000_000_000L / activeFps.coerceAtLeast(1)
                    next += interval
                    if (!waitUntil(next)) break
                    val dt = interval / 1_000_000_000f
                    val playing = playingProvider?.invoke() ?: true
                    val target = targetBands.get()
                    if (playing) {
                        gain = (gain + dt / 0.22f).coerceAtMost(1f)
                        if (target != null) {
                            if (display.size != target.size) display = FloatArray(target.size)
                            for (i in display.indices) {
                                display[i] += (target.getOrElse(i) { 0f } - display[i]) * 0.45f
                            }
                        }
                    } else {
                        // 550 ms release: the whole spectrum, including bass,
                        // fades out instead of snapping to zero in one frame.
                        gain = (gain - dt / 0.55f).coerceAtLeast(0f)
                    }
                    bands = FloatArray(display.size) { display[it] * gain }
                }
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
            } catch (t: Throwable) {
                if (running.get()) error = t.message ?: t.javaClass.simpleName
            }
        }, "SpectrumDisplay").apply {
            isDaemon = true
            priority = Thread.NORM_PRIORITY + 1
        }
        displayWorker!!.start()
    }

    /**
     * Waits for [deadline]. parkNanos overshoots badly on Windows (the shell timer quantum
     * is ~15.6 ms, which dropped the frame rate to 16), so the coarse part uses sleep() and
     * only the last couple of milliseconds spin. That is a fraction of the old 4 ms-per-frame
     * busy wait while keeping 60 fps.
     */
    private fun waitUntil(deadline: Long): Boolean {
        while (running.get()) {
            val remaining = deadline - System.nanoTime()
            if (remaining <= 0L) return true
            if (remaining > SPIN_WINDOW_NS) {
                Thread.sleep((remaining - SPIN_WINDOW_NS) / 1_000_000L)
            } else {
                Thread.onSpinWait()
            }
        }
        return false
    }

    private fun updateHold(now: Long) {
        val dt = 1f / 60f
        val decay = (dev.sakana.spw.prism.settings.SpectrumSettings.peakDecayDbPerSec / 60f / 96f)
        if (leftPeak >= leftHold || now - holdAtL > holdWindowNs) {
            leftHold = leftPeak
            holdAtL = now
        } else {
            leftHold = max(0f, leftHold - dt * decay)
        }
        if (rightPeak >= rightHold || now - holdAtR > holdWindowNs) {
            rightHold = rightPeak
            holdAtR = now
        } else {
            rightHold = max(0f, rightHold - dt * decay)
        }
    }

    fun stop() {
        running.set(false)
        worker?.interrupt()
        worker = null
        displayWorker?.interrupt()
        displayWorker = null
    }
}

fun main(args: Array<String>) {
    val path = args.firstOrNull()?.let(Path::of)
        ?: Path.of(System.getProperty("user.home"), "Music", "39.flac")
    check(Files.exists(path)) { "audio file not found: $path" }
    application {
        val controller = SpectrumController(path)
        Window(
            onCloseRequest = ::exitApplication,
            title = "Sakana Prism · Preview",
            state = rememberWindowState(width = 1180.dp, height = 760.dp),
        ) {
            SpectrumDesign(controller)
        }
    }
}
