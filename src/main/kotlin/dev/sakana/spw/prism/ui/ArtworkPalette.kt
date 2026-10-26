package dev.sakana.spw.prism.ui

import androidx.compose.ui.graphics.Color
import dev.sakana.spw.prism.settings.ThemePreset
import java.awt.Color as AwtColor
import java.awt.image.BufferedImage
import java.io.ByteArrayInputStream
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import java.util.concurrent.ConcurrentHashMap
import javax.imageio.ImageIO
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/** Upper bound for any single metadata read, so a corrupt header cannot allocate GBs. */
private const val MAX_METADATA_BYTES = 48 * 1024 * 1024

private val isoBmffTypes = setOf("ftyp", "moov", "mdat", "free", "skip", "wide", "styp")

data class ArtworkPalette(
    val spectrum: List<Color>,
    val left: Color,
    val right: Color,
    val fromArtwork: Boolean = false,
) {
    companion object {
        fun fromCustom(a: Color, b: Color, left: Color, right: Color): ArtworkPalette {
            val spectrum = listOf(
                a,
                blend(a, b, 0.25f),
                b,
                blend(a, b, 0.70f),
                lightenColor(b, 0.10f),
            )
            return ArtworkPalette(spectrum, left, right, fromArtwork = false)
        }

        fun parseHex(value: String?): Color? {
            if (value == null) return null
            val hex = value.trim().removePrefix("#")
            if (hex.length != 6 || hex.any { it !in '0'..'9' && it !in 'a'..'f' && it !in 'A'..'F' }) return null
            val rgb = hex.toIntOrNull(16) ?: return null
            return Color(
                red = ((rgb shr 16) and 0xff) / 255f,
                green = ((rgb shr 8) and 0xff) / 255f,
                blue = (rgb and 0xff) / 255f,
            )
        }

        fun toHex(color: Color): String {
            val r = (color.red * 255f).toInt().coerceIn(0, 255)
            val g = (color.green * 255f).toInt().coerceIn(0, 255)
            val b = (color.blue * 255f).toInt().coerceIn(0, 255)
            return "#%02X%02X%02X".format(r, g, b)
        }

        private fun blend(a: Color, b: Color, t: Float): Color = Color(
            red = a.red + (b.red - a.red) * t,
            green = a.green + (b.green - a.green) * t,
            blue = a.blue + (b.blue - a.blue) * t,
        )

        private fun lightenColor(c: Color, amount: Float): Color = Color(
            red = min(1f, c.red + amount),
            green = min(1f, c.green + amount),
            blue = min(1f, c.blue + amount),
        )

        fun fallback(theme: ThemePreset) = ArtworkPalette(
            spectrum = listOf(theme.accent, theme.secondary, theme.tertiary, theme.highlight),
            left = theme.accent,
            right = theme.secondary,
            fromArtwork = false,
        )
    }
}

/**
 * Extracts a small color palette from sidecar/embedded cover art.
 * Called on Dispatchers.IO; never from audio or draw threads.
 */
object ArtworkPaletteExtractor {
    private val cache = ConcurrentHashMap<String, ArtworkPalette>()
    private val withoutArtwork = ConcurrentHashMap.newKeySet<String>()

    fun load(path: Path): ArtworkPalette? {
        val key = path.toAbsolutePath().toString()
        if (cache.containsKey(key)) return cache[key]
        if (withoutArtwork.contains(key)) return null
        val result = runCatching { loadUncached(path) }.getOrNull()
        if (result == null) {
            withoutArtwork.add(key)
        } else {
            cache[key] = result
        }
        return result
    }

