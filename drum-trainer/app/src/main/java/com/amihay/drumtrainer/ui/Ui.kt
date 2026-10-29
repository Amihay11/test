package com.amihay.drumtrainer.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.FlowRowScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp

/** Side margin of every page. */
val PagePadding = 20.dp
val CardShape = RoundedCornerShape(20.dp)
val ButtonShape = RoundedCornerShape(16.dp)

/**
 * A full page: a top bar with a back arrow (when [onBack] is set), scrolling content,
 * and an optional [bottomBar] pinned under it for the main action.
 */
@Composable
fun Page(
    title: String,
    onBack: (() -> Unit)?,
    subtitle: String? = null,
    actions: @Composable RowScope.() -> Unit = {},
    bottomBar: (@Composable ColumnScope.() -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    Column(Modifier.fillMaxSize()) {
        TopBar(title, onBack, subtitle, actions)
        Column(
            Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = PagePadding),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Spacer(Modifier.height(4.dp))
            content()
            Spacer(Modifier.height(20.dp))
        }
        if (bottomBar != null) {
            HorizontalDivider(color = Palette.outline.copy(alpha = 0.6f))
            Column(
                Modifier.fillMaxWidth().background(Palette.background).padding(horizontal = PagePadding, vertical = 12.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
                content = bottomBar,
            )
        }
    }
}

@Composable
fun TopBar(title: String, onBack: (() -> Unit)?, subtitle: String? = null, actions: @Composable RowScope.() -> Unit = {}) {
    Row(
        Modifier.fillMaxWidth().heightIn(min = 64.dp).padding(horizontal = 4.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (onBack != null) {
            IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "חזרה") }
        } else {
            Spacer(Modifier.size(16.dp))
        }
        Column(Modifier.weight(1f).padding(horizontal = 4.dp)) {
            Text(title, style = MaterialTheme.typography.titleLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (subtitle != null) {
                Text(subtitle, style = MaterialTheme.typography.bodySmall, color = Palette.muted, maxLines = 1,
                    overflow = TextOverflow.Ellipsis)
            }
        }
        actions()
    }
}

/** The top of a main tab: a big title and optional actions. */
@Composable
fun TabHeader(title: String, subtitle: String? = null, actions: @Composable RowScope.() -> Unit = {}) {
    Row(Modifier.fillMaxWidth().padding(top = 20.dp, bottom = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.headlineLarge)
            if (subtitle != null) Text(subtitle, style = MaterialTheme.typography.bodyMedium, color = Palette.muted)
        }
        actions()
    }
}

/** A rounded surface that groups related content. */
@Composable
fun Panel(
    modifier: Modifier = Modifier,
    onClick: (() -> Unit)? = null,
    color: Color = Palette.surface,
    padding: Dp = 16.dp,
    spacing: Dp = 12.dp,
    content: @Composable ColumnScope.() -> Unit,
) {
    val base = modifier.fillMaxWidth().clip(CardShape).background(color)
        .border(1.dp, Palette.outline.copy(alpha = 0.5f), CardShape)
    Column(
        (if (onClick != null) base.clickable(onClick = onClick) else base).padding(padding),
        verticalArrangement = Arrangement.spacedBy(spacing),
        content = content,
    )
}

/** A panel with a title row (and optional trailing content such as a button). */
@Composable
fun Section(
    title: String,
    modifier: Modifier = Modifier,
    trailing: @Composable RowScope.() -> Unit = {},
    content: @Composable ColumnScope.() -> Unit,
) {
    Panel(modifier) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
            trailing()
        }
        content()
    }
}

/** A small heading between groups of content. */
@Composable
fun SectionLabel(text: String, modifier: Modifier = Modifier, trailing: @Composable RowScope.() -> Unit = {}) {
    Row(modifier.fillMaxWidth().padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(text, style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
        trailing()
    }
}

/** A labelled control: the label above, the control below. */
@Composable
fun Field(label: String, content: @Composable ColumnScope.() -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(label, style = MaterialTheme.typography.labelLarge, color = Palette.muted)
        content()
    }
}

