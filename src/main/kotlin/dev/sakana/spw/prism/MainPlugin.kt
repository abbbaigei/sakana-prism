@file:OptIn(com.xuncorp.spw.workshop.api.UnstableSpwWorkshopApi::class)

package dev.sakana.spw.prism

import com.xuncorp.spw.workshop.api.ActionShortcut
import com.xuncorp.spw.workshop.api.PluginContext
import com.xuncorp.spw.workshop.api.PluginPermission
import com.xuncorp.spw.workshop.api.SpwPlugin
import com.xuncorp.spw.workshop.api.WorkshopApi
import dev.sakana.spw.prism.host.HostUiBridge
import dev.sakana.spw.prism.host.HostUiIntegration
import dev.sakana.spw.prism.host.HostUiRuntime
import dev.sakana.spw.prism.host.SpectrumHostState
import dev.sakana.spw.prism.host.SpectrumNavigationHook
import dev.sakana.spw.prism.host.SpectrumOutsideClickGate
import dev.sakana.spw.prism.host.SpectrumPageHook
import dev.sakana.spw.prism.host.SpectrumPlayback
import dev.sakana.spw.prism.host.SpectrumTitlebarEntry
import dev.sakana.spw.prism.settings.SpectrumSettings
import java.awt.event.KeyEvent
import java.nio.file.Path

class MainPlugin(pluginContext: PluginContext) : SpwPlugin(pluginContext) {
    private var integration: HostUiIntegration? = null
    private var keyBinding: AutoCloseable? = null

    override fun start() {
        SpectrumPlayback.refreshMedia()
        runCatching { SpectrumSettings.attach(WorkshopApi.manager.createConfigManager().getConfig()) }
            .onFailure { println("[Sakana] settings load failed: ${it.message}") }
        if (!WorkshopApi.manager.isPermissionGranted(PluginPermission.CLASS_TRANSFORM)) {
            WorkshopApi.ui.toast("Sakana Prism 页面尚未就绪", WorkshopApi.Ui.ToastType.Warning)
            return
        }
        runCatching {
            if (WorkshopApi.manager.isPermissionGranted(PluginPermission.KEY_BINDINGS)) {
                keyBinding = WorkshopApi.manager.keyBindingManager.register(
                    "spectrum.toggle",
                    "实时频谱",
                    ActionShortcut(KeyEvent.VK_S, ActionShortcut.CONTROL or ActionShortcut.SHIFT),
                    true,
                ) { SpectrumHostState.toggle() }
            }
        }.onFailure { println("[Sakana] key binding failed: ${it.message}") }
        val host = WorkshopApi.instance.javaClass
        integration = HostUiIntegration(
            source = {
                Path.of(
                    checkNotNull(host.protectionDomain.codeSource) {
                        "Host code source unavailable"
                    }.location.toURI(),
                )
            },
            loader = host.classLoader,
            install = ::installHostUi,
            failed = { failure ->
                failure.printStackTrace()
                WorkshopApi.ui.toast("Sakana Prism 页面尚未就绪", WorkshopApi.Ui.ToastType.Warning)
            },
            report = { println("[Sakana] host UI discovery: $it") },
        ).also { it.start() }
    }

    private fun installHostUi(bridge: HostUiBridge): AutoCloseable {
        val handles = mutableListOf<AutoCloseable>()
        fun release() {
            handles.asReversed().forEach { runCatching { it.close() } }
            handles.clear()
        }
        try {
            handles += HostUiRuntime.activate(bridge)
            handles += SpectrumPageHook.register { target, method, caller, parameters, content ->
                WorkshopApi.hookRegistrar.replaceComposable(target, method, caller, parameters, 50, content)
            }
            runCatching {
                handles += SpectrumNavigationHook.register(WorkshopApi.hookRegistrar)
            }.onFailure {
                println("[Sakana] navigation exit hook unavailable: ${it.message}")
            }
            runCatching {
                handles += SpectrumOutsideClickGate.install()
            }.onFailure {
                println("[Sakana] outside-click yield unavailable: ${it.message}")
            }
            runCatching {
                handles += SpectrumTitlebarEntry.register { target, method, caller, parameters, content ->
                    WorkshopApi.hookRegistrar.afterComposable(target, method, caller, parameters, 50, content)
                }
            }.onFailure {
                println("[Sakana] titlebar entry unavailable: ${it.message}")
            }
            WorkshopApi.ui.toast("Sakana Prism 已启用", WorkshopApi.Ui.ToastType.Success)
        } catch (failure: Throwable) {
            release()
            throw failure
        }
        return AutoCloseable(::release)
    }

    override fun stop() {
        SpectrumHostState.close()
        SpectrumSettings.flush()
        keyBinding?.close()
        keyBinding = null
        integration?.close()
        integration = null
    }

    companion object {
        @JvmStatic
        fun openSpectrum() {
            if (HostUiRuntime.current == null) {
                WorkshopApi.ui.toast("Sakana Prism 页面尚未就绪", WorkshopApi.Ui.ToastType.Warning)
            } else {
                SpectrumHostState.open()
            }
        }
    }
}