    private fun loadUncached(path: Path): ArtworkPalette? {
        // Each source is tried in turn and must actually decode: a half-parsed
        // embedded picture must not shadow a perfectly good folder image.
        val sources = listOf<() -> ByteArray?>(
            { readSidecar(path) },
            { readEmbedded(path) },
            { readFolderImage(path) },
        )
        for (source in sources) {
            val bytes = runCatching(source).getOrNull() ?: continue
            if (bytes.size < 64) continue
            val image = runCatching { ImageIO.read(ByteArrayInputStream(bytes)) }.getOrNull() ?: continue
            val colors = extractColors(image)
            if (colors.isEmpty()) continue
            val spectrum = spectrumColors(colors)
            val (left, right) = strongestPair(colors, spectrum.first())
            return ArtworkPalette(spectrum, left, right, fromArtwork = true)
        }
        return null
    }

    private fun readSidecar(path: Path): ByteArray? {
        val parent = path.parent ?: return null
        val base = path.fileName.toString().substringBeforeLast('.', path.fileName.toString())
        val names = listOf(
            "$base.jpg", "$base.jpeg", "$base.png",
            "cover.jpg", "cover.jpeg", "cover.png",
            "folder.jpg", "folder.jpeg", "folder.png",
            "front.jpg", "front.jpeg", "front.png",
            "album.jpg", "album.jpeg", "album.png",
            "artwork.jpg", "artwork.jpeg", "artwork.png",
        )
        for (name in names) {
            val candidate = parent.resolve(name)
            if (Files.isRegularFile(candidate)) {
                val bytes = Files.readAllBytes(candidate)
                if (bytes.size > 32) return bytes
            }
        }
        return null
    }

    private fun readFolderImage(path: Path): ByteArray? {
        val parent = path.parent ?: return null
        val imageExtensions = setOf("jpg", "jpeg", "png", "webp", "bmp")
        val candidates = runCatching {
            Files.list(parent).use { stream ->
                stream.filter { candidate ->
                    Files.isRegularFile(candidate) &&
                        candidate.fileName.toString().substringAfterLast('.', "").lowercase() in imageExtensions &&
                        !candidate.fileName.toString().equals(path.fileName.toString(), ignoreCase = true)
                }.toList()
            }
        }.getOrDefault(emptyList())
        val sorted = candidates.sortedWith(
            compareByDescending<Path> {
                val name = it.fileName.toString().lowercase()
                name.startsWith("cover") || name.startsWith("folder") || name.startsWith("front") ||
                    name.startsWith("album") || name.startsWith("artwork")
            }.thenByDescending {
                runCatching { Files.size(it) }.getOrDefault(0L)
            }
        )
        for (candidate in sorted) {
            val bytes = runCatching { Files.readAllBytes(candidate) }.getOrNull() ?: continue
            if (bytes.size <= 32) continue
            if (ImageIO.read(ByteArrayInputStream(bytes)) != null) return bytes
        }
        return null
    }

    /**
     * Reads embedded artwork without slurping the whole track: only file headers and the
     * picture block are touched, so a 50 MB M4A costs a few hundred KB of I/O instead of
     * 50 MB of garbage per track change.
     */
    private fun readEmbedded(path: Path): ByteArray? {
        FileChannel.open(path, StandardOpenOption.READ).use { channel ->
            if (channel.size() < 12L) return null
            val head = channel.readAt(0, 12)
            if (head.size < 12) return null
            if (head.hasAscii(0, "fLaC")) return flacPicture(channel)
            if (head.hasAscii(0, "ID3")) return id3Picture(channel)
            if (head.isIsoBmff()) return mp4Picture(channel)
            return null
        }
    }

    private fun flacPicture(channel: FileChannel): ByteArray? {
        val fileSize = channel.size()
        var offset = 4L
        var guard = 0
        while (offset + 4 <= fileSize && guard++ < 256) {
            val header = channel.readAt(offset, 4)
            if (header.size < 4) return null
            val type = header[0].toInt() and 0x7f
            val last = header[0].toInt() and 0x80 != 0
            val length = ((header[1].toInt() and 0xff) shl 16) or
                ((header[2].toInt() and 0xff) shl 8) or
                (header[3].toInt() and 0xff)
            val start = offset + 4
            if (type == 6 && length in 1..MAX_METADATA_BYTES) {
                val block = channel.readExact(start, length)
                if (block != null) {
                    val picture = parseFlacPicture(block, 0, block.size)
                    if (picture != null) return picture
                }
            }
            offset = start + length
            if (last) return null
        }
        return null
    }

