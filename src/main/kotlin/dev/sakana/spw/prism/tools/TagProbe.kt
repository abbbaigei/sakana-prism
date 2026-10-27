package dev.sakana.spw.prism.tools

import dev.sakana.spw.prism.audio.LoudnessTags
import java.nio.file.Path

/** Prints the ReplayGain / R128 tags a file carries, for manual verification. */
fun main(args: Array<String>) {
    args.forEach { raw ->
        val path = Path.of(raw)
        val tags = LoudnessTags.read(path)
        println("$raw -> $tags")
        tags?.let {
            println("  gain=${it.gainDb} trackPeak=${it.trackPeak} album=${it.albumGainDb} r128=${it.r128GainQ78}")
        }
    }
}
