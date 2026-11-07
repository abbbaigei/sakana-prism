package dev.sakana.spw.prism.dac

import java.lang.foreign.Arena
import java.lang.foreign.FunctionDescriptor
import java.lang.foreign.Linker
import java.lang.foreign.MemorySegment
import java.lang.foreign.SymbolLookup
import java.lang.foreign.ValueLayout.ADDRESS
import java.lang.foreign.ValueLayout.JAVA_BYTE
import java.lang.foreign.ValueLayout.JAVA_FLOAT
import java.lang.foreign.ValueLayout.JAVA_INT
import java.lang.foreign.ValueLayout.JAVA_INT_UNALIGNED
import java.lang.foreign.ValueLayout.JAVA_SHORT
import java.lang.foreign.ValueLayout.JAVA_SHORT_UNALIGNED
import java.lang.invoke.MethodHandle

/**
 * Windows Core Audio access for the DAC page.
 *
 * Uses the JDK 25 foreign-function API rather than JNA: JNA's COM helper applies an extra
 * offset to the vtable index (slot 4 dispatched GetDevice instead of
 * GetDefaultAudioEndpoint) and writing through the wrong slot corrupts memory.
 *
 * Every call opens and releases its own COM objects, so nothing is cached across a device
 * change or a page reload.
 */
enum class DacState { ACTIVE, DISABLED, NOT_PRESENT, UNPLUGGED }

data class DacDevice(
    val id: String,
    val name: String,
    val state: DacState,
    val isDefault: Boolean,
    val channels: Int,
    val sampleRate: Int,
    val bits: Int,
    val volume: Float,
    val muted: Boolean,
) {
    /** "384 kHz / 32 bit / 2ch", or null when the device has no active format. */
    val formatLabel: String?
        get() = if (sampleRate > 0 && bits > 0) {
            "${formatHz(sampleRate)} / $bits bit / ${channels}ch"
        } else {
            null
        }

    private fun formatHz(hz: Int): String =
        if (hz % 1000 == 0) "${hz / 1000} kHz" else "%.1f kHz".format(hz / 1000.0)
}

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
private const val VT_GET_DEVICE = 5
// IMMDevice
private const val VT_DEVICE_ACTIVATE = 3
private const val VT_DEVICE_OPEN_PROPERTY_STORE = 4
private const val VT_DEVICE_GET_ID = 5
private const val VT_DEVICE_GET_STATE = 6
// IMMDeviceCollection
private const val VT_COLLECTION_GET_COUNT = 3
private const val VT_COLLECTION_ITEM = 4
// IPropertyStore
private const val VT_STORE_GET_VALUE = 5
// IAudioEndpointVolume
private const val VT_VOLUME_SET_SCALAR = 7
private const val VT_VOLUME_GET_SCALAR = 9
private const val VT_VOLUME_SET_MUTE = 14
private const val VT_VOLUME_GET_MUTE = 15

private const val VT_LPWSTR = 31
private const val VT_BLOB = 65

private const val PKEY_DEVICE_FRIENDLY_NAME = "{A45C254E-DF1C-4EFD-8020-67D146A850E0}|14"
private const val PKEY_AUDIOENGINE_DEVICE_FORMAT = "{F19F064D-082C-4E27-BC73-6882A1BB8E4C}|0"

private val arena: Arena = Arena.ofAuto()
private val linker: Linker = Linker.nativeLinker()
private val ole32: SymbolLookup = SymbolLookup.libraryLookup("ole32", arena)

private fun bind(name: String, descriptor: FunctionDescriptor): MethodHandle =
    linker.downcallHandle(
        ole32.find(name).orElseThrow { IllegalStateException("ole32!$name missing") },
        descriptor,
    )

private val coInitializeEx = bind("CoInitializeEx", FunctionDescriptor.of(JAVA_INT, ADDRESS, JAVA_INT))
private val coCreateInstance = bind(
    "CoCreateInstance",
    FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS, JAVA_INT, ADDRESS, ADDRESS),
)
private val coTaskMemFree = bind("CoTaskMemFree", FunctionDescriptor.ofVoid(ADDRESS))
private val propVariantClear = bind("PropVariantClear", FunctionDescriptor.of(JAVA_INT, ADDRESS))