    private fun parseFlacPicture(bytes: ByteArray, start: Int, end: Int): ByteArray? {
        var p = start
        fun u32(): Int {
            if (p + 4 > end) return -1
            val v = ((bytes[p].toInt() and 0xff) shl 24) or
                ((bytes[p + 1].toInt() and 0xff) shl 16) or
                ((bytes[p + 2].toInt() and 0xff) shl 8) or
                (bytes[p + 3].toInt() and 0xff)
            p += 4
            return v
        }
        if (u32() < 0) return null
        val mimeLen = u32(); if (mimeLen < 0 || p + mimeLen > end) return null
        p += mimeLen
        val descLen = u32(); if (descLen < 0 || p + descLen > end) return null
        p += descLen
        if (p + 16 > end) return null
        p += 16
        val dataLen = u32(); if (dataLen <= 0 || p + dataLen > end) return null
        return bytes.copyOfRange(p, p + dataLen)
    }

    private fun id3Picture(channel: FileChannel): ByteArray? {
        val header = channel.readAt(0, 10)
        if (header.size < 10) return null
        val version = header[3].toInt() and 0xff
        val tagSize = syncSafe(header, 6)
        val total = 10 + tagSize
        if (tagSize <= 0 || total > MAX_METADATA_BYTES) return null
        val bytes = channel.readExact(0, total) ?: return null
        var p = 10
        val end = bytes.size
        var guard = 0
        while (p + 10 <= end && guard++ < 1024) {
            val id = bytes.ascii(p, 4)
            val size = if (version >= 4) syncSafe(bytes, p + 4) else beInt(bytes, p + 4)
            if (size <= 0 || p + 10 + size > end) break
            if (id == "APIC") {
                val frameStart = p + 10
                var q = frameStart
                val encoding = bytes[q].toInt() and 0xff
                q++
                while (q < frameStart + size && bytes[q] != 0.toByte()) q++
                q++ // mime terminator
                if (q < frameStart + size) q++ // picture type
                if (encoding == 1 || encoding == 2) {
                    while (q + 1 < frameStart + size && !(bytes[q] == 0.toByte() && bytes[q + 1] == 0.toByte())) q += 2
                    q += 2
                } else {
                    while (q < frameStart + size && bytes[q] != 0.toByte()) q++
                    q++
                }
                if (q < frameStart + size) return bytes.copyOfRange(q, frameStart + size)
            }
            p += 10 + size
        }
        return null
    }

    private fun syncSafe(bytes: ByteArray, at: Int): Int {
        if (at + 4 > bytes.size) return 0
        return ((bytes[at].toInt() and 0x7f) shl 21) or
            ((bytes[at + 1].toInt() and 0x7f) shl 14) or
            ((bytes[at + 2].toInt() and 0x7f) shl 7) or
            (bytes[at + 3].toInt() and 0x7f)
    }

    private fun beInt(bytes: ByteArray, at: Int): Int {
        if (at + 4 > bytes.size) return 0
        return ((bytes[at].toInt() and 0xff) shl 24) or
            ((bytes[at + 1].toInt() and 0xff) shl 16) or
            ((bytes[at + 2].toInt() and 0xff) shl 8) or
            (bytes[at + 3].toInt() and 0xff)
    }

