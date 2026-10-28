@file:OptIn(
    androidx.compose.runtime.InternalComposeApi::class,
    com.xuncorp.spw.workshop.api.UnstableSpwWorkshopApi::class,
)

package dev.sakana.spw.prism.host

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Composer
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.currentComposer
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionOnScreen
import java.awt.Rectangle
import androidx.compose.ui.zIndex
import dev.sakana.spw.prism.i18n.Strings
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.xuncorp.spw.workshop.api.hook.ComposableHook
import com.xuncorp.spw.workshop.api.hook.UiHookCall
import dev.sakana.spw.prism.preview.SpectrumController
import dev.sakana.spw.prism.ui.SpectrumDesign
import java.lang.reflect.Method
import java.nio.file.Path

/** Wrap the Salt Layer so the spectrum can cover host content without replacing AppNavigation. */
object SpectrumPageHook {
    fun register(addHook: (String, String, String, List<String>, ComposableHook) -> AutoCloseable): AutoCloseable {
        val bridge = HostUiRuntime.require()
        val site = bridge.targets.layer
        val layer = HostPageLayer(bridge.layer)
        return addHook(
            site.target.className, site.target.name, site.callerClass,
            listOf("androidx.compose.ui.Modifier", "boolean", "kotlin.jvm.functions.Function3"),
            object : ComposableHook() {
                @Composable
                override fun Content(call: UiHookCall) {
                    val modifier = call.arguments[0] as Modifier
                    val decoration = call.arguments[1] as Boolean
                    val original = requireNotNull(call.arguments[2])
                    val content: @Composable BoxScope.() -> Unit = {
                        val scope = this
                        layer.renderContent(original, scope, currentComposer)
                        // Only compose the spectrum page while it is actually open. The old
                        // AnimatedVisibility kept the page (and therefore the analysis
                        // threads) alive until a fade-out finished, which never happens
                        // once the host window stops producing frames.
                        if (SpectrumHostState.visible) {
                            SpectrumPageView()
                        }
                    }
                    layer.render(modifier, decoration, content, currentComposer)
                }
            },
        )
    }
}

private class HostPageLayer(private val method: Method) {
    private val loader = SpectrumPageHook::class.java.classLoader
    private val invokeContent: Method by lazy {
        Class.forName("kotlin.jvm.functions.Function3", false, loader)
            .getMethod("invoke", Any::class.java, Any::class.java, Any::class.java)
    }

    fun render(modifier: Modifier, decoration: Boolean, content: Any, composer: Composer) {
        method.invoke(null, modifier, decoration, content, composer, 0, 0)
    }

    fun renderContent(content: Any, scope: BoxScope, composer: Composer) {
        invokeContent.invoke(content, scope, composer, 0)
    }
}

@Composable
private fun SpectrumPageView() {
    LaunchedEffect(Unit) {
        while (true) {
            SpectrumPlayback.refreshMedia()
            kotlinx.coroutines.delay(750)
        }
    }
    val source = SpectrumPlayback.path
    var activeSource by remember { mutableStateOf(source) }
    LaunchedEffect(source) {
        if (source != null && source != activeSource) {
            // Song changes re-open the decoder on the same page; only host
            // navigation (sidebar) closes the spectrum workspace.
            kotlinx.coroutines.delay(350)
            activeSource = source
        }
    }
    Box(
        Modifier.fillMaxSize()
            .background(Color(0xFF0D121A))
            .onGloballyPositioned { coordinates ->
                val topLeft = coordinates.positionOnScreen()
                SpectrumOutsideClickGate.updateBounds(
                    Rectangle(topLeft.x.toInt(), topLeft.y.toInt(), coordinates.size.width, coordinates.size.height),
                )
            },
    ) {
        if (activeSource == null) {
            MissingTrack()
        } else {
            val controller = remember(activeSource) {
                SpectrumController(Path.of(activeSource), { SpectrumPlayback.positionMs }, { SpectrumPlayback.isPlaying })
            }
            SpectrumDesign(controller)
        }
        // Always-on-screen escape hatch: the page header can be clipped by
        // narrow host widths, so this button must not participate in its Row.
        Text(
            Strings["btn.close"],
            modifier = Modifier.align(Alignment.TopEnd).padding(12.dp).zIndex(20f)
                .border(1.dp, Color(0xFF3B4A5C), RoundedCornerShape(50))
                .background(Color(0xE60D121A), RoundedCornerShape(50))
                .clickable { SpectrumHostState.close() }
                .padding(horizontal = 12.dp, vertical = 7.dp),
            color = Color(0xFFC6D2DF),
            fontSize = 10.sp,
        )
    }
}

@Composable
private fun MissingTrack() {
    Box(Modifier.fillMaxSize()) {
        Text(
            Strings["btn.close"],
            modifier = Modifier.align(Alignment.TopEnd).padding(20.dp).clickable { SpectrumHostState.close() },
            color = Color(0xFF7D8A9A),
            fontSize = 10.sp,
        )
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text("没有可分析的本地音频", color = Color(0xFF7D8A9A), fontSize = 12.sp)
        }
    }
}
