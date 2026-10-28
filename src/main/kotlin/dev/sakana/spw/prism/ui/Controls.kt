package dev.sakana.spw.prism.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.dp
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.offset
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInParent
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/**
 * Host-style controls: grouped rows inside a translucent card, Fluent switches, segmented
 * pickers. Every colour comes from [LocalHostTokens]; sizes come from the spacing scale
 * (4 / 8 / 12 / 16 / 24) and the shared radii (card 8, control 6).
 */

// SaltDimens.corner = 12dp, SaltShapesDefaults.medium = 6dp on desktop.
private val CardShape = RoundedCornerShape(12.dp)
private val ControlShape = RoundedCornerShape(6.dp)
private val Spacing = 12.dp
private val RowMinHeight = 32.dp   // SaltDimens.item on desktop

@Composable
fun SectionHeader(text: String, modifier: Modifier = Modifier) {
    val tokens = LocalHostTokens.current
    Text(
        text,
        style = Type.section,
        color = tokens.onSurfaceVariant,
        modifier = modifier.padding(top = 24.dp, bottom = 8.dp),
    )
}

/** Grouped container; no hard border, hierarchy comes from the translucent tint. */
@Composable
fun SettingCard(modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    val tokens = LocalHostTokens.current
    Column(
        modifier
            .fillMaxWidth()
            .clip(CardShape)
            .background(tokens.card, CardShape)
            .padding(horizontal = 16.dp, vertical = 2.dp)  // SaltDimens.padding 16 / subPadding 12,
    ) {
        content()
    }
}

/**
 * One setting row: label (and optional description) on the left, control on the right.
 * The whole row is clickable so the target is as large as the host's own rows.
 */