/** One choice out of a few, as a segmented control. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun <T> Segmented(options: List<Pair<T, String>>, selected: T, onSelect: (T) -> Unit, modifier: Modifier = Modifier) {
    SingleChoiceSegmentedButtonRow(modifier.fillMaxWidth()) {
        options.forEachIndexed { i, (value, label) ->
            SegmentedButton(
                selected = value == selected,
                onClick = { onSelect(value) },
                shape = SegmentedButtonDefaults.itemShape(i, options.size),
                icon = {},
                colors = SegmentedButtonDefaults.colors(
                    activeContainerColor = Palette.accentSoft,
                    activeContentColor = Palette.accent,
                    activeBorderColor = Palette.accent.copy(alpha = 0.6f),
                    inactiveContainerColor = Color.Transparent,
                    inactiveContentColor = Palette.text,
                    inactiveBorderColor = Palette.outline,
                ),
            ) { Text(label, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.labelLarge) }
        }
    }
}

/** A selectable chip for longer lists of choices. */
@Composable
fun ChoiceChip(label: String, selected: Boolean, onClick: () -> Unit) {
    Text(
        label,
        style = MaterialTheme.typography.labelLarge,
        color = if (selected) Palette.accent else Palette.text,
        modifier = Modifier
            .clip(RoundedCornerShape(12.dp))
            .background(if (selected) Palette.accentSoft else Palette.surfaceHigh)
            .border(1.dp, if (selected) Palette.accent.copy(alpha = 0.6f) else Color.Transparent, RoundedCornerShape(12.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 9.dp),
    )
}

@Composable
fun PrimaryButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    icon: ImageVector? = null,
    enabled: Boolean = true,
) {
    Button(
        onClick = onClick, enabled = enabled, shape = ButtonShape,
        modifier = modifier.fillMaxWidth().height(56.dp),
        contentPadding = PaddingValues(horizontal = 20.dp),
    ) {
        if (icon != null) {
            Icon(icon, contentDescription = null, modifier = Modifier.size(22.dp))
            Spacer(Modifier.size(8.dp))
        }
        Text(text, style = MaterialTheme.typography.titleMedium)
    }
}

@Composable
fun SecondaryButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    icon: ImageVector? = null,
    enabled: Boolean = true,
) {
    FilledTonalButton(
        onClick = onClick, enabled = enabled, shape = ButtonShape, modifier = modifier.height(48.dp),
        colors = ButtonDefaults.filledTonalButtonColors(containerColor = Palette.surfaceHigh, contentColor = Palette.text),
        contentPadding = PaddingValues(horizontal = 16.dp),
    ) {
        if (icon != null) {
            Icon(icon, contentDescription = null, modifier = Modifier.size(20.dp))
            Spacer(Modifier.size(6.dp))
        }
        Text(text, style = MaterialTheme.typography.labelLarge, maxLines = 1)
    }
}

@Composable
fun QuietButton(text: String, onClick: () -> Unit, modifier: Modifier = Modifier, color: Color = Palette.text) {
    OutlinedButton(
        onClick = onClick, shape = ButtonShape, modifier = modifier.height(48.dp),
        border = androidx.compose.foundation.BorderStroke(1.dp, Palette.outline),
        colors = ButtonDefaults.outlinedButtonColors(contentColor = color),
    ) { Text(text, style = MaterialTheme.typography.labelLarge, maxLines = 1) }
}

/** Tempo: a big number between − and +, with ±4 steps for bigger jumps. */
@Composable
fun TempoControl(bpm: Int, onChange: (Int) -> Unit, modifier: Modifier = Modifier, caption: String? = null, enabled: Boolean = true) {
    fun set(v: Int) = onChange(v.coerceIn(30, 260))
    Column(modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(6.dp)) {
        // A numeric control: slower on the left, faster on the right.
        CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                StepChip("−4", enabled) { set(bpm - 4) }
                FilledTonalIconButton(onClick = { set(bpm - 1) }, enabled = enabled, modifier = Modifier.size(52.dp)) {
                    Text("−", style = MaterialTheme.typography.headlineMedium)
                }
                Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.padding(horizontal = 6.dp)) {
                    Text("$bpm", style = MaterialTheme.typography.displaySmall)
                    Text("BPM", style = MaterialTheme.typography.labelMedium, color = Palette.muted)
                }
                FilledTonalIconButton(onClick = { set(bpm + 1) }, enabled = enabled, modifier = Modifier.size(52.dp)) {
                    Text("+", style = MaterialTheme.typography.headlineMedium)
                }
                StepChip("+4", enabled) { set(bpm + 4) }
            }
        }
        if (caption != null) {
            Text(caption, style = MaterialTheme.typography.bodySmall, color = Palette.muted, textAlign = TextAlign.Center)
        }
    }
}

