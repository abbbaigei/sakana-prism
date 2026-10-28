package dev.sakana.spw.prism.app

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import dev.sakana.spw.prism.i18n.Strings
import dev.sakana.spw.prism.preview.SpectrumController
import dev.sakana.spw.prism.ui.SpectrumDesign
import kotlinx.coroutines.delay
import java.awt.datatransfer.DataFlavor
import java.awt.dnd.DnDConstants
import java.awt.dnd.DropTarget
import java.awt.dnd.DropTargetAdapter
import java.awt.dnd.DropTargetDragEvent
import java.awt.dnd.DropTargetDropEvent
import java.nio.file.Path
import kotlin.io.path.exists
import kotlin.io.path.name

private val Panel = Color(0xFF0D121A)
private val BarBackground = Color(0xFF111823)
private val Muted = Color(0xFF7D8A9A)
private val Teal = Color(0xFF4DD8C4)

private val AUDIO_EXTENSIONS = setOf(
    "flac", "mp3", "m4a", "aac", "ogg", "opus", "wav", "aiff", "aif", "dsf", "dff", "ape", "wv",
)

/**
 * Standalone entry point: drop an audio file on the window, it decodes through BASS and plays
 * while the visualiser runs. Everything above the transport is the very same SpectrumDesign
 * composable the workshop plugin renders inside Salt Player.
 */
fun main(args: Array<String>) = application {
    Strings.brand = "SAKANA PRISM"
    val player = remember { StandalonePlayer() }
    var trackPath by remember { mutableStateOf<Path?>(null) }
    var message by remember { mutableStateOf<String?>(null) }

    DisposableEffect(Unit) {
        onDispose { player.close() }
    }

    fun load(path: Path) {
        if (!path.exists()) {
            message = "文件不存在：${path.name}"
            return
        }
        if (!path.isAudioCandidate()) {
            message = "不支持的文件类型：${path.name}"
            return
        }
        message = null
        if (player.open(path)) {
            trackPath = path
        } else {
            message = player.error ?: "无法播放：${path.name}"
        }
    }

    // A path on the command line opens straight away; handy for testing.
    LaunchedEffect(Unit) {
        args.firstOrNull()?.let { runCatching { load(Path.of(it)) } }
    }

    LaunchedEffect(player) {
        while (true) {
            player.poll()
            delay(80)
        }
    }

    Window(
        onCloseRequest = { player.close(); exitApplication() },
        title = "Sakana Prism",
        state = rememberWindowState(width = 1180.dp, height = 780.dp),
    ) {
        // AWT drop target: Compose's external-drag API is still experimental, this is stable.
        DisposableEffect(window) {
            val target = DropTarget(
                window,
                DnDConstants.ACTION_COPY,
                object : DropTargetAdapter() {
                    // Both callbacks must accept, otherwise AWT rejects the drag and
                    // drop() is never delivered.
                    override fun dragEnter(event: DropTargetDragEvent) {
                        event.acceptDrag(DnDConstants.ACTION_COPY)
                    }

                    override fun dragOver(event: DropTargetDragEvent) {
                        event.acceptDrag(DnDConstants.ACTION_COPY)
                    }

                    override fun drop(event: DropTargetDropEvent) {
                        event.acceptDrop(DnDConstants.ACTION_COPY)
                        val dropped = runCatching {
                            event.transferable.getTransferData(DataFlavor.javaFileListFlavor)
                        }.getOrNull()
                        @Suppress("UNCHECKED_CAST")
                        val file = (dropped as? List<java.io.File>)?.firstOrNull()
                        if (file != null) {
                            load(file.toPath())
                            event.dropComplete(true)
                        } else {
                            event.dropComplete(false)
                        }
                    }
                },
                true,
            )
            window.dropTarget = target
            onDispose { runCatching { window.dropTarget = null } }
        }

        Column(Modifier.fillMaxSize().background(Panel)) {
            Box(Modifier.fillMaxWidth().weight(1f)) {
                val path = trackPath
                if (path == null) {
                    DropHint(message) { chooseFile(window)?.let(::load) }
                } else {
                    val controller = remember(path) {
                        SpectrumController(path, { player.currentPositionMs() }, { player.playing })
                    }
                    SpectrumDesign(controller)
                }
            }
            Transport(player)
        }
    }
}