/** COM is apartment-scoped, so initialisation happens once per calling thread. */
private val comReady = ThreadLocal.withInitial {
    runCatching { coInitializeEx.invokeExact(MemorySegment.NULL, 2) as Int } // STA
    true
}

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

private fun vtableCall(
    instance: MemorySegment,
    index: Int,
    descriptor: FunctionDescriptor,
    vararg args: Any?,
): Int {
    // Pointers read out of memory arrive as zero-length segments; rebind before dereferencing.
    val vtable = instance.get(ADDRESS, 0L).reinterpret(1024)
    val entry = vtable.get(ADDRESS, index * 8L)
    val handle = linker.downcallHandle(entry, descriptor)
    return handle.invokeWithArguments(listOf(instance, *args)) as Int
}

private fun release(instance: MemorySegment?) {
    if (instance == null || instance.address() == 0L) return
    runCatching { vtableCall(instance, VT_RELEASE, FunctionDescriptor.of(JAVA_INT, ADDRESS)) }
}

private fun outPointer(slot: MemorySegment): MemorySegment? =
    slot.get(ADDRESS, 0L).takeIf { it.address() != 0L }?.reinterpret(1024)

/** COM strings are UTF-16 and only null-terminated, so the length is unknown up front. */
private fun wideString(segment: MemorySegment): String =
    segment.reinterpret(Long.MAX_VALUE).getString(0, Charsets.UTF_16LE)

object CoreAudio {

    /** All render endpoints, default device first. */
    fun devices(): List<DacDevice> = runCatching {
        comReady.get()
        withEnumerator { enumerator -> readAll(enumerator) } ?: emptyList()
    }.onFailure {
        System.err.println("CoreAudio.devices failed: $it")
        it.printStackTrace()
    }.getOrDefault(emptyList())

    fun setVolume(deviceId: String, volume: Float): Boolean = runCatching {
        comReady.get()
        withDevice(deviceId) { device ->
            withVolume(device) { endpointVolume ->
                vtableCall(
                    endpointVolume,
                    VT_VOLUME_SET_SCALAR,
                    FunctionDescriptor.of(JAVA_INT, ADDRESS, JAVA_FLOAT, ADDRESS),
                    volume.coerceIn(0f, 1f),
                    MemorySegment.NULL,
                ) >= 0
            } ?: false
        } ?: false
    }.getOrDefault(false)

    fun setMute(deviceId: String, muted: Boolean): Boolean = runCatching {
        comReady.get()
        withDevice(deviceId) { device ->
            withVolume(device) { endpointVolume ->
                vtableCall(
                    endpointVolume,
                    VT_VOLUME_SET_MUTE,
                    FunctionDescriptor.of(JAVA_INT, ADDRESS, JAVA_INT, ADDRESS),
                    if (muted) 1 else 0,
                    MemorySegment.NULL,
                ) >= 0
            } ?: false
        } ?: false
    }.getOrDefault(false)

    // ---- internal ---------------------------------------------------------

    private class PointerSlot {
        val memory: MemorySegment = arena.allocate(ADDRESS)
    }

    private fun <T> withEnumerator(block: (MemorySegment) -> T): T? {
        val slot = PointerSlot()
        val hr = coCreateInstance.invokeExact(
            guid(CLSID_MMDEVICE_ENUMERATOR),
            MemorySegment.NULL,
            CLSCTX_ALL,
            guid(IID_IMMDEVICE_ENUMERATOR),
            slot.memory,
        ) as Int
        if (hr < 0) return null
        val enumerator = outPointer(slot.memory) ?: return null
        return try {
            block(enumerator)
        } finally {
            release(enumerator)
        }
    }