    /**
     * M4A/MP4 keep cover art at moov/udta/meta/ilst/covr. Top-level atoms are walked by
     * seeking, so the (often multi-gigabyte) mdat payload is skipped without reading it.
     */
    private fun mp4Picture(channel: FileChannel): ByteArray? {
        val fileSize = channel.size()
        var offset = 0L
        var moov: LongRange? = null
        var guard = 0
        while (offset + 8 <= fileSize && guard++ < 4096) {
            val header = channel.readAt(offset, 16)
            if (header.size < 8) break
            val size32 = header.u32(0)
            val type = header.ascii(4, 4)
            var headerSize = 8L
            var atomSize = size32
            if (size32 == 1L) {
                if (header.size < 16) break
                headerSize = 16L
                atomSize = header.u64(8)
            } else if (size32 == 0L) {
                atomSize = fileSize - offset
            }
            if (atomSize < headerSize) break
            val atomEnd = min(offset + atomSize, fileSize)
            if (type == "moov") {
                moov = (offset + headerSize) until atomEnd
                break
            }
            offset = atomEnd
        }
        val range = moov ?: return null
        val length = (range.last - range.first).toInt()
        if (length < 8) return null
        // Cover art lives inside moov, so one bounded read covers the whole tree.
        val payload = channel.readAt(range.first, min(length, MAX_METADATA_BYTES))
        if (payload.size < 8) return null
        return mp4CoverIn(payload) ?: mp4CoverByScan(payload)
    }

    private fun mp4CoverIn(moov: ByteArray): ByteArray? {
        val root = Mp4Atom("moov", 0, moov.size)
        val meta = mp4Child(moov, mp4Child(moov, root, "udta") ?: root, "meta") ?: return null
        val ilst = mp4Child(moov, meta.fullBoxBody(), "ilst") ?: return null
        val covr = mp4Child(moov, ilst, "covr") ?: return null
        for (data in mp4Children(moov, covr)) {
            if (data.type != "data") continue
            val start = data.start + 8 // 4-byte data type + 4-byte locale
            if (data.end - start > 64) return moov.copyOfRange(start, data.end)
        }
        return null
    }

    /** Fallback for encoders that nest ilst somewhere unexpected. */
    private fun mp4CoverByScan(moov: ByteArray): ByteArray? {
        val marker = "covr".toByteArray(Charsets.ISO_8859_1)
        var index = 4
        while (index + 8 < moov.size) {
            if (moov.hasBytes(index, marker)) {
                val atomStart = index - 4
                val size = moov.u32(atomStart)
                val atomEnd = if (size in 8..(moov.size - atomStart).toLong()) {
                    atomStart + size.toInt()
                } else {
                    moov.size
                }
                for (data in mp4Atoms(moov, index + 4, atomEnd)) {
                    if (data.type == "data" && data.end - data.start > 72) {
                        return moov.copyOfRange(data.start + 8, data.end)
                    }
                }
            }
            index++
        }
        return null
    }

    private class Mp4Atom(val type: String, val start: Int, val end: Int) {
        /** meta is a full box: 4 bytes of version+flags before the child atoms. */
        fun fullBoxBody() = Mp4Atom(type, min(start + 4, end), end)
    }

    private fun mp4Children(bytes: ByteArray, parent: Mp4Atom): List<Mp4Atom> =
        mp4Atoms(bytes, parent.start, parent.end)

    private fun mp4Child(bytes: ByteArray, parent: Mp4Atom, type: String): Mp4Atom? =
        mp4Atoms(bytes, parent.start, parent.end).firstOrNull { it.type == type }

    private fun mp4Atoms(bytes: ByteArray, start: Int, end: Int): List<Mp4Atom> {
        val atoms = ArrayList<Mp4Atom>()
        var p = start
        var guard = 0
        while (p + 8 <= end && guard++ < 4096) {
            val size32 = bytes.u32(p)
            val type = bytes.ascii(p + 4, 4)
            var headerSize = 8
            var size = size32
            if (size32 == 1L) {
                if (p + 16 > end) break
                headerSize = 16
                size = bytes.u64(p + 8)
            } else if (size32 == 0L) {
                size = (end - p).toLong()
            }
            if (size < headerSize) break
            if (size > (end - p).toLong()) size = (end - p).toLong()
            val atomEnd = p + size.toInt()
            atoms.add(Mp4Atom(type, p + headerSize, atomEnd))
            p = atomEnd
        }
        return atoms
    }

