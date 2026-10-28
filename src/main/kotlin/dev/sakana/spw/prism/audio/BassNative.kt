package dev.sakana.spw.prism.audio

import com.sun.jna.Library
import com.sun.jna.Memory
import com.sun.jna.Pointer
import com.sun.jna.Structure
import com.sun.jna.WString

/** Public BASS 2.4 C API subset. No Salt Player internals are involved. */
internal interface BassNative : Library {
    fun BASS_Init(device: Int, freq: Int, flags: Int, win: Pointer?, clsid: Pointer?): Boolean
    fun BASS_ErrorGetCode(): Int
    fun BASS_StreamCreateFile(mem: Boolean, file: WString, offset: Long, length: Long, flags: Int): Int
    fun BASS_StreamFree(handle: Int): Boolean
    fun BASS_ChannelGetInfo(handle: Int, info: BassChannelInfo): Boolean
    fun BASS_ChannelGetData(handle: Int, buffer: Pointer, length: Int): Int
    fun BASS_ChannelSetPosition(handle: Int, pos: Long, mode: Int): Boolean
    fun BASS_ChannelSeconds2Bytes(handle: Int, pos: Double): Long
    fun BASS_ChannelGetLength(handle: Int, mode: Int): Long
    fun BASS_ChannelGetLevel(handle: Int): Int
    fun BASS_PluginLoad(file: String, flags: Int): Int
    fun BASS_PluginFree(handle: Int): Boolean

    // Playback (used by the standalone app; the plugin itself only decodes).
    fun BASS_ChannelPlay(handle: Int, restart: Boolean): Boolean
    fun BASS_ChannelPause(handle: Int): Boolean
    fun BASS_ChannelStop(handle: Int): Boolean
    fun BASS_ChannelIsActive(handle: Int): Int
    fun BASS_ChannelGetPosition(handle: Int, mode: Int): Long
    fun BASS_ChannelBytes2Seconds(handle: Int, pos: Long): Double
    fun BASS_ChannelSetAttribute(handle: Int, attrib: Int, value: Float): Boolean
    fun BASS_Free(): Boolean
}

@Structure.FieldOrder("freq", "chans", "flags", "ctype", "origres", "plugin", "sample", "filename")
internal class BassChannelInfo : Structure() {
    @JvmField var freq: Int = 0
    @JvmField var chans: Int = 0
    @JvmField var flags: Int = 0
    @JvmField var ctype: Int = 0
    @JvmField var origres: Int = 0
    @JvmField var plugin: Int = 0
    @JvmField var sample: Int = 0
    @JvmField var filename: Pointer? = null
}

internal object BassFlags {
    const val STREAM_DECODE = 0x200000
    const val SAMPLE_FLOAT = 0x100
    const val UNICODE = 0x80000000.toInt()
    const val POS_BYTE = 0
    const val DATA_FLOAT = 0x40000000
    const val DATA_FFT8192 = 0x80000005.toInt()
    const val DATA_FFT16384 = 0x80000006.toInt()
    const val DATA_FFT32768 = 0x80000007.toInt()

    /** BASS_ACTIVE_* values returned by BASS_ChannelIsActive. */
    const val ACTIVE_STOPPED = 0
    const val ACTIVE_PLAYING = 1
    const val ACTIVE_PAUSED = 3

    /** BASS_ATTRIB_VOL: linear volume, 0..1. */
    const val ATTRIB_VOL = 2
}

/** Owns exactly one independent decode channel. */
internal class BassDecodeStream(
    private val bass: BassNative,
    val path: String,
) : AutoCloseable {
    val handle: Int
    val sampleRate: Int
    val channels: Int

    init {
        val flags = BassFlags.STREAM_DECODE or BassFlags.SAMPLE_FLOAT or BassFlags.UNICODE
        handle = bass.BASS_StreamCreateFile(false, WString(path), 0L, 0L, flags)
        check(handle != 0) { "BASS_StreamCreateFile failed: ${bass.BASS_ErrorGetCode()}" }
        val info = BassChannelInfo()
        check(bass.BASS_ChannelGetInfo(handle, info)) { "BASS_ChannelGetInfo failed" }
        sampleRate = info.freq
        channels = info.chans.coerceAtLeast(1)
    }

    fun seekMs(positionMs: Long) {
        val bytes = bass.BASS_ChannelSeconds2Bytes(handle, positionMs.coerceAtLeast(0L) / 1000.0)
        bass.BASS_ChannelSetPosition(handle, bytes, BassFlags.POS_BYTE)
    }

    private val fftBuffers = HashMap<Int, Memory>()
    private val pcmBuffers = HashMap<Int, Memory>()

    fun readFft8192(): FloatArray? = readFft(BassFlags.DATA_FFT8192, 4096)
    fun readFft16384(): FloatArray? = readFft(BassFlags.DATA_FFT16384, 8192)

    /** 16384 is the default low-band window; 32768 is optional. */
    fun readLowFft(preferLarge: Boolean): FloatArray? =
        if (preferLarge) {
            readFft(BassFlags.DATA_FFT32768, 16384) ?: readFft(BassFlags.DATA_FFT16384, 8192)
        } else {
            readFft(BassFlags.DATA_FFT16384, 8192)
        }

    private fun readFft(flag: Int, bins: Int): FloatArray? {
        val memory = fftBuffers.getOrPut(bins) { Memory(bins.toLong() * 4L) }
        val got = bass.BASS_ChannelGetData(handle, memory, flag)
        if (got <= 0) return null
        return memory.getFloatArray(0, bins)
    }

    fun readFloatPcm(frames: Int): FloatArray? {
        val bytes = frames * channels * 4
        val memory = pcmBuffers.getOrPut(bytes) { Memory(bytes.toLong()) }
        val got = bass.BASS_ChannelGetData(handle, memory, BassFlags.DATA_FLOAT or bytes)
        if (got <= 0) return null
        return memory.getFloatArray(0, got / 4)
    }

    override fun close() {
        if (handle != 0) bass.BASS_StreamFree(handle)
    }
}
