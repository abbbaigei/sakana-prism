package dev.sakana.spw.prism.audio

import com.sun.jna.Native
import java.nio.file.Files
import java.nio.file.Path

/**
 * Locates the BASS native libraries. Inside the host they sit next to the host's own
 * resources; a standalone build ships them in a `native` folder beside the executable.
 */
internal object BassPaths {

    // PE machine types, read straight out of the DLL header. The host runs on a 64-bit JVM,
    // so a 32-bit bass.dll (the one at the root of the official BASS zip) fails to load with
    // Windows error 193, which surfaces as "not a valid Win32 application".
    private const val MACHINE_X86 = 0x14C
    private const val MACHINE_X64 = 0x8664
    private const val MACHINE_ARM64 = 0xAA64

    private val expectedMachine: Int =
        if (System.getProperty("sun.arch.data.model") == "32") MACHINE_X86 else MACHINE_X64

    /**
     * Directories the host keeps its BASS DLLs in.
     *
     * The plugin runs inside the host JVM, so `ProcessHandle.current().command()` is the host
     * launcher itself — that is the only reliable way to find the install directory when the
     * library lives on a non-default Steam drive.
     */
    private fun hostResourceDirs(): List<Path> {
        val dirs = mutableListOf<Path>()
        val exe = ProcessHandle.current().info().command().orElse(null)
        if (exe != null) {
            val root = runCatching { Path.of(exe).parent }.getOrNull()
            if (root != null) {
                dirs.add(root.resolve("app").resolve("resources"))
                dirs.add(root.resolve("resources"))
                dirs.add(root)
            }
        }
        steamLibraryRoots().forEach {
            dirs.add(
                it.resolve("steamapps").resolve("common")
                    .resolve("Salt Player for Windows").resolve("app").resolve("resources"),
            )
        }
        // Last resort for the common default install; kept for environments where neither
        // the process handle nor the registry gives us anything.
        dirs.add(Path.of("C:\\Program Files (x86)\\Steam\\steamapps\\common\\Salt Player for Windows\\app\\resources"))
        return dirs
    }

    /** Steam install locations from HKCU plus every library listed in libraryfolders.vdf. */
    private fun steamLibraryRoots(): List<Path> = runCatching {
        val steam = com.sun.jna.platform.win32.Advapi32Util.registryGetStringValue(
            com.sun.jna.platform.win32.WinReg.HKEY_CURRENT_USER,
            "Software\\Valve\\Steam",
            "SteamPath",
        )
        val roots = mutableListOf(Path.of(steam))
        val vdf = Path.of(steam, "steamapps", "libraryfolders.vdf")
        if (Files.exists(vdf)) {
            Regex("\"path\"\\s+\"([^\"]+)\"")
                .findAll(Files.readString(vdf))
                .forEach { match -> roots.add(Path.of(match.groupValues[1].replace("\\\\", "\\"))) }
        }
        roots
    }.getOrDefault(emptyList())

    /** Machine type from the PE header, or null when the file is not a PE image at all. */
    private fun peMachine(path: Path): Int? = runCatching {
        Files.newInputStream(path).use { input ->
            val head = ByteArray(1024)
            val read = input.read(head)
            if (read < 0x40) return@use null
            fun u16(offset: Int) = (head[offset].toInt() and 0xFF) or
                ((head[offset + 1].toInt() and 0xFF) shl 8)
            fun u32(offset: Int) =
                u16(offset) or (u16(offset + 2) shl 16)
            if (u16(0) != 0x5A4D) return@use null // "MZ"
            val peOffset = u32(0x3C)
            if (peOffset <= 0 || peOffset + 6 > read) return@use null
            if (head[peOffset] != 'P'.code.toByte() || head[peOffset + 1] != 'E'.code.toByte()) return@use null
            u16(peOffset + 4)
        }
    }.getOrNull()

    private fun archName(machine: Int): String = when (machine) {
        MACHINE_X86 -> "32 位 (x86)"
        MACHINE_X64 -> "64 位 (x64)"
        MACHINE_ARM64 -> "ARM64"
        else -> "未知架构 0x%X".format(machine)
    }

    fun resolve(dll: String): Path {
        val candidates = buildList {
            System.getenv("SAKANA_BASS_DIR")?.let { add(Path.of(it, dll)) }
            addAll(hostResourceDirs().map { it.resolve(dll) })
            add(Path.of(System.getProperty("user.dir"), "native", dll))
            add(Path.of(System.getProperty("user.dir"), dll))
            ProcessHandle.current().info().command().orElse(null)?.let { exe ->
                Path.of(exe).parent?.let { dir -> add(dir.resolve("native").resolve(dll)) }
            }
        }
        val existing = candidates.filter { Files.exists(it) }

        // Prefer a file with the right architecture: a stray 32-bit copy dropped next to the
        // app must not shadow the host's own 64-bit DLL.
        existing.firstOrNull { peMachine(it) == expectedMachine }?.let { return it }

        val wrongArch = existing.firstNotNullOfOrNull { path ->
            peMachine(path)?.takeIf { it != expectedMachine }?.let { path to it }
        }
        if (wrongArch != null) {
            val (path, machine) = wrongArch
            error(
                "BASS DLL 位数不符：$path 是 ${archName(machine)}，宿主需要 ${archName(expectedMachine)}。" +
                    "请删除该文件（宿主自带正确的 DLL），或改用 x64 版本。",
            )
        }
        error(
            "找不到 $dll。该文件由 Salt Player 自带，请确认是 Steam 完整安装；" +
                "也可设置环境变量 SAKANA_BASS_DIR 指向 app\\resources。已搜索：" +
                candidates.joinToString(),
        )
    }
}

/**
 * One-shot BASS setup. The plugin only needs decoding; the standalone app also needs a
 * real output device, so the two entry points differ in how they call BASS_Init.
 */
internal object BassBootstrap {
    private var cached: BassNative? = null
    private var initialized = false

    @Synchronized
    fun load(dll: Path = BassPaths.resolve("bass.dll")): BassNative {
        cached?.let { return it }
        check(Files.exists(dll)) { "BASS not found: $dll" }
        return Native.load(dll.toAbsolutePath().toString(), BassNative::class.java).also { cached = it }
    }

    /**
     * The host normally owns BASS already. Initializing it again on every track change
     * can interrupt host decoding, so global setup is deliberately one-shot.
     */
    @Synchronized
    fun initDecode(bass: BassNative, flacPlugin: Path = BassPaths.resolve("bassflac.dll")) {
        if (initialized) return
        initialized = true
        if (!bass.BASS_Init(0, 48000, 0, null, null)) {
            println("BASS_Init returned false (host may own BASS), error=${bass.BASS_ErrorGetCode()}")
        }
        loadPlugins(bass, flacPlugin)
    }

    /** Standalone playback: opens the default output device instead of the null device. */
    @Synchronized
    fun initPlayback(bass: BassNative, flacPlugin: Path? = null) {
        if (initialized) return
        initialized = true
        check(bass.BASS_Init(-1, 48000, 0, null, null)) {
            "BASS_Init failed, error=${bass.BASS_ErrorGetCode()}"
        }
        loadPlugins(bass, flacPlugin ?: BassPaths.resolve("bassflac.dll"))
    }

    private fun loadPlugins(bass: BassNative, flacPlugin: Path) {
        runCatching { BassPaths.resolve("bassopus.dll") }.getOrNull()?.let { plugin ->
            if (bass.BASS_PluginLoad(plugin.toAbsolutePath().toString(), 0) == 0) {
                println("BASS_PluginLoad(opus) failed, error=${bass.BASS_ErrorGetCode()}")
            }
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
