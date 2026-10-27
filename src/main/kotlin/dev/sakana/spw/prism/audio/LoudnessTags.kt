package dev.sakana.spw.prism.audio

import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.nio.file.Path
import java.nio.file.StandardOpenOption

/**
 * Loudness metadata stored *inside* the audio file, so the meters can say what the track
 * was mastered to instead of only what is coming out right now.
 *
 * ReplayGain lives in Vorbis comments (FLAC/Ogg), `TXXX` frames (ID3) or free-form `ilst`
 * atoms (M4A); R128 gain lives in the same places as an integer in 1/256 dB. All of them
 * are plain ASCII key/value pairs, so a byte scan of the tag areas finds every container
 * without a format-specific parser.
 */
data class LoudnessTags(
    val trackGainDb: Float? = null,
    val trackPeak: Float? = null,
    val albumGainDb: Float? = null,
    val r128GainQ78: Int? = null,
) {
    /** R128 gain converted to dB, or the ReplayGain track gain when only that exists. */
    val gainDb: Float?
        get() = trackGainDb ?: r128GainQ78?.let { it / 256f }

    val isEmpty: Boolean
        get() = trackGainDb == null && trackPeak == null && albumGainDb == null && r128GainQ78 == null

    companion object {
        private const val SCAN_BYTES = 512 * 1024

        fun read(path: Path?): LoudnessTags? {
            if (path == null) return null
            return runCatching {
                FileChannel.open(path, StandardOpenOption.READ).use { channel ->
                    val size = channel.size()
                    if (size <= 0L) return@use null
                    val head = channel.readAt(0L, minOf(SCAN_BYTES.toLong(), size).toInt())
                    val tail = if (size > SCAN_BYTES) {
                        channel.readAt(maxOf(0L, size - SCAN_BYTES), SCAN_BYTES)
                    } else {
                        ByteArray(0)
                    }
                    val text = String(head, Charsets.ISO_8859_1) + "\u0000" + String(tail, Charsets.ISO_8859_1)
                    val parsed = LoudnessTags(
                        trackGainDb = valueAfter(text, "REPLAYGAIN_TRACK_GAIN"),
                        trackPeak = valueAfter(text, "REPLAYGAIN_TRACK_PEAK"),
                        albumGainDb = valueAfter(text, "REPLAYGAIN_ALBUM_GAIN"),
                        r128GainQ78 = intAfter(text, "R128_TRACK_GAIN"),
                    )
                    parsed.takeUnless { it.isEmpty }
                }
            }.getOrNull()
        }

        /** "REPLAYGAIN_TRACK_GAIN" then '=' or a NUL, then the number, e.g. "-3.20 dB". */
        private fun valueAfter(text: String, key: String): Float? {
            var from = 0
            while (true) {
                val index = text.indexOf(key, from, ignoreCase = true)
                if (index < 0) return null
                from = index + key.length
                val raw = rawValue(text, from) ?: continue
                leadingFloat(raw)?.let { return it }
            }
        }

        private fun intAfter(text: String, key: String): Int? {
            var from = 0
            while (true) {
                val index = text.indexOf(key, from, ignoreCase = true)
                if (index < 0) return null
                from = index + key.length
                val raw = rawValue(text, from) ?: continue
                leadingInt(raw)?.let { return it }
            }
        }

        /** Values carry units ("+1.51 dB") or trailing binary, so take the leading number. */
        private fun leadingFloat(raw: String): Float? =
            LEADING_FLOAT.find(raw.trim())?.value?.toFloatOrNull()

        private fun leadingInt(raw: String): Int? =
            LEADING_INT.find(raw.trim())?.value?.toIntOrNull()

        private val LEADING_FLOAT = Regex("^[+-]?\\d+(\\.\\d+)?")
        private val LEADING_INT = Regex("^[+-]?\\d+")

        /** Skips the separator and returns up to the next NUL or control byte. */
        private fun rawValue(text: String, from: Int): String? {
            var start = from
            while (start < text.length && (text[start] == '=' || text[start] == '\u0000' || text[start] == ' ')) start++
            if (start >= text.length) return null
            var end = start
            while (end < text.length) {
                val c = text[end]
                if (c == '\u0000' || c.code < 0x20) break
                end++
            }
            return if (end > start) text.substring(start, end) else null
        }

        private fun FileChannel.readAt(offset: Long, length: Int): ByteArray {
            val buffer = ByteBuffer.allocate(length)
            var position = offset
            while (buffer.hasRemaining()) {
                val read = read(buffer, position)
                if (read <= 0) break
                position += read
            }
            val out = ByteArray(buffer.position())
            buffer.flip()
            buffer.get(out)
            return out
        }
    }
}
