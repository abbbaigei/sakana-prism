package dev.sakana.spw.prism.tools

import java.lang.foreign.Arena
import java.lang.foreign.FunctionDescriptor
import java.lang.foreign.Linker
import java.lang.foreign.MemorySegment
import java.lang.foreign.SymbolLookup
import java.lang.foreign.ValueLayout.ADDRESS
import java.lang.foreign.ValueLayout.JAVA_BYTE
import java.lang.foreign.ValueLayout.JAVA_FLOAT
import java.lang.foreign.ValueLayout.JAVA_INT
import java.lang.foreign.ValueLayout.JAVA_SHORT
import java.lang.invoke.MethodHandle

/**
 * Feasibility probe for a DAC plugin: read and set the Windows endpoint volume through
 * Core Audio.
 *
 * Uses the JDK 25 foreign-function API instead of JNA. JNA's COM helper adds an unexpected
 * offset to the vtable index (slot 4 dispatched GetDevice rather than
 * GetDefaultAudioEndpoint) and writing through the wrong slot corrupted memory, so the raw
 * vtable is walked here instead.
 */
private val arena: Arena = Arena.ofAuto()
private val linker: Linker = Linker.nativeLinker()
private val ole32: SymbolLookup = SymbolLookup.libraryLookup("ole32", arena)

private const val CLSCTX_ALL = 0x17
private const val STGM_READ = 0x0

private const val CLSID_MMDEVICE_ENUMERATOR = "{BCDE0395-E52F-467C-8E3D-C4579291692E}"
private const val IID_IMMDEVICE_ENUMERATOR = "{A95664D2-9614-4F35-A746-DE8DB63617E6}"
private const val IID_IAUDIO_ENDPOINT_VOLUME = "{5CDF2C82-841E-4546-9722-0CF74078229A}"

// IUnknown
private const val VT_RELEASE = 2
// IMMDeviceEnumerator
private const val VT_ENUM_AUDIO_ENDPOINTS = 3
private const val VT_GET_DEFAULT_ENDPOINT = 4
// IMMDevice
private const val VT_DEVICE_ACTIVATE = 3
private const val VT_DEVICE_OPEN_PROPERTY_STORE = 4
private const val VT_DEVICE_GET_ID = 5
// IPropertyStore
private const val VT_STORE_GET_VALUE = 5
// IAudioEndpointVolume
private const val VT_VOLUME_CHANNEL_COUNT = 5
private const val VT_VOLUME_SET_SCALAR = 7
private const val VT_VOLUME_GET_SCALAR = 9
private const val VT_VOLUME_GET_MUTE = 15

private const val VT_LPWSTR = 31
private const val PKEY_DEVICE_FRIENDLY_NAME_FMTID = "{A45C254E-DF1C-4EFD-8020-67D146A850E0}"
private const val PKEY_DEVICE_FRIENDLY_NAME_PID = 14

private fun bind(name: String, descriptor: FunctionDescriptor): MethodHandle =
    linker.downcallHandle(
        ole32.find(name).orElseThrow { IllegalStateException("ole32!$name missing") },
        descriptor,
    )

private val coInitializeEx = bind("CoInitializeEx", FunctionDescriptor.of(JAVA_INT, ADDRESS, JAVA_INT))
private val coUninitialize = bind("CoUninitialize", FunctionDescriptor.ofVoid())
private val coCreateInstance = bind(
    "CoCreateInstance",
    FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS, JAVA_INT, ADDRESS, ADDRESS),
)
private val coTaskMemFree = bind("CoTaskMemFree", FunctionDescriptor.ofVoid(ADDRESS))
private val propVariantClear = bind("PropVariantClear", FunctionDescriptor.of(JAVA_INT, ADDRESS))

