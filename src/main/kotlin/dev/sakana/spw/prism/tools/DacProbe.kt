package dev.sakana.spw.prism.tools

import dev.sakana.spw.prism.dac.CoreAudio

/** Lists render endpoints through [CoreAudio] and round-trips the default device volume. */
fun main() {
    val devices = CoreAudio.devices()
    println("devices: ${devices.size}")
    devices.forEach { device ->
        val star = if (device.isDefault) "*" else " "
        val fmt = device.formatLabel ?: "-"
        println(
            "$star ${device.name} | ${device.state} | $fmt | ${(device.volume * 100).toInt()}% | mute=${device.muted}",
        )
    }
    val target = devices.firstOrNull { it.isDefault } ?: devices.firstOrNull { it.state.name == "ACTIVE" }
    if (target == null) {
        println("no active device")
        return
    }
    val original = target.volume
    println("set 0.42 -> ${CoreAudio.setVolume(target.id, 0.42f)}")
    val after = CoreAudio.devices().firstOrNull { it.id == target.id }
    println("read back -> ${after?.let { (it.volume * 100).toInt() }}%")
    println("restore -> ${CoreAudio.setVolume(target.id, original)}")
    println("mute true -> ${CoreAudio.setMute(target.id, true)}")
    println("mute false -> ${CoreAudio.setMute(target.id, false)}")
}
