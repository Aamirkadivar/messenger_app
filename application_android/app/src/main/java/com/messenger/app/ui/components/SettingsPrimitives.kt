package com.messenger.app.ui.components

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.messenger.app.ui.theme.Tokens
import com.messenger.app.ui.theme.isDarkTheme
import com.messenger.app.ui.theme.luxuryTween

/**
 * Shared building blocks for the Settings surfaces.
 *
 * Interaction contract used throughout: a row owns its own interaction and
 * carries the accessibility role, while the control it renders (toggle, radio
 * mark) is decorative and semantically hidden. This keeps a screen reader
 * seeing one target per row instead of two, and keeps the whole 56dp row a
 * valid touch and keyboard target.
 */

@Composable
private fun isDark(): Boolean = isDarkTheme()

// ==================== Section header ====================

/**
 * Section heading. The serif face appears here and nowhere else, which is what
 * makes it read as a considered choice rather than a font mix.
 */
@Composable
fun SectionHeader(
    title: String,
    modifier: Modifier = Modifier,
    overline: String? = null
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(
                start = Tokens.Space.screenGutter,
                end = Tokens.Space.screenGutter,
                top = Tokens.Space.sectionTop,
                bottom = Tokens.Space.md
            )
            // Announce as a single heading rather than two stray text nodes.
            .semantics(mergeDescendants = true) { }
    ) {
        if (overline != null) {
            Text(
                text = overline.uppercase(),
                style = Tokens.Type.overline,
                color = Tokens.Palette.accent(isDark())
            )
            Spacer(Modifier.height(Tokens.Space.sm))
        }
        Text(
            text = title,
            style = Tokens.Type.sectionHeader,
            color = MaterialTheme.colorScheme.onBackground
        )
    }
}

// ==================== Divider ====================

/** Hairline, inset, low-opacity. Visible in both themes. */
@Composable
fun HairlineDivider(modifier: Modifier = Modifier, inset: Boolean = true) {
    Box(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = if (inset) Tokens.Space.dividerInset else 0.dp)
            .height(Tokens.Size.hairline)
            .background(Tokens.Palette.divider(isDark()))
    )
}

// ==================== Toggle ====================

/**
 * Custom switch. Purely visual - the enclosing row owns the click and the
 * accessibility semantics, so this is hidden from the semantics tree.
 */
@Composable
fun LuxToggle(
    checked: Boolean,
    enabled: Boolean = true,
    modifier: Modifier = Modifier
) {
    val dark = isDark()
    val trackColor by animateColorAsState(
        targetValue = if (checked) {
            Tokens.Palette.accent(dark)
        } else {
            Tokens.Palette.toggleTrackOff(dark)
        },
        animationSpec = luxuryTween(Tokens.Motion.FAST_MS),
        label = "toggleTrack"
    )
    val thumbOffset by animateDpAsState(
        targetValue = if (checked) {
            Tokens.Size.toggleWidth - Tokens.Size.toggleThumb - 3.dp
        } else {
            3.dp
        },
        animationSpec = luxuryTween(Tokens.Motion.FAST_MS),
        label = "toggleThumb"
    )

    Box(
        modifier = modifier
            .alpha(if (enabled) 1f else Tokens.DISABLED_ALPHA)
            .size(width = Tokens.Size.toggleWidth, height = Tokens.Size.toggleHeight)
            .clip(RoundedCornerShape(Tokens.Radius.pill))
            .background(trackColor)
            .clearAndSetSemantics { },
        contentAlignment = Alignment.CenterStart
    ) {
        Box(
            modifier = Modifier
                .offset(x = thumbOffset)
                .size(Tokens.Size.toggleThumb)
                .clip(RoundedCornerShape(Tokens.Radius.pill))
                .background(
                    if (checked) Color.White
                    else if (dark) Color.White.copy(alpha = 0.72f)
                    else Color.White
                )
        )
    }
}

// ==================== Rows ====================

/**
 * Base row. 56dp (64dp with a subtitle) - comfortably past the 48dp touch
 * minimum, and the height itself is part of the intended spaciousness.
 */
@Composable
fun SettingsRow(
    title: String,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    icon: ImageVector? = null,
    enabled: Boolean = true,
    trailing: (@Composable () -> Unit)? = null,
    onClick: (() -> Unit)? = null
) {
    val interactionModifier = if (onClick != null && enabled) {
        Modifier.clickable(onClick = onClick)
    } else {
        Modifier
    }

    Row(
        modifier = modifier
            .fillMaxWidth()
            .then(interactionModifier)
            .defaultMinSize(
                minHeight = if (subtitle != null) Tokens.Size.rowHeightTall else Tokens.Size.rowHeight
            )
            .padding(horizontal = Tokens.Space.screenGutter, vertical = Tokens.Space.sm)
            // Fold title/subtitle/trailing value into one node so the row is
            // announced as a single labelled control rather than loose text.
            .semantics(mergeDescendants = true) { },
        verticalAlignment = Alignment.CenterVertically
    ) {
        RowContent(title, subtitle, icon, enabled, Modifier.weight(1f))
        if (trailing != null) {
            Spacer(Modifier.width(Tokens.Space.md))
            trailing()
        }
    }
}

