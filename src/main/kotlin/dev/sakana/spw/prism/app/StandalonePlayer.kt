package dev.sakana.spw.prism.app

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.sun.jna.WString
import dev.sakana.spw.prism.audio.BassBootstrap
import dev.sakana.spw.prism.audio.BassChannelInfo
import dev.sakana.spw.prism.audio.BassFlags
import dev.sakana.spw.prism.audio.BassNative
import java.nio.file.Path
import kotlin.math.roundToLong

/**
 * Playback for the standalone app. The visualiser core only decodes; this class owns the
 * one thing the plugin never needed: a stream on a real output device plus a transport.
 *
 * A separate decode stream is opened by [dev.sakana.spw.prism.preview.SpectrumController],
 * exactly like inside the host, so analysis and playback stay independent.
 */
class StandalonePlayer {

    var trackPath: Path? by mutableStateOf(null)
        private set
    var playing by mutableStateOf(false)
        private set
    var positionMs by mutableStateOf(0L)
        private set
    var durationMs by mutableStateOf(0L)
        private set
    var error by mutableStateOf<String?>(null)
        private set
    var volume: Float by mutableStateOf(0.8f)
        private set

    private var bass: BassNative? = null
    private var handle = 0

    private fun ensureBass(): BassNative? {
        bass?.let { return it }
        return runCatching {
            val api = BassBootstrap.load()
            BassBootstrap.initPlayback(api)
            bass = api
            api
        }.onFailure { error = it.message ?: "BASS 初始化失败" }.getOrNull()
    }

    /** Opens [file] and starts playing it. Returns false when BASS rejects the file. */
    fun open(file: Path): Boolean {
        val api = ensureBass() ?: return false
        closeStream()
        error = null
        val flags = BassFlags.SAMPLE_FLOAT or BassFlags.UNICODE
        val stream = api.BASS_StreamCreateFile(false, WString(file.toAbsolutePath().toString()), 0L, 0L, flags)
        if (stream == 0) {
            error = "无法解码：" + file.fileName
            playing = false
            return false
        }
        handle = stream
        trackPath = file
        durationMs = runCatching {
            (api.BASS_ChannelBytes2Seconds(stream, api.BASS_ChannelGetLength(stream, BassFlags.POS_BYTE)) * 1000.0)
                .roundToLong()
        }.getOrDefault(0L)
        positionMs = 0L
        applyVolume(api)
        playing = api.BASS_ChannelPlay(stream, true)
        return true
    }

    fun toggle() {
        val api = bass ?: return
        if (handle == 0) return
        if (playing) {
            api.BASS_ChannelPause(handle)
            playing = false
        } else {
            // Restarting at the end of the track should replay from the top.
            if (durationMs > 0 && positionMs >= durationMs - 250) seek(0L)
            playing = api.BASS_ChannelPlay(handle, false)
        }
    }

    fun seek(ms: Long) {
        val api = bass ?: return
        if (handle == 0) return
        val clamped = ms.coerceIn(0L, if (durationMs > 0) durationMs else Long.MAX_VALUE)
        val bytes = api.BASS_ChannelSeconds2Bytes(handle, clamped / 1000.0)
        api.BASS_ChannelSetPosition(handle, bytes, BassFlags.POS_BYTE)
        positionMs = clamped
    }

    fun applyVolumeSetting(value: Float) {
        volume = value.coerceIn(0f, 1f)
        bass?.let { applyVolume(it) }
    }

    private fun applyVolume(api: BassNative) {
        if (handle != 0) api.BASS_ChannelSetAttribute(handle, BassFlags.ATTRIB_VOL, volume)
    }

    /**
     * Exact playback position, read straight from BASS. The analyser asks for this once per
     * frame, so returning the cached UI value (updated ~8 times a second) would make the
     * analysis window jump and the visuals stutter.
     */
    fun currentPositionMs(): Long {
        val api = bass ?: return positionMs
        if (handle == 0) return positionMs
        return runCatching {
            (api.BASS_ChannelBytes2Seconds(handle, api.BASS_ChannelGetPosition(handle, BassFlags.POS_BYTE)) * 1000.0)
                .roundToLong()
        }.getOrDefault(positionMs)
    }

    /** Called by the UI ticker; keeps the transport in sync with the stream. */
    fun poll() {
        val api = bass ?: return
        if (handle == 0) return
        positionMs = runCatching {
            (api.BASS_ChannelBytes2Seconds(handle, api.BASS_ChannelGetPosition(handle, BassFlags.POS_BYTE)) * 1000.0)
                .roundToLong()
        }.getOrDefault(positionMs)
        playing = api.BASS_ChannelIsActive(handle) == BassFlags.ACTIVE_PLAYING
        if (!playing && durationMs > 0 && positionMs >= durationMs - 120) {
            positionMs = durationMs
        }
    }

    fun close() {
        closeStream()
        bass?.let { runCatching { it.BASS_Free() } }
        bass = null
        trackPath = null
        playing = false
        positionMs = 0L
        durationMs = 0L
    }

    private fun closeStream() {
        val api = bass ?: return
        if (handle != 0) {
            runCatching { api.BASS_ChannelStop(handle) }
            runCatching { api.BASS_StreamFree(handle) }
            handle = 0
        }
    }
}