private fun guid(value: String): MemorySegment {
    val hex = value.replace("{", "").replace("}", "").replace("-", "")
    require(hex.length == 32) { "bad GUID: $value" }
    val segment = arena.allocate(16)
    segment.set(JAVA_INT, 0, hex.substring(0, 8).toLong(16).toInt())
    segment.set(JAVA_SHORT, 4, hex.substring(8, 12).toLong(16).toShort())
    segment.set(JAVA_SHORT, 6, hex.substring(12, 16).toLong(16).toShort())
    for (i in 0 until 8) {
        segment.set(JAVA_BYTE, (8 + i).toLong(), hex.substring(16 + i * 2, 18 + i * 2).toInt(16).toByte())
    }
    return segment
}

/** Calls vtable [index] on a COM object, passing the interface pointer as the `this` argument. */
private fun vtableCall(
    instance: MemorySegment,
    index: Int,
    descriptor: FunctionDescriptor,
    vararg args: Any?,
): Int {
    // Both the object pointer and the vtable pointer come out of memory with zero length.
    val vtable = instance.get(ADDRESS, 0L).reinterpret(1024)
    val entry = vtable.get(ADDRESS, index * 8L)
    val handle = linker.downcallHandle(entry, descriptor)
    return handle.invokeWithArguments(listOf(instance, *args)) as Int
}

private fun release(instance: MemorySegment?) {
    if (instance == null || instance.address() == 0L) return
    runCatching { vtableCall(instance, VT_RELEASE, FunctionDescriptor.of(JAVA_INT, ADDRESS)) }
}

private fun hr(code: Int): String = if (code >= 0) "ok" else "0x%08X".format(code)

private fun outPointer(slot: MemorySegment): MemorySegment? =
    // A pointer read from memory has zero length; rebind it so the vtable slots are readable.
    slot.get(ADDRESS, 0L).takeIf { it.address() != 0L }?.reinterpret(1024)

fun main() {
    println("CoInitializeEx: ${hr(coInitializeEx.invokeExact(MemorySegment.NULL, 0) as Int)}")

    val enumeratorSlot = arena.allocate(ADDRESS)
    val createHr = coCreateInstance.invokeExact(
        guid(CLSID_MMDEVICE_ENUMERATOR),
        MemorySegment.NULL,
        CLSCTX_ALL,
        guid(IID_IMMDEVICE_ENUMERATOR),
        enumeratorSlot,
    ) as Int
    println("CoCreateInstance: ${hr(createHr)}")
    if (createHr < 0) return
    val enumerator = outPointer(enumeratorSlot) ?: return

    val collectionSlot = arena.allocate(ADDRESS)
    val enumHr = vtableCall(
        enumerator,
        VT_ENUM_AUDIO_ENDPOINTS,
        FunctionDescriptor.of(JAVA_INT, ADDRESS, JAVA_INT, JAVA_INT, ADDRESS),
        0,
        0xF,
        collectionSlot,
    )
    println("EnumAudioEndpoints: ${hr(enumHr)}")
    outPointer(collectionSlot)?.let { release(it) }

    val deviceSlot = arena.allocate(ADDRESS)
    val deviceHr = vtableCall(
        enumerator,
        VT_GET_DEFAULT_ENDPOINT,
        FunctionDescriptor.of(JAVA_INT, ADDRESS, JAVA_INT, JAVA_INT, ADDRESS),
        0, // eRender
        0, // eConsole
        deviceSlot,
    )
    println("GetDefaultAudioEndpoint: ${hr(deviceHr)}")
    val device = outPointer(deviceSlot)
    if (deviceHr < 0 || device == null) {
        release(enumerator)
        return
    }

    println("device id: ${deviceId(device) ?: "<unreadable>"}")
    println("friendly name: ${friendlyName(device) ?: "<unreadable>"}")

    val volumeSlot = arena.allocate(ADDRESS)
    val activateHr = vtableCall(
        device,
        VT_DEVICE_ACTIVATE,
        FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS, JAVA_INT, ADDRESS, ADDRESS),
        guid(IID_IAUDIO_ENDPOINT_VOLUME),
        CLSCTX_ALL,
        MemorySegment.NULL,
        volumeSlot,
    )
    println("Activate(IAudioEndpointVolume): ${hr(activateHr)}")
    val volume = outPointer(volumeSlot)
    if (activateHr < 0 || volume == null) {
        release(device)
        release(enumerator)
        return
    }

    val channels = arena.allocate(JAVA_INT)
    vtableCall(volume, VT_VOLUME_CHANNEL_COUNT, FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS), channels)
    println("channels: ${channels.get(JAVA_INT, 0L)}")

    val scalar = arena.allocate(JAVA_FLOAT)
    vtableCall(volume, VT_VOLUME_GET_SCALAR, FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS), scalar)
    println("master volume: ${(scalar.get(JAVA_FLOAT, 0L) * 100).toInt()}%")

    val mute = arena.allocate(JAVA_INT)
    vtableCall(volume, VT_VOLUME_GET_MUTE, FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS), mute)
    println("mute: ${mute.get(JAVA_INT, 0L) != 0}")

    val original = scalar.get(JAVA_FLOAT, 0L)
    val target = if (original > 0.5f) 0.35f else 0.65f
    val setHr = vtableCall(
        volume,
        VT_VOLUME_SET_SCALAR,
        FunctionDescriptor.of(JAVA_INT, ADDRESS, JAVA_FLOAT, ADDRESS),
        target,
        MemorySegment.NULL,
    )
    val readBack = arena.allocate(JAVA_FLOAT)
    vtableCall(volume, VT_VOLUME_GET_SCALAR, FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS), readBack)
    println("set ${(target * 100).toInt()}% -> ${hr(setHr)}, read back ${(readBack.get(JAVA_FLOAT, 0L) * 100).toInt()}%")

    vtableCall(
        volume,
        VT_VOLUME_SET_SCALAR,
        FunctionDescriptor.of(JAVA_INT, ADDRESS, JAVA_FLOAT, ADDRESS),
        original,
        MemorySegment.NULL,
    )
    val restored = arena.allocate(JAVA_FLOAT)
    vtableCall(volume, VT_VOLUME_GET_SCALAR, FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS), restored)
    println("restored to ${(restored.get(JAVA_FLOAT, 0L) * 100).toInt()}%")

    release(volume)
    release(device)
    release(enumerator)
    coUninitialize.invokeExact()
    println("done")
}

