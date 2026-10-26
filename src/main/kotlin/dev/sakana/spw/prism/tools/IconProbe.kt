package dev.sakana.spw.prism.tools

import org.jetbrains.skia.Data
import org.jetbrains.skia.EncodedImageFormat
import org.jetbrains.skia.Surface
import org.jetbrains.skia.svg.SVGDOM
import java.nio.file.Files
import java.nio.file.Path

/**
 * Renders an SVG at the sizes the host actually draws the titlebar icon, so a 512 px
 * logo can be judged at 20 dp instead of guessing.
 *
 * Usage: IconProbe [/icons/prism-entry.svg | C:\path\logo.svg] [output dir]
 *
 * A leading `/` means "classpath resource"; anything else is read as a file, so a
 * freshly exported logo can be checked before it is installed.
 */
object IconProbe {
    @JvmStatic
    fun main(args: Array<String>) {
        val source = args.getOrElse(0) { "/icons/prism-entry.svg" }
        val output = Path.of(args.getOrElse(1) { "build/icon-preview" })
        Files.createDirectories(output)
        val bytes = read(source)
        val dom = SVGDOM(Data.makeFromBytes(bytes))
        // Icons in this project use a square viewBox; keep it in sync with the artwork.
        val artWidth = 512f
        val artHeight = 512f
        val stem = Path.of(source).fileName.toString().substringBeforeLast('.')
        println("svg: $source (${bytes.size} bytes, artwork ${artWidth.toInt()}px)")
        dom.setContainerSize(artWidth, artHeight)
        for (size in intArrayOf(16, 20, 24, 32, 40, 64, 256)) {
            val surface = Surface.makeRasterN32Premul(size, size)
            val canvas = surface.canvas
            // Roughly the host titlebar background.
            canvas.clear(0xFF12161D.toInt())
            // loadSvgPainter() renders the artwork at its intrinsic size and scales it
            // when drawing, so scale the canvas instead of the container.
            canvas.save()
            canvas.scale(size / artWidth, size / artHeight)
            runCatching { dom.render(canvas) }
                .onFailure { println("render ${size}px failed: ${it.message}") }
            canvas.restore()
            val data = surface.makeImageSnapshot().encodeToData(EncodedImageFormat.PNG) ?: continue
            val name = "$stem-$size.png"
            Files.write(output.resolve(name), data.bytes)
            println("  wrote $name")
        }
    }

    private fun read(source: String): ByteArray =
        if (source.startsWith("/")) {
            IconProbe::class.java.getResourceAsStream(source)?.use { it.readBytes() }
                ?: error("missing resource $source")
        } else {
            Files.readAllBytes(Path.of(source))
        }
}