/**
 * Row whose entire surface toggles a boolean. Carries [Role.Switch] and a
 * spoken on/off state, so the row is one coherent target for keyboard and
 * screen-reader users.
 */
@Composable
fun ToggleRow(
    title: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    icon: ImageVector? = null,
    enabled: Boolean = true
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .toggleable(
                value = checked,
                enabled = enabled,
                role = Role.Switch,
                onValueChange = onCheckedChange
            )
            .defaultMinSize(
                minHeight = if (subtitle != null) Tokens.Size.rowHeightTall else Tokens.Size.rowHeight
            )
            .padding(horizontal = Tokens.Space.screenGutter, vertical = Tokens.Space.sm)
            // mergeDescendants is required: toggleable() supplies the Switch role
            // and on/off state but does NOT fold in child text, so without this
            // the row is announced as an unlabelled switch.
            .semantics(mergeDescendants = true) {
                stateDescription = if (checked) "On" else "Off"
            },
        verticalAlignment = Alignment.CenterVertically
    ) {
        RowContent(title, subtitle, icon, enabled, Modifier.weight(1f))
        Spacer(Modifier.width(Tokens.Space.md))
        LuxToggle(checked = checked, enabled = enabled)
    }
}

@Composable
private fun RowContent(
    title: String,
    subtitle: String?,
    icon: ImageVector?,
    enabled: Boolean,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier.alpha(if (enabled) 1f else Tokens.DISABLED_ALPHA),
        verticalAlignment = Alignment.CenterVertically
    ) {
        if (icon != null) {
            Icon(
                imageVector = icon,
                // Decorative: the adjacent title already names the row.
                contentDescription = null,
                modifier = Modifier.size(Tokens.Size.icon),
                tint = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.width(Tokens.Space.md))
        }
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                style = Tokens.Type.rowTitle,
                color = MaterialTheme.colorScheme.onBackground
            )
            if (subtitle != null) {
                Spacer(Modifier.height(2.dp))
                Text(
                    text = subtitle,
                    style = Tokens.Type.rowSubtitle,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    overflow = TextOverflow.Ellipsis,
                    maxLines = 2
                )
            }
        }
    }
}

/** Row showing a current value on the trailing edge, opening a picker on tap. */
@Composable
fun ValueRow(
    title: String,
    value: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    icon: ImageVector? = null,
    enabled: Boolean = true
) {
    SettingsRow(
        title = title,
        subtitle = subtitle,
        icon = icon,
        enabled = enabled,
        onClick = onClick,
        modifier = modifier.semantics(mergeDescendants = true) { stateDescription = value },
        trailing = {
            Text(
                text = value,
                style = Tokens.Type.rowValue,
                color = Tokens.Palette.accent(isDark()),
                modifier = Modifier.alpha(if (enabled) 1f else Tokens.DISABLED_ALPHA)
            )
        }
    )
}

// ==================== Option list (radio semantics) ====================

/**
 * Single-choice list. Used instead of a cramped segmented control wherever the
 * options carry explanatory text.
 */