    private fun FileChannel.readAt(offset: Long, length: Int): ByteArray {
        if (length <= 0 || offset < 0L) return ByteArray(0)
        val buffer = ByteBuffer.allocate(length)
        var position = offset
        while (buffer.hasRemaining()) {
            val read = read(buffer, position)
            if (read <= 0) break
            position += read
        }
        return if (buffer.position() == length) buffer.array() else buffer.array().copyOf(buffer.position())
    }

    private fun FileChannel.readExact(offset: Long, length: Int): ByteArray? {
        if (length <= 0) return null
        val bytes = readAt(offset, length)
        return if (bytes.size == length) bytes else null
    }

    private fun ByteArray.u32(at: Int): Long {
        if (at < 0 || at + 4 > size) return -1L
        return ((this[at].toLong() and 0xff) shl 24) or
            ((this[at + 1].toLong() and 0xff) shl 16) or
            ((this[at + 2].toLong() and 0xff) shl 8) or
            (this[at + 3].toLong() and 0xff)
    }

    private fun ByteArray.u64(at: Int): Long {
        if (at < 0 || at + 8 > size) return -1L
        var value = 0L
        for (i in 0 until 8) value = (value shl 8) or (this[at + i].toLong() and 0xff)
        return value
    }

    private fun ByteArray.ascii(at: Int, length: Int): String {
        if (at < 0 || length <= 0 || at + length > size) return ""
        return String(this, at, length, Charsets.ISO_8859_1)
    }

    private fun ByteArray.hasAscii(at: Int, expected: String): Boolean = ascii(at, expected.length) == expected

    private fun ByteArray.hasBytes(at: Int, expected: ByteArray): Boolean {
        if (at < 0 || at + expected.size > size) return false
        for (i in expected.indices) if (this[at + i] != expected[i]) return false
        return true
    }

    private fun ByteArray.isIsoBmff(): Boolean = ascii(4, 4) in isoBmffTypes

    private fun extractColors(image: BufferedImage): List<Int> {
        val thumb = BufferedImage(48, 48, BufferedImage.TYPE_INT_RGB)
        val g = thumb.createGraphics()
        g.drawImage(image, 0, 0, 48, 48, null)
        g.dispose()
        val buckets = HashMap<Int, Int>()
        for (y in 0 until thumb.height) {
            for (x in 0 until thumb.width) {
                val argb = thumb.getRGB(x, y)
                val a = (argb ushr 24) and 0xff
                if (a < 96) continue
                val r = (argb shr 16) and 0xff
                val gg = (argb shr 8) and 0xff
                val b = argb and 0xff
                val lum = (0.2126 * r + 0.7152 * gg + 0.0722 * b) / 255.0
                if (lum < 0.035 || lum > 0.97) continue
                val qr = (r / 24) * 24
                val qg = (gg / 24) * 24
                val qb = (b / 24) * 24
                val key = (qr shl 16) or (qg shl 8) or qb
                val hsb = AwtColor.RGBtoHSB(qr, qg, qb, null)
                val weight = 1 + (hsb[1] * 3).toInt()
                buckets[key] = (buckets[key] ?: 0) + weight
            }
        }
        val sorted = buckets.entries.sortedByDescending { it.value }
        val selected = ArrayList<Int>(8)
        for ((key, _) in sorted) {
            if (selected.size >= 8) break
            if (selected.all { distance(key, it) > 38 }) selected.add(key)
        }
        if (selected.size < 2) {
            val fallback = sorted.firstOrNull()?.key ?: return emptyList()
            selected.add(fallback)
            selected.add(complement(fallback))
        }
        return selected
    }