@Composable
private fun StepChip(label: String, enabled: Boolean, onClick: () -> Unit) {
    Text(
        label,
        style = MaterialTheme.typography.labelLarge,
        color = if (enabled) Palette.muted else Palette.pending,
        modifier = Modifier.clip(RoundedCornerShape(10.dp)).clickable(enabled = enabled, onClick = onClick)
            .border(1.dp, Palette.outline, RoundedCornerShape(10.dp)).padding(horizontal = 10.dp, vertical = 8.dp),
    )
}

/** A small coloured label. */
@Composable
fun Pill(text: String, color: Color = Palette.accent) {
    Text(
        text,
        style = MaterialTheme.typography.labelMedium,
        color = color,
        modifier = Modifier.clip(RoundedCornerShape(50)).background(color.copy(alpha = 0.15f))
            .padding(horizontal = 10.dp, vertical = 4.dp),
    )
}

/** A thin progress bar. */
@Composable
fun Meter(fraction: Float, modifier: Modifier = Modifier, color: Color = Palette.accent) {
    // Fills from the start of the line: the right side, in Hebrew.
    Box(modifier.fillMaxWidth().height(6.dp).clip(RoundedCornerShape(3.dp)).background(Palette.pending)) {
        Box(Modifier.fillMaxWidth(fraction.coerceIn(0f, 1f)).height(6.dp).clip(RoundedCornerShape(3.dp)).background(color))
    }
}

/** A row in a list of settings or links: title, description, and a trailing control. */
@Composable
fun ListRow(
    title: String,
    description: String? = null,
    icon: ImageVector? = null,
    onClick: (() -> Unit)? = null,
    trailing: @Composable RowScope.() -> Unit = {
        if (onClick != null) Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = null, tint = Palette.muted)
    },
) {
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp))
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
            .padding(vertical = 10.dp, horizontal = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        if (icon != null) IconBadge(icon)
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            if (description != null) Text(description, style = MaterialTheme.typography.bodyMedium, color = Palette.muted)
        }
        trailing()
    }
}

@Composable
fun IconBadge(icon: ImageVector, color: Color = Palette.accent, size: Dp = 40.dp) {
    Box(Modifier.size(size).clip(RoundedCornerShape(12.dp)).background(color.copy(alpha = 0.15f)), contentAlignment = Alignment.Center) {
        Icon(icon, contentDescription = null, tint = color, modifier = Modifier.size(size * 0.55f))
    }
}

/** A notice: a warning or a piece of news, optionally leading somewhere. */
@Composable
fun Banner(text: String, color: Color, onClick: (() -> Unit)? = null) {
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(color.copy(alpha = 0.12f))
            .border(1.dp, color.copy(alpha = 0.3f), RoundedCornerShape(16.dp))
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
            .padding(14.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Icon(if (color == Palette.off || color == Palette.bad) Icons.Filled.Warning else Icons.Filled.Info,
            contentDescription = null, tint = color, modifier = Modifier.size(22.dp))
        Text(text, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
        if (onClick != null) Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = null, tint = color)
    }
}

/** Two tiles per row. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun StatGrid(content: @Composable FlowRowScope.() -> Unit) {
    FlowRow(
        Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
        maxItemsInEachRow = 2,
        content = content,
    )
}

/** A dot for each of [total] steps, the first [done] filled. */
@Composable
fun Dots(done: Int, total: Int, color: Color = Palette.accent, current: Int = -1) {
    CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
            repeat(total) { i ->
                val size = if (i == current) 12.dp else 8.dp
                Box(Modifier.size(size).background(if (i < done || i == current) color else Palette.pending, CircleShape))
            }
        }
    }
}
