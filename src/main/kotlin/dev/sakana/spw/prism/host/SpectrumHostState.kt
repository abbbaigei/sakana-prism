package dev.sakana.spw.prism.host

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/** Host-composed spectrum page. No separate AWT window. */
object SpectrumHostState {
    var visible by mutableStateOf(false)
        private set

    fun open() { visible = true }
    fun close() { visible = false }
    fun toggle() { visible = !visible }
}