@Composable
fun SettingRow(
    label: String,
    modifier: Modifier = Modifier,
    description: String? = null,
    onClick: (() -> Unit)? = null,
    control: @Composable () -> Unit,
) {
    val tokens = LocalHostTokens.current
    val interaction = remember { MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()
    Row(
        modifier
            .fillMaxWidth()
            .clip(ControlShape)
            .background(if (hovered) tokens.cardHover else Color.Transparent, ControlShape)
            .let { if (onClick != null) it.clickable(interactionSource = interaction, indication = null, onClick = onClick) else it }
            .heightIn(min = RowMinHeight)
            .padding(vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(label, style = Type.body, color = tokens.onSurface)
            if (description != null) {
                Text(description, style = Type.caption, color = tokens.onSurfaceVariant)
            }
        }
        Spacer(Modifier.width(Spacing))
        control()
    }
}


/** One-row container for a control that is self-describing (a segmented picker, a button). */
@Composable
fun ControlRow(modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    Row(
        modifier.fillMaxWidth().padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        content()
    }
}

/** Fluent-style switch: 40x20 track, 16dp knob, the knob slides instead of jumping. */
@Composable
fun HostSwitch(checked: Boolean, onCheckedChange: (Boolean) -> Unit, modifier: Modifier = Modifier) {
    val tokens = LocalHostTokens.current
    val trackColor by animateColorAsState(
        targetValue = if (checked) tokens.accent else tokens.outlineStrong,
        animationSpec = tween(140),
        label = "switch-track",
    )
    val knobColor by animateColorAsState(
        targetValue = if (checked) Color.White else tokens.onSurface,
        animationSpec = tween(140),
        label = "switch-knob",
    )
    val knobOffset by animateDpAsState(
        targetValue = if (checked) 20.dp else 0.dp,
        animationSpec = tween(140, easing = FastOutSlowInEasing),
        label = "switch-offset",
    )
    Box(
        modifier
            .width(40.dp)
            .height(20.dp)
            .clip(RoundedCornerShape(50))
            .background(trackColor, RoundedCornerShape(50))
            .border(1.dp, if (checked) Color.Transparent else tokens.outline, RoundedCornerShape(50))
            .clickable { onCheckedChange(!checked) }
            .padding(2.dp),
    ) {
        Box(
            Modifier
                .offset(x = knobOffset)
                .width(16.dp)
                .height(16.dp)
                .background(knobColor, RoundedCornerShape(50)),
        )
    }
}

/** Toggle row: the pattern used by every boolean setting. */
@Composable
fun ToggleRow(
    label: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
    description: String? = null,
) {
    SettingRow(
        label = label,
        description = description,
        modifier = modifier,
        onClick = { onCheckedChange(!checked) },
    ) {
        HostSwitch(checked, onCheckedChange)
    }
}

/**
 * Segmented picker modelled on the host's: one rounded container, and the selected segment
 * is a pill that slides to the new position instead of jumping.
 */
@Composable
fun <T> SegmentedControl(
    options: List<T>,
    selected: T,
    label: (T) -> String,
    onSelect: (T) -> Unit,
    modifier: Modifier = Modifier,
) {
    val tokens = LocalHostTokens.current
    val density = LocalDensity.current
    var thumbLeft by remember { mutableStateOf(0.dp) }
    var thumbWidth by remember { mutableStateOf(0.dp) }
    var placed by remember { mutableStateOf(false) }
    val animatedLeft by animateDpAsState(
        targetValue = thumbLeft,
        animationSpec = tween(180, easing = FastOutSlowInEasing),
        label = "segment-left",
    )
    val animatedWidth by animateDpAsState(
        targetValue = thumbWidth,
        animationSpec = tween(180, easing = FastOutSlowInEasing),
        label = "segment-width",
    )
    Box(
        modifier
            .clip(ControlShape)
            .background(tokens.cardHover, ControlShape)
            .border(1.dp, tokens.outline, ControlShape)
            .padding(2.dp),
    ) {
        if (placed) {
            Box(
                Modifier
                    .offset(x = animatedLeft)
                    .width(animatedWidth)
                    .height(28.dp)
                    .clip(RoundedCornerShape(4.dp))
                    .background(tokens.accent.copy(alpha = 0.16f)),
            )
        }
        Row(horizontalArrangement = Arrangement.spacedBy(2.dp)) {
            options.forEach { option ->
                val active = option == selected
                Box(
                    Modifier
                        .clip(RoundedCornerShape(4.dp))
                        .onGloballyPositioned { coordinates ->
                            if (active) {
                                thumbLeft = with(density) { coordinates.positionInParent().x.toDp() }
                                thumbWidth = with(density) { coordinates.size.width.toDp() }
                                placed = true
                            }
                        }
                        .clickable { onSelect(option) }
                        .heightIn(min = 28.dp)
                        .padding(horizontal = 12.dp, vertical = 6.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        label(option),
                        style = Type.caption,
                        color = if (active) tokens.accent else tokens.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

/** Plain action button, same radius and height as the host's secondary buttons. */
@Composable
fun ActionButton(
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    accent: Boolean = false,
) {
    val tokens = LocalHostTokens.current
    val interaction = remember { MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()
    Box(
        modifier
            .clip(ControlShape)
            .background(if (hovered) tokens.cardHover else tokens.card, ControlShape)
            .border(1.dp, tokens.outline, ControlShape)
            .clickable(interactionSource = interaction, indication = null, onClick = onClick)
            .heightIn(min = 32.dp)
            .padding(horizontal = 14.dp, vertical = 7.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(label, style = Type.caption, color = if (accent) tokens.accent else tokens.onSurface)
    }
}

/** Small labelled value used by the sound-field statistics grid. */
@Composable
fun StatCell(label: String, value: String, valueStyle: TextStyle, modifier: Modifier = Modifier) {
    val tokens = LocalHostTokens.current
    Column(modifier) {
        Text(label, style = Type.caption, color = tokens.onSurfaceVariant)
        Text(value, style = valueStyle, color = tokens.onSurface)
    }
}
