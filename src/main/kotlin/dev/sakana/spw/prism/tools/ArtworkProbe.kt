package dev.sakana.spw.prism.tools

import dev.sakana.spw.prism.ui.ArtworkPalette
import dev.sakana.spw.prism.ui.ArtworkPaletteExtractor
import java.nio.file.Files
import java.nio.file.Path

/**
 * Diagnostic entry point: prints what palette the artwork extractor resolves for a set of
 * audio files, plus how long it took. Used to check embedded-cover parsing (FLAC / ID3 /
 * M4A) against a real library without launching the player.
 *
 * Usage: ArtworkProbe <list.txt>   (one path per line, UTF-8)
 *        ArtworkProbe <file> [more files...]
 */
object ArtworkProbe {
    @JvmStatic
    fun main(args: Array<String>) {
        val paths = resolve(args)
        if (paths.isEmpty()) {
            println("usage: ArtworkProbe <list.txt | file...>")
            return
        }
        var found = 0
        for (path in paths) {
            val name = path.fileName.toString()
            if (!Files.isRegularFile(path)) {
                println("MISSING   $name")
                continue
            }
            val started = System.nanoTime()
            val palette = runCatching { ArtworkPaletteExtractor.load(path) }.getOrElse { failure ->
                println("ERROR     $name  ${failure.javaClass.simpleName}: ${failure.message}")
                continue
            }
            val elapsed = (System.nanoTime() - started) / 1_000_000.0
            if (palette == null) {
                println("%6.1f ms  none      %s".format(elapsed, name))
                continue
            }
            found++
            val spectrum = palette.spectrum.joinToString(" ") { ArtworkPalette.toHex(it) }
            println(
                "%6.1f ms  %-8s  %s  spectrum=%s left=%s right=%s".format(
                    elapsed,
                    if (palette.fromArtwork) "artwork" else "theme",
                    name,
                    spectrum,
                    ArtworkPalette.toHex(palette.left),
                    ArtworkPalette.toHex(palette.right),
                )
            )
        }
        println("resolved $found / ${paths.size}")
    }

    private fun resolve(args: Array<String>): List<Path> {
        if (args.size == 1 && args[0].endsWith(".txt", ignoreCase = true)) {
            val listFile = Path.of(args[0])
            if (Files.isRegularFile(listFile)) {
                return Files.readAllLines(listFile, Charsets.UTF_8)
                    .map { it.trim() }
                    .filter { it.isNotEmpty() && !it.startsWith("#") }
                    .map(Path::of)
            }
        }
        return args.map(Path::of)
    }
}