private fun deviceId(device: MemorySegment): String? {
    val idSlot = arena.allocate(ADDRESS)
    if (vtableCall(device, VT_DEVICE_GET_ID, FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS), idSlot) < 0) {
        return null
    }
    val pointer = outPointer(idSlot) ?: return null
    val value = pointer.reinterpret(Long.MAX_VALUE).getString(0, Charsets.UTF_16LE)
    coTaskMemFree.invokeExact(pointer)
    return value
}

private fun friendlyName(device: MemorySegment): String? {
    val storeSlot = arena.allocate(ADDRESS)
    val openHr = vtableCall(
        device,
        VT_DEVICE_OPEN_PROPERTY_STORE,
        FunctionDescriptor.of(JAVA_INT, ADDRESS, JAVA_INT, ADDRESS),
        STGM_READ,
        storeSlot,
    )
    if (openHr < 0) return null
    val store = outPointer(storeSlot) ?: return null

    val key = arena.allocate(24)
    MemorySegment.copy(guid(PKEY_DEVICE_FRIENDLY_NAME_FMTID), 0L, key, 0L, 16L)
    key.set(JAVA_INT, 16L, PKEY_DEVICE_FRIENDLY_NAME_PID)

    val variant = arena.allocate(32)
    var name: String? = null
    if (vtableCall(
            store,
            VT_STORE_GET_VALUE,
            FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS, ADDRESS),
            key,
            variant,
        ) >= 0
    ) {
        if (variant.get(JAVA_SHORT, 0L).toInt() == VT_LPWSTR) {
            name = variant.get(ADDRESS, 8L).reinterpret(Long.MAX_VALUE).getString(0, Charsets.UTF_16LE)
        }
        propVariantClear.invokeExact(variant) as Int
    }
    release(store)
    return name
}