    private fun spectrumColors(colors: List<Int>): List<Color> {
        val result = ArrayList<Color>(5)
        for (value in colors) result.add(visible(value))
        while (result.size < 5) {
            val base = result[result.size % result.size.coerceAtLeast(1)]
            result.add(lighten(base, 0.12f * result.size))
        }
        return result.take(5)
    }

    private fun strongestPair(colors: List<Int>, fallback: Color): Pair<Color, Color> {
        var bestA = colors.first()
        var bestB = complement(bestA)
        var best = -1.0
        for (i in colors.indices) {
            for (j in i + 1 until colors.size) {
                val hueGap = hueDistance(colors[i], colors[j])
                val d = distance(colors[i], colors[j]) + hueGap * 120 + saturation(colors[i]) * 30 + saturation(colors[j]) * 30
                if (d > best) {
                    best = d
                    bestA = colors[i]
                    bestB = colors[j]
                }
            }
        }
        var left = visible(bestA)
        var right = visible(bestB)
        if (distance(bestA, bestB) < 42 || hueDistance(bestA, bestB) < 0.10) {
            val c = complement(bestA)
            left = visible(bestA)
            right = visible(c)
        }
        if (left == right) right = fallback
        return left to right
    }

    private fun visible(rgb: Int): Color {
        val r = (rgb shr 16) and 0xff
        val g = (rgb shr 8) and 0xff
        val b = rgb and 0xff
        val hsb = AwtColor.RGBtoHSB(r, g, b, null)
        hsb[1] = max(hsb[1], 0.50f)
        hsb[2] = max(hsb[2], 0.62f)
        val out = AwtColor.HSBtoRGB(hsb[0], hsb[1], hsb[2])
        return Color(
            red = ((out shr 16) and 0xff) / 255f,
            green = ((out shr 8) and 0xff) / 255f,
            blue = (out and 0xff) / 255f,
        )
    }

    private fun complement(rgb: Int): Int {
        val r = (rgb shr 16) and 0xff
        val g = (rgb shr 8) and 0xff
        val b = rgb and 0xff
        val hsb = AwtColor.RGBtoHSB(r, g, b, null)
        val out = AwtColor.HSBtoRGB((hsb[0] + 0.5f) % 1f, max(hsb[1], 0.55f), max(hsb[2], 0.72f))
        return out and 0xFFFFFF
    }

    private fun lighten(color: Color, amount: Float): Color = Color(
        red = min(1f, color.red + amount),
        green = min(1f, color.green + amount),
        blue = min(1f, color.blue + amount),
        alpha = 1f,
    )

    private fun distance(a: Int, b: Int): Double {
        val dr = ((a shr 16) and 0xff) - ((b shr 16) and 0xff)
        val dg = ((a shr 8) and 0xff) - ((b shr 8) and 0xff)
        val db = (a and 0xff) - (b and 0xff)
        return sqrt(2.0 * dr * dr + 4.0 * dg * dg + 3.0 * db * db)
    }

    private fun hueDistance(a: Int, b: Int): Double {
        val ha = AwtColor.RGBtoHSB((a shr 16) and 0xff, (a shr 8) and 0xff, a and 0xff, null)[0]
        val hb = AwtColor.RGBtoHSB((b shr 16) and 0xff, (b shr 8) and 0xff, b and 0xff, null)[0]
        val diff = abs(ha - hb)
        return min(diff, 1f - diff).toDouble()
    }

    private fun saturation(rgb: Int): Double {
        val r = (rgb shr 16) and 0xff
        val g = (rgb shr 8) and 0xff
        val b = rgb and 0xff
        val maxC = max(r, max(g, b))
        val minC = min(r, min(g, b))
        return if (maxC == 0) 0.0 else (maxC - minC).toDouble() / maxC
    }
}
