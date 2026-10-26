package dev.sakana.spw.prism.host

import java.awt.AWTEvent
import java.awt.Rectangle
import java.awt.Toolkit
import java.awt.event.AWTEventListener
import java.awt.event.MouseEvent
import java.util.concurrent.atomic.AtomicReference

/**
 * Host-side plugins have no shared window manager. As a compatibility layer,
 * a click outside our surface dismisses it before the host activates another
 * plugin. This is deliberately a yield, not a fake z-index.
 */
internal object SpectrumOutsideClickGate {
    private val bounds = AtomicReference<Rectangle?>(null)
    private val titlebarButtonBounds = AtomicReference<Rectangle?>(null)
    private var listener: AWTEventListener? = null

    fun install(): AutoCloseable {
        val toolkit = Toolkit.getDefaultToolkit()
        val gate = AWTEventListener { event: AWTEvent ->
            if (!SpectrumHostState.visible) return@AWTEventListener
            if (event !is MouseEvent || event.id != MouseEvent.MOUSE_PRESSED) return@AWTEventListener
            val surface = bounds.get() ?: return@AWTEventListener
            val click = event.locationOnScreen
            if (surface.contains(click)) return@AWTEventListener
            // The titlebar entry is outside the page bounds. Ignore its own
            // click so the entry can open the page without a close/open flash.
            val entry = titlebarButtonBounds.get()
            if (entry != null && entry.contains(click)) return@AWTEventListener
            // Native minimize / maximize / close live to the right of our
            // caption entry. They are outside the page, but must not dismiss it.
            val controlsLeft = entry?.let { it.x + it.width + 2 }
                ?: (surface.x + surface.width - 220)
            if (click.y < surface.y && click.x >= controlsLeft) return@AWTEventListener
            SpectrumHostState.close()
        }
        toolkit.addAWTEventListener(gate, AWTEvent.MOUSE_EVENT_MASK)
        listener = gate
        return AutoCloseable {
            toolkit.removeAWTEventListener(gate)
            if (listener === gate) listener = null
        }
    }

    fun updateBounds(surface: Rectangle) {
        bounds.set(surface)
    }

    fun updateTitlebarButton(button: Rectangle) {
        // Expand a little to cover the host button's inner padding.
        titlebarButtonBounds.set(
            Rectangle(button.x - 10, button.y - 8, button.width + 20, button.height + 16),
        )
    }
}
