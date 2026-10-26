@file:OptIn(androidx.compose.runtime.InternalComposeApi::class)

package dev.sakana.spw.prism.host

import java.lang.invoke.MethodType
import java.lang.reflect.Field
import java.lang.reflect.Method
import java.lang.reflect.Modifier

/** Reflected host members are scoped to one plugin enable session. */
class HostUiBridge(val targets: HostUiTargets, private val loader: ClassLoader) {
    private val preparationWarnings = mutableListOf<String>()
    val warnings: List<String> get() = targets.warnings + preparationWarnings

    private fun <T> optional(role: String, prepare: () -> T): T? = runCatching(prepare)
        .onFailure { preparationWarnings += "$role reflection: ${it.message}" }.getOrNull()

    fun method(key: HostMethod): Method {
        val signature = MethodType.fromMethodDescriptorString(key.descriptor, loader)
        return Class.forName(key.className, false, loader)
            .getDeclaredMethod(key.name, *signature.parameterArray()).also {
                check(it.returnType == signature.returnType() && it.trySetAccessible()) {
                    "Host method unavailable: $key"
                }
            }
    }

    fun field(key: HostField): Field = Class.forName(key.owner.replace('/', '.'), false, loader)
        .getDeclaredField(key.name).also {
            check(it.type.descriptorString() == key.descriptor && it.trySetAccessible()) {
                "Host field unavailable: $key"
            }
        }

    val layer = method(targets.layer.target).also { check(Modifier.isStatic(it.modifiers)) }

    val caption = targets.caption?.let { key ->
        optional("caption") { method(key.control).also { check(Modifier.isStatic(it.modifiers)) } }
    }
}

object HostUiRuntime {
    @Volatile var current: HostUiBridge? = null
        private set

    fun require(): HostUiBridge = checkNotNull(current) { "Host UI discovery is not ready" }

    fun activate(bridge: HostUiBridge): AutoCloseable {
        check(current == null) { "A host UI session is already active" }
        current = bridge
        return AutoCloseable { if (current === bridge) current = null }
    }
}
