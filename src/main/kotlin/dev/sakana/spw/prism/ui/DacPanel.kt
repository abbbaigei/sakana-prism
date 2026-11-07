package dev.sakana.spw.prism.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import dev.sakana.spw.prism.dac.CoreAudio
import dev.sakana.spw.prism.dac.DacDevice
import dev.sakana.spw.prism.dac.DacState
import dev.sakana.spw.prism.i18n.Strings
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

/**
 * DAC page: the Windows render endpoints with their format and volume.
 *
 * Reads the endpoint volume through Core Audio, so the sliders move the system volume —
 * the same thing the Windows volume flyout does.
 */
@Composable
fun DacPanel(modifier: Modifier = Modifier) {
    val tokens = LocalHostTokens.current
    var devices by remember { mutableStateOf<List<DacDevice>>(emptyList()) }
    var loading by remember { mutableStateOf(true) }
    var refresh by remember { mutableStateOf(0) }
    // Local echo while dragging, otherwise the 6 s refresh fights the thumb.
    var draggingId by remember { mutableStateOf<String?>(null) }
    var draggingValue by remember { mutableStateOf(0f) }

    LaunchedEffect(refresh) {
        loading = true
        devices = withContext(Dispatchers.IO) { CoreAudio.devices() }
        loading = false
    }
    LaunchedEffect(Unit) {
        while (true) {
            delay(6_000)
            if (draggingId == null) {
                val latest = withContext(Dispatchers.IO) { CoreAudio.devices() }
                if (latest.isNotEmpty()) devices = latest
            }
        }
    }

    Column(modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp)) {
        Row(Modifier.fillMaxWidth().padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(Strings["dac.title"], style = Type.title, color = tokens.onSurface)
            Spacer(Modifier.weight(1f))
            if (loading) {
                Text(Strings["dac.loading"], style = Type.caption, color = tokens.onSurfaceVariant)
                Spacer(Modifier.width(10.dp))
            }
            ActionButton(Strings["dac.refresh"], onClick = { refresh++ }, accent = true)
        }
        Spacer(Modifier.height(14.dp))

        if (devices.isEmpty() && !loading) {
            Text(Strings["dac.empty"], style = Type.body, color = tokens.onSurfaceVariant)
            return@Column
        }

        devices.forEach { device ->
            val shown = if (draggingId == device.id) draggingValue else device.volume
            DeviceCard(
                device = device,
                volume = shown,
                enabled = device.state == DacState.ACTIVE,
                onVolumeChange = {
                    draggingId = device.id
                    draggingValue = it
                },
                onVolumeCommit = {
                    draggingId = null
                    CoreAudio.setVolume(device.id, it)
                    refresh++
                },
                onToggleMute = {
                    CoreAudio.setMute(device.id, !device.muted)
                    refresh++
                },
            )
            Spacer(Modifier.height(10.dp))
        }
        Spacer(Modifier.height(24.dp))
    }
}

@Composable
private fun DeviceCard(
    device: DacDevice,
    volume: Float,
    enabled: Boolean,
    onVolumeChange: (Float) -> Unit,
    onVolumeCommit: (Float) -> Unit,
    onToggleMute: () -> Unit,
) {
    val tokens = LocalHostTokens.current
    val shape = RoundedCornerShape(12.dp)
    val highlight = device.isDefault
    Column(
        Modifier
            .fillMaxWidth()
            .clip(shape)
            .background(if (highlight) tokens.accent.copy(alpha = 0.10f) else tokens.card, shape)
            .border(1.dp, if (highlight) tokens.accent.copy(alpha = 0.45f) else tokens.outline, shape)
            .padding(horizontal = 16.dp, vertical = 12.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                device.name,
                style = Type.body.copy(fontWeight = FontWeight.Medium),
                color = if (enabled) tokens.onSurface else tokens.onSurfaceVariant,
                modifier = Modifier.weight(1f),
                maxLines = 1,
            )
            if (device.isDefault) {
                Pill(Strings["dac.default"], tokens.accent)
                Spacer(Modifier.width(8.dp))
            }
            if (device.state != DacState.ACTIVE) {
                Pill(stateLabel(device.state), tokens.onSurfaceVariant)
            }
        }
        Spacer(Modifier.height(4.dp))
        Text(
            listOfNotNull(device.formatLabel, stateLabel(device.state))
                .joinToString("  ·  "),
            style = Type.caption,
            color = tokens.onSurfaceVariant,
        )

        if (enabled) {
            Spacer(Modifier.height(6.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    "${(volume * 100).toInt()}%",
                    style = Type.numericSmall,
                    color = tokens.onSurface,
                    modifier = Modifier.width(44.dp),
                )
                Slider(
                    value = volume.coerceIn(0f, 1f),
                    onValueChange = onVolumeChange,
                    onValueChangeFinished = { onVolumeCommit(volume) },
                    colors = SliderDefaults.colors(
                        thumbColor = tokens.accent,
                        activeTrackColor = tokens.accent,
                        inactiveTrackColor = tokens.outlineStrong,
                    ),
                    modifier = Modifier.weight(1f),
                )
                Spacer(Modifier.width(10.dp))
                Box(
                    Modifier
                        .size(30.dp)
                        .clip(RoundedCornerShape(8.dp))
                        .background(if (device.muted) tokens.accent.copy(alpha = 0.18f) else Color.Transparent)
                        .border(1.dp, if (device.muted) tokens.accent else tokens.outline, RoundedCornerShape(8.dp))
                        .clickable(onClick = onToggleMute),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        if (device.muted) "M" else "♪",
                        style = Type.caption,
                        color = if (device.muted) tokens.accent else tokens.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

@Composable
private fun Pill(text: String, color: Color) {
    Box(
        Modifier
            .clip(RoundedCornerShape(50))
            .background(color.copy(alpha = 0.16f), RoundedCornerShape(50))
            .padding(horizontal = 8.dp, vertical = 3.dp),
    ) {
        Text(text, style = Type.caption, color = color)
    }
}

private fun stateLabel(state: DacState): String = when (state) {
    DacState.ACTIVE -> Strings["dac.state.active"]
    DacState.DISABLED -> Strings["dac.state.disabled"]
    DacState.UNPLUGGED -> Strings["dac.state.unplugged"]
    DacState.NOT_PRESENT -> Strings["dac.state.absent"]
}
