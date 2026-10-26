@file:OptIn(com.xuncorp.spw.workshop.api.UnstableSpwWorkshopApi::class)

package dev.sakana.spw.prism.host

import com.xuncorp.spw.workshop.api.hook.HookRegistrar
import com.xuncorp.spw.workshop.api.hook.MethodHook
import com.xuncorp.spw.workshop.api.hook.MethodHookParam

/**
 * The host sidebar (Songs, Albums, Settings, ...) uses NavBackStack actions,
 * not media item changes. Close the spectrum page before the destination
 * renders, so the exit animation can run over the host transition.
 */
object SpectrumNavigationHook {
    fun register(registrar: HookRegistrar): List<AutoCloseable> {
        val navigation = HostUiRuntime.require().targets.navigation
        val actions = listOf(navigation.navigate, navigation.replace, navigation.forward, navigation.back)
            .distinctBy { it.owner to it.name to it.descriptor }
        return actions.map { action ->
            registrar.hookMethod(
                action.className,
                action.name,
                action.parameters,
                object : MethodHook(50) {
                    override fun before(param: MethodHookParam) {
                        SpectrumHostState.close()
                    }
                },
            )
        }
    }
}
