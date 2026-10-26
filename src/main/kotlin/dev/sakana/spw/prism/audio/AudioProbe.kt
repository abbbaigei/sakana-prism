package dev.sakana.spw.prism.audio

import com.sun.jna.Native
import java.nio.file.Files
import java.nio.file.Path

private const val DEFAULT_BASS = """C:\Program Files (x86)\Steam\steamapps\common\Salt Player for Windows\app\resources\bass.dll"""
private const val DEFAULT_FLAC = """C:\Program Files (x86)\Steam\steamapps\common\Salt Player for Windows\app\resources\bassflac.dll"""

internal object BassBootstrap {
    private var cached: BassNative? = null
    private var initialized = false

    @Synchronized
    fun load(dll: Path = Path.of(DEFAULT_BASS)): BassNative {
        cached?.let { return it }
        check(Files.exists(dll)) { "BASS not found: $dll" }
        return Native.load(dll.toAbsolutePath().toString(), BassNative::class.java).also { cached = it }
    }

    /**
     * The host normally owns BASS already. Initializing it again on every track change
     * can interrupt host decoding, so global setup is deliberately one-shot.
     */
    @Synchronized
    fun initDecode(bass: BassNative, flacPlugin: Path = Path.of(DEFAULT_FLAC)) {
        if (initialized) return
        initialized = true
        if (!bass.BASS_Init(0, 48000, 0, null, null)) {
            println("BASS_Init returned false (host may own BASS), error=${bass.BASS_ErrorGetCode()}")
        }
        if (Files.exists(flacPlugin)) {
            val plugin = bass.BASS_PluginLoad(flacPlugin.toAbsolutePath().toString(), 0)
            if (plugin == 0) println("BASS_PluginLoad(flac) failed, error=${bass.BASS_ErrorGetCode()}")
        }
    }
}

internal object AudioProbe {
    @JvmStatic
    fun main(args: Array<String>) {
        val path = when {
            args.isNotEmpty() -> Path.of(args[0])
            else -> Path.of(System.getProperty("user.home"), "Music", "39.flac")
        }
        check(Files.exists(path)) { "test audio not found: $path" }
        val bass = BassBootstrap.load()
        BassBootstrap.initDecode(bass)
        val analyzer = SpectrumAnalyzer(bass, path.toString())
        try {
            println("rate=${analyzer.sampleRate} ch=${analyzer.channels} file=$path")
            val started = System.nanoTime()
            for (i in 0 until 10) {
                val frame = analyzer.analyze(i * 500L)
                if (frame == null) {
                    println("frame $i: null")
                } else {
                    val lowIndexes = (0 until SpectrumLayout.POINTS).filter { SpectrumLayout.frequencyAt(it.toDouble()) < 250.0 }
                    val lowPeakBand = lowIndexes.maxByOrNull { frame.bands[it] } ?: 0
                    val topBands = lowIndexes.sortedByDescending { frame.bands[it] }.take(5)
                        .joinToString(",") { "${SpectrumLayout.frequencyAt(it.toDouble()).toInt()}Hz:${"%.2f".format(frame.bands[it])}" }
                    println("frame $i: peak=${frame.peakHz}Hz lowBand=${SpectrumLayout.frequencyAt(lowPeakBand.toDouble()).toInt()}Hz value=${"%.3f".format(frame.bands[lowPeakBand])} top=[$topBands] field=${frame.field.size} L=${"%.4f".format(frame.leftPeak)} R=${"%.4f".format(frame.rightPeak)} stereo=${frame.stereo}")
                }
            }
            println("avg ms=${"%.2f".format((System.nanoTime() - started) / 1e6 / 10)}")
        } finally {
            analyzer.close()
        }
    }
}