@Composable
private fun DropHint(message: String?, onBrowse: () -> Unit) {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                "Sakana Prism",
                fontSize = 30.sp,
                color = Teal,
                fontWeight = FontWeight.SemiBold,
                letterSpacing = 1.sp,
            )
            Spacer(Modifier.height(10.dp))
            Text("拖入音频文件即可播放 · Drop an audio file to play", fontSize = 13.sp, color = Muted)
            Spacer(Modifier.height(18.dp))
            Box(
                Modifier
                    .background(Teal.copy(alpha = 0.16f), RoundedCornerShape(50))
                    .clickable(onClick = onBrowse)
                    .padding(horizontal = 20.dp, vertical = 9.dp),
            ) {
                Text("打开文件 · Open file", fontSize = 12.sp, color = Teal)
            }
            if (message != null) {
                Spacer(Modifier.height(14.dp))
                Text(message, fontSize = 12.sp, color = Color(0xFFFF9E80))
            }
        }
    }
}

@Composable
private fun Transport(player: StandalonePlayer) {
    val duration = player.durationMs.coerceAtLeast(1L)
    val position = player.positionMs.coerceIn(0L, duration)
    Row(
        Modifier.fillMaxWidth()
            .height(58.dp)
            .background(BarBackground)
            .padding(horizontal = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier
                .height(30.dp)
                .background(Teal.copy(alpha = 0.14f), RoundedCornerShape(50))
                .clickable { player.toggle() }
                .padding(horizontal = 14.dp),
            contentAlignment = Alignment.Center,
        ) {
            Text(if (player.playing) "暂停" else "播放", fontSize = 12.sp, color = Teal)
        }
        Spacer(Modifier.width(14.dp))
        Text(format(position), fontSize = 11.sp, color = Muted, fontFamily = FontFamily.Monospace)
        Spacer(Modifier.width(10.dp))
        Slider(
            value = position.toFloat(),
            onValueChange = { player.seek(it.toLong()) },
            valueRange = 0f..duration.toFloat(),
            colors = SliderDefaults.colors(
                thumbColor = Teal,
                activeTrackColor = Teal,
                inactiveTrackColor = Color(0xFF22303E),
            ),
            modifier = Modifier.weight(1f),
        )
        Spacer(Modifier.width(10.dp))
        Text(format(player.durationMs), fontSize = 11.sp, color = Muted, fontFamily = FontFamily.Monospace)
        Spacer(Modifier.width(16.dp))
        Text("音量", fontSize = 10.sp, color = Muted)
        Spacer(Modifier.width(6.dp))
        Slider(
            value = player.volume,
            onValueChange = { player.applyVolumeSetting(it) },
            valueRange = 0f..1f,
            colors = SliderDefaults.colors(
                thumbColor = Teal,
                activeTrackColor = Teal,
                inactiveTrackColor = Color(0xFF22303E),
            ),
            modifier = Modifier.width(92.dp),
        )
        Spacer(Modifier.width(16.dp))
        Text(player.trackPath?.name ?: "—", fontSize = 10.sp, color = Muted)
    }
}

private fun format(ms: Long): String {
    val total = (ms / 1000).coerceAtLeast(0)
    return "%d:%02d".format(total / 60, total % 60)
}

private fun Path.isAudioCandidate(): Boolean =
    name.substringAfterLast('.', "").lowercase() in AUDIO_EXTENSIONS

/** Swing file chooser, used when drag and drop is not available (remote desktop, etc.). */
private fun chooseFile(parent: java.awt.Window?): Path? {
    val chooser = javax.swing.JFileChooser().apply {
        dialogTitle = "选择音频文件 / Choose an audio file"
        fileSelectionMode = javax.swing.JFileChooser.FILES_ONLY
        isAcceptAllFileFilterUsed = true
        fileFilter = object : javax.swing.filechooser.FileFilter() {
            override fun accept(f: java.io.File): Boolean =
                f.isDirectory || f.extension().lowercase() in AUDIO_EXTENSIONS

            override fun getDescription(): String = "音频 / Audio (*.flac, *.mp3, *.m4a, *.wav, …)"
        }
    }
    return if (chooser.showOpenDialog(parent) == javax.swing.JFileChooser.APPROVE_OPTION) {
        chooser.selectedFile?.toPath()
    } else {
        null
    }
}

private fun java.io.File.extension(): String = name.substringAfterLast('.', "")
