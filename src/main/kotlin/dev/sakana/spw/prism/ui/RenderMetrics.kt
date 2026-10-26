package dev.sakana.spw.prism.ui

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue

/**
 * Measured render intervals, not the analysis-loop rate. publish() is throttled
 * so observing these values cannot itself cause a recomposition every frame.
 */
class RenderMetrics {
    var fps by mutableFloatStateOf(0f)
        private set
    var p95Ms by mutableFloatStateOf(0f)
        private set
    var p99Ms by mutableFloatStateOf(0f)
        private set
    var drawMs by mutableFloatStateOf(0f)
        private set

    private val frameNs = ArrayDeque<Long>()
    private val drawSamples = ArrayDeque<Long>()
    private var publishedAt = 0L
    private var lastFrameAt = 0L

    fun recordFrame(now: Long = System.nanoTime()) {
        if (lastFrameAt == 0L) {
            lastFrameAt = now
            return
        }
        val intervalNs = now - lastFrameAt
        lastFrameAt = now
        if (intervalNs <= 0L) return
        frameNs.addLast(intervalNs)
        if (frameNs.size > 180) frameNs.removeFirst()
        if (now - publishedAt >= 500_000_000L) {
            publishedAt = now
            val sorted = frameNs.sorted()
            if (sorted.isNotEmpty()) {
                fps = 1_000_000_000f / sorted.average().toFloat()
                p95Ms = sorted[(sorted.size * 0.95).toInt().coerceIn(0, sorted.lastIndex)] / 1_000_000f
                p99Ms = sorted[(sorted.size * 0.99).toInt().coerceIn(0, sorted.lastIndex)] / 1_000_000f
                drawMs = if (drawSamples.isEmpty()) 0f
                else drawSamples.average().toFloat() / 1_000_000f
                drawSamples.clear()
            }
        }
    }

    fun recordDraw(ns: Long) {
        if (ns <= 0L) return
        drawSamples.addLast(ns)
        if (drawSamples.size > 180) drawSamples.removeFirst()
    }
}