@Composable
fun <T> OptionList(
    options: List<T>,
    selected: T,
    onSelect: (T) -> Unit,
    label: (T) -> String,
    modifier: Modifier = Modifier,
    description: (T) -> String? = { null },
    enabled: Boolean = true
) {
    Column(modifier = modifier.selectableGroup()) {
        options.forEach { option ->
            val isSelected = option == selected
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .selectable(
                        selected = isSelected,
                        enabled = enabled,
                        role = Role.RadioButton,
                        onClick = { onSelect(option) }
                    )
                    .defaultMinSize(minHeight = Tokens.Size.rowHeight)
                    .padding(
                        horizontal = Tokens.Space.screenGutter,
                        vertical = Tokens.Space.sm
                    )
                    .semantics(mergeDescendants = true) { },
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(
                    modifier = Modifier
                        .weight(1f)
                        .alpha(if (enabled) 1f else Tokens.DISABLED_ALPHA)
                ) {
                    Text(
                        text = label(option),
                        style = Tokens.Type.rowTitle,
                        color = MaterialTheme.colorScheme.onBackground
                    )
                    description(option)?.let {
                        Spacer(Modifier.height(2.dp))
                        Text(
                            text = it,
                            style = Tokens.Type.rowSubtitle,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
                // Selection is conveyed by the radio role to assistive tech;
                // the mark is a visual echo, not the only signal.
                SelectionMark(selected = isSelected, enabled = enabled)
            }
        }
    }
}

@Composable
private fun SelectionMark(selected: Boolean, enabled: Boolean) {
    val dark = isDark()
    val alpha by animateFloatAsState(
        targetValue = if (selected) 1f else 0f,
        animationSpec = luxuryTween(Tokens.Motion.FAST_MS),
        label = "selectionMark"
    )
    Box(
        modifier = Modifier
            .size(Tokens.Size.icon)
            .clearAndSetSemantics { },
        contentAlignment = Alignment.Center
    ) {
        Icon(
            imageVector = Icons.Outlined.Check,
            contentDescription = null,
            tint = Tokens.Palette.accent(dark),
            modifier = Modifier
                .size(Tokens.Size.icon)
                .alpha(alpha * if (enabled) 1f else Tokens.DISABLED_ALPHA)
        )
    }
}

// ==================== Segmented control ====================

/** Compact single-choice control for short, self-explanatory labels. */
@Composable
fun <T> SegmentedControl(
    options: List<T>,
    selected: T,
    onSelect: (T) -> Unit,
    label: (T) -> String,
    modifier: Modifier = Modifier,
    enabled: Boolean = true
) {
    val dark = isDark()
    Row(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(Tokens.Radius.md))
            .background(Tokens.Palette.storageTrack(dark))
            .padding(Tokens.Space.xs)
            .selectableGroup()
            .alpha(if (enabled) 1f else Tokens.DISABLED_ALPHA),
        horizontalArrangement = Arrangement.spacedBy(Tokens.Space.xs)
    ) {
        options.forEach { option ->
            val isSelected = option == selected
            val bg by animateColorAsState(
                targetValue = if (isSelected) {
                    Tokens.Palette.accent(dark).copy(alpha = if (dark) 0.20f else 0.16f)
                } else {
                    Color.Transparent
                },
                animationSpec = luxuryTween(Tokens.Motion.FAST_MS),
                label = "segmentBg"
            )
            Box(
                modifier = Modifier
                    .weight(1f)
                    .clip(RoundedCornerShape(Tokens.Radius.sm))
                    .background(bg)
                    .selectable(
                        selected = isSelected,
                        enabled = enabled,
                        role = Role.RadioButton,
                        onClick = { onSelect(option) }
                    )
                    .defaultMinSize(minHeight = Tokens.Size.touchTarget)
                    .padding(vertical = Tokens.Space.sm)
                    .semantics(mergeDescendants = true) { },
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = label(option),
                    style = Tokens.Type.rowValue,
                    color = if (isSelected) {
                        Tokens.Palette.accent(dark)
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    }
                )
            }
        }
    }
}

// ==================== Storage bar ====================

data class StorageSegment(val label: String, val bytes: Long, val color: Color)

/**
 * Thin segmented bar for the storage breakdown.
 *
 * Deliberately not a progress bar: it shows composition, not completion. Values
 * are also listed in the legend and summarised for screen readers, so nothing
 * here depends on colour alone.
 */
@Composable
fun StorageBar(
    segments: List<StorageSegment>,
    totalBytes: Long,
    modifier: Modifier = Modifier,
    accessibilitySummary: String? = null
) {
    val dark = isDark()
    val visible = segments.filter { it.bytes > 0 }

    Row(
        modifier = modifier
            .fillMaxWidth()
            .height(Tokens.Size.storageBarHeight)
            .clip(RoundedCornerShape(Tokens.Radius.pill))
            .background(Tokens.Palette.storageTrack(dark))
            .then(
                if (accessibilitySummary != null) {
                    Modifier.semantics { contentDescription = accessibilitySummary }
                } else {
                    Modifier
                }
            )
    ) {
        if (totalBytes <= 0 || visible.isEmpty()) return@Row

        visible.forEach { segment ->
            val fraction = (segment.bytes.toFloat() / totalBytes).coerceIn(0f, 1f)
            if (fraction <= 0f) return@forEach
            val animated by animateFloatAsState(
                targetValue = fraction,
                animationSpec = luxuryTween(Tokens.Motion.SLOW_MS),
                label = "segment_${segment.label}"
            )
            Box(
                modifier = Modifier
                    .weight(animated.coerceAtLeast(0.0001f))
                    .fillMaxWidth()
                    .height(Tokens.Size.storageBarHeight)
                    .background(segment.color)
            )
        }
    }
}

/** Legend entry: swatch, label, and the value in tabular figures. */
@Composable
fun StorageLegendRow(segment: StorageSegment, formattedValue: String) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .defaultMinSize(minHeight = 32.dp)
            .padding(vertical = Tokens.Space.xs)
            .semantics(mergeDescendants = true) { },
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier
                .size(10.dp)
                .clip(RoundedCornerShape(Tokens.Radius.pill))
                .background(segment.color)
        )
        Spacer(Modifier.width(Tokens.Space.md))
        Text(
            text = segment.label,
            style = Tokens.Type.rowSubtitle,
            color = MaterialTheme.colorScheme.onBackground,
            modifier = Modifier.weight(1f)
        )
        Text(
            text = formattedValue,
            style = Tokens.Type.metric,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}
