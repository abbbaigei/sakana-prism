package dev.sakana.spw.prism.host

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.xuncorp.spw.workshop.api.PlaybackExtensionPoint
import com.xuncorp.spw.workshop.api.WorkshopApi
import org.pf4j.Extension
import java.util.concurrent.atomic.AtomicLong

/** Public playback callbacks only. No host audio internals are read. */
object SpectrumPlayback {
    var path by mutableStateOf<String?>(null)
        private set
    var title by mutableStateOf("")
        private set
    var artist by mutableStateOf("")
        private set

    private val position = AtomicLong(0L)
    private val positionAt = AtomicLong(System.nanoTime())
    private val duration = AtomicLong(0L)
    private val playing = AtomicLong(0L)

    /** Extrapolate from the last host callback so the analysis window follows the audible moment. */
    val positionMs: Long
        get() {
            val base = position.get()
            if (playing.get() == 0L) return base
            val elapsedMs = (System.nanoTime() - positionAt.get()) / 1_000_000L
            val value = base + elapsedMs.coerceAtLeast(0L)
            val total = duration.get()
            return if (total > 0L) value.coerceAtMost(total) else value
        }
    val isPlaying: Boolean get() = playing.get() != 0L

    fun refreshMedia() {
        runCatching {
            val item = WorkshopApi.playback.getCurrentMediaItem() ?: return
            path = item.path
            title = item.title
            artist = item.artist
            duration.set(item.duration.coerceAtLeast(0L))
        }
    }

    fun updatePosition(value: Long) {
        position.set(value.coerceAtLeast(0L))
        positionAt.set(System.nanoTime())
    }
    fun updatePlaying(value: Boolean) { playing.set(if (value) 1L else 0L) }
}

@Extension
class SpectrumPlaybackExtension : PlaybackExtensionPoint {
    override fun onStateChanged(state: PlaybackExtensionPoint.State) {
        SpectrumPlayback.refreshMedia()
    }

    override fun onIsPlayingChanged(isPlaying: Boolean) {
        SpectrumPlayback.updatePlaying(isPlaying)
    }

    override fun onSeekTo(position: Long) {
        SpectrumPlayback.updatePosition(position)
    }

    override fun onPositionUpdated(position: Long) {
        SpectrumPlayback.updatePosition(position)
    }

    override fun onBeforeLoadLyrics(mediaItem: PlaybackExtensionPoint.MediaItem): String? {
        SpectrumPlayback.refreshMedia()
        return null
    }
}