    private fun readAll(enumerator: MemorySegment): List<DacDevice> {
        val defaultId = defaultDeviceId(enumerator)
        val collectionSlot = PointerSlot()
        val enumHr = vtableCall(
            enumerator,
            VT_ENUM_AUDIO_ENDPOINTS,
            FunctionDescriptor.of(JAVA_INT, ADDRESS, JAVA_INT, JAVA_INT, ADDRESS),
            0, // eRender
            0xF, // DEVICE_STATE_ALL
            collectionSlot.memory,
        )
        if (enumHr < 0) return emptyList()
        val collection = outPointer(collectionSlot.memory) ?: return emptyList()
        return try {
            val countSlot = arena.allocate(JAVA_INT)
            vtableCall(
                collection,
                VT_COLLECTION_GET_COUNT,
                FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS),
                countSlot,
            )
            val count = countSlot.get(JAVA_INT, 0L)
            (0 until count).mapNotNull { index ->
                val itemSlot = PointerSlot()
                val hr = vtableCall(
                    collection,
                    VT_COLLECTION_ITEM,
                    FunctionDescriptor.of(JAVA_INT, ADDRESS, JAVA_INT, ADDRESS),
                    index,
                    itemSlot.memory,
                )
                if (hr < 0) return@mapNotNull null
                val device = outPointer(itemSlot.memory) ?: return@mapNotNull null
                try {
                    readDevice(device, defaultId)
                } finally {
                    release(device)
                }
            }.sortedWith(compareByDescending<DacDevice> { it.isDefault }.thenBy { it.state.ordinal })
        } finally {
            release(collection)
        }
    }

    private fun defaultDeviceId(enumerator: MemorySegment): String? {
        val slot = PointerSlot()
        if (vtableCall(
                enumerator,
                VT_GET_DEFAULT_ENDPOINT,
                FunctionDescriptor.of(JAVA_INT, ADDRESS, JAVA_INT, JAVA_INT, ADDRESS),
                0,
                0,
                slot.memory,
            ) < 0
        ) {
            return null
        }
        val device = outPointer(slot.memory) ?: return null
        return try {
            readId(device)
        } finally {
            release(device)
        }
    }

    private fun <T> withDevice(deviceId: String, block: (MemorySegment) -> T): T? =
        withEnumerator { enumerator ->
            val slot = PointerSlot()
            val id = arena.allocateFrom(deviceId, Charsets.UTF_16LE)
            if (vtableCall(
                    enumerator,
                    VT_GET_DEVICE,
                    FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS, ADDRESS),
                    id,
                    slot.memory,
                ) < 0
            ) {
                return@withEnumerator null
            }
            val device = outPointer(slot.memory) ?: return@withEnumerator null
            try {
                block(device)
            } finally {
                release(device)
            }
        }

    private fun <T> withVolume(device: MemorySegment, block: (MemorySegment) -> T): T? {
        val slot = PointerSlot()
        if (vtableCall(
                device,
                VT_DEVICE_ACTIVATE,
                FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS, JAVA_INT, ADDRESS, ADDRESS),
                guid(IID_IAUDIO_ENDPOINT_VOLUME),
                CLSCTX_ALL,
                MemorySegment.NULL,
                slot.memory,
            ) < 0
        ) {
            return null
        }
        val endpointVolume = outPointer(slot.memory) ?: return null
        return try {
            block(endpointVolume)
        } finally {
            release(endpointVolume)
        }
    }

    private fun readId(device: MemorySegment): String? {
        val slot = PointerSlot()
        if (vtableCall(
                device,
                VT_DEVICE_GET_ID,
                FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS),
                slot.memory,
            ) < 0
        ) {
            return null
        }
        val pointer = outPointer(slot.memory) ?: return null
        return try {
            wideString(pointer)
        } finally {
            coTaskMemFree.invokeExact(pointer)
        }
    }

    private fun readDevice(device: MemorySegment, defaultId: String?): DacDevice? {
        val id = readId(device) ?: return null
        val stateSlot = arena.allocate(JAVA_INT)
        vtableCall(
            device,
            VT_DEVICE_GET_STATE,
            FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS),
            stateSlot,
        )
        val state = when (stateSlot.get(JAVA_INT, 0L)) {
            0x1 -> DacState.ACTIVE
            0x2 -> DacState.DISABLED
            0x8 -> DacState.UNPLUGGED
            else -> DacState.NOT_PRESENT
        }
        val format = readFormat(device)
        val level = withVolume(device) { readLevel(it) }
        return DacDevice(
            id = id,
            name = readFriendlyName(device) ?: id,
            state = state,
            isDefault = id == defaultId,
            channels = format?.second ?: 0,
            sampleRate = format?.first ?: 0,
            bits = format?.third ?: 0,
            volume = level?.first ?: 0f,
            muted = level?.second ?: false,
        )
    }

    private fun readLevel(endpointVolume: MemorySegment): Pair<Float, Boolean> {
        val scalar = arena.allocate(JAVA_FLOAT)
        vtableCall(
            endpointVolume,
            VT_VOLUME_GET_SCALAR,
            FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS),
            scalar,
        )
        val mute = arena.allocate(JAVA_INT)
        vtableCall(
            endpointVolume,
            VT_VOLUME_GET_MUTE,
            FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS),
            mute,
        )
        return scalar.get(JAVA_FLOAT, 0L) to (mute.get(JAVA_INT, 0L) != 0)
    }

    /** PKEY_AudioEngine_DeviceFormat as (sampleRate, channels, bits). */
    private fun readFormat(device: MemorySegment): Triple<Int, Int, Int>? = withStore(device) { store ->
        val (variantType, payload) = readProperty(store, PKEY_AUDIOENGINE_DEVICE_FORMAT) ?: return@withStore null
        if (variantType != VT_BLOB || payload !is ByteArray || payload.size < 18) return@withStore null
        val wave = MemorySegment.ofArray(payload)
        // WAVEFORMATEX: wFormatTag(2) nChannels(2) nSamplesPerSec(4) nAvgBytesPerSec(4)
        // nBlockAlign(2) wBitsPerSample(2) cbSize(2)
        Triple(
            wave.get(JAVA_INT_UNALIGNED, 4L),
            wave.get(JAVA_SHORT_UNALIGNED, 2L).toInt(),
            wave.get(JAVA_SHORT_UNALIGNED, 14L).toInt(),
        )
    }

    private fun readFriendlyName(device: MemorySegment): String? = withStore(device) { store ->
        val (variantType, payload) = readProperty(store, PKEY_DEVICE_FRIENDLY_NAME) ?: return@withStore null
        if (variantType != VT_LPWSTR || payload !is String) return@withStore null
        payload
    }

    private fun <T> withStore(device: MemorySegment, block: (MemorySegment) -> T): T? {
        val slot = PointerSlot()
        if (vtableCall(
                device,
                VT_DEVICE_OPEN_PROPERTY_STORE,
                FunctionDescriptor.of(JAVA_INT, ADDRESS, JAVA_INT, ADDRESS),
                STGM_READ,
                slot.memory,
            ) < 0
        ) {
            return null
        }
        val store = outPointer(slot.memory) ?: return null
        return try {
            block(store)
        } finally {
            release(store)
        }
    }

    /**
     * Reads one PROPVARIANT property and copies its payload into Kotlin memory, so the
     * variant can be cleared immediately and no COM allocation outlives the call.
     */
    private fun readProperty(store: MemorySegment, key: String): Pair<Int, Any?>? {
        val propertyKey = arena.allocate(24)
        MemorySegment.copy(guid(key.substringBefore("|")), 0L, propertyKey, 0L, 16L)
        propertyKey.set(JAVA_INT, 16L, key.substringAfter("|").toInt())

        val variant = arena.allocate(32)
        if (vtableCall(
                store,
                VT_STORE_GET_VALUE,
                FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS, ADDRESS),
                propertyKey,
                variant,
            ) < 0
        ) {
            return null
        }
        val type = variant.get(JAVA_SHORT, 0L).toInt()
        val payload: Any? = when (type) {
            VT_LPWSTR -> variant.get(ADDRESS, 8L)
                .takeIf { it.address() != 0L }
                ?.let { wideString(it) }

            VT_BLOB -> {
                // PROPVARIANT BLOB: cbSize at +8, pBlobData at +16.
                val size = variant.get(JAVA_INT, 8L)
                val data = variant.get(ADDRESS, 16L)
                if (size > 0 && data.address() != 0L) {
                    ByteArray(size).also { bytes ->
                        MemorySegment.copy(
                            data.reinterpret(size.toLong()),
                            0L,
                            MemorySegment.ofArray(bytes),
                            0L,
                            size.toLong(),
                        )
                    }
                } else {
                    null
                }
            }

            else -> null
        }
        propVariantClear.invokeExact(variant) as Int
        return type to payload
    }
}
