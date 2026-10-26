@file:OptIn(
    androidx.compose.runtime.InternalComposeApi::class,
    androidx.compose.ui.ExperimentalComposeUiApi::class,
    com.xuncorp.spw.workshop.api.UnstableSpwWorkshopApi::class,
)

package dev.sakana.spw.prism.host

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.currentComposer
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionOnScreen
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.loadSvgPainter
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.xuncorp.spw.workshop.api.hook.ComposableHook
import com.xuncorp.spw.workshop.api.hook.UiHookCall
import java.awt.Rectangle

/** Native caption control, placed immediately before the host's Quick button. */
object SpectrumTitlebarEntry {
    const val ICON_RESOURCE = "/icons/prism-entry.svg"

    fun register(addHook: (String, String, String, List<String>, ComposableHook) -> AutoCloseable): AutoCloseable {
        check(HostCaptionButton.iconBytes.isNotEmpty())
        val bridge = HostUiRuntime.require()
        checkNotNull(bridge.caption) { "Native caption control unavailable" }
        val site = checkNotNull(bridge.targets.caption) { "Titlebar discovery unavailable" }.entry
        return addHook(site.target.className, site.target.name, site.callerClass, emptyList(),
            object : ComposableHook() {
                @Composable
                override fun Content(call: UiHookCall) {
                    SpectrumTitlebarButton()
                }
            })
    }
}

@Composable
fun SpectrumTitlebarButton(onClick: () -> Unit = SpectrumHostState::open) {
    val density = LocalDensity.current
    val painter = remember(density) {
        HostCaptionButton.iconBytes.inputStream().use { loadSvgPainter(it, density) }
    }
    val icon: @Composable BoxScope.() -> Unit = {
        Box(
            Modifier.align(Alignment.Center).size(20.dp)
                .onGloballyPositioned { coordinates ->
                    val topLeft = coordinates.positionOnScreen()
                    SpectrumOutsideClickGate.updateTitlebarButton(
                        Rectangle(topLeft.x.toInt(), topLeft.y.toInt(), coordinates.size.width, coordinates.size.height),
                    )
                }
                .drawBehind { with(painter) { draw(size) } },
        )
    }
    val method = HostUiRuntime.current?.caption ?: return
    method.invoke(
        null, onClick, "Sakana Prism",
        Modifier.semantics { contentDescription = "Sakana Prism" },
        true, null, 0f, icon, currentComposer, 0, 32,
    )
}

private object HostCaptionButton {
    val iconBytes: ByteArray by lazy {
        requireNotNull(SpectrumTitlebarEntry::class.java.getResourceAsStream(SpectrumTitlebarEntry.ICON_RESOURCE)) {
            "Missing titlebar spectrum icon"
        }.use { it.readBytes() }
    }
}
