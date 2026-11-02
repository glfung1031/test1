package com.macrorunner

import android.app.TimePickerDialog
import android.text.format.DateFormat
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import java.time.LocalTime
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle

private data class StepDialogState(val step: Step, val parent: StepPath, val editPath: StepPath?)

private fun defaultStep(t: StepType) = Step(type = t)

private fun iconFor(t: StepType): ImageVector = when (t) {
    StepType.TAP -> Icons.Rounded.TouchApp
    StepType.SWIPE -> Icons.Rounded.SwapHoriz
    StepType.WAIT -> Icons.Rounded.Timer
    StepType.LOOP -> Icons.Rounded.Repeat
}

private fun titleOf(s: Step) = when (s.type) {
    StepType.TAP -> "Tap (${s.x}, ${s.y})"
    StepType.SWIPE -> "Swipe (${s.x}, ${s.y}) → (${s.x2}, ${s.y2})"
    StepType.WAIT -> "Wait ${fmtMs(s.waitMs)}"
    StepType.LOOP -> if (s.loopMode == LoopMode.COUNT) "Repeat ${s.loopCount}×" else "Repeat for ${fmtMs(s.loopDurationMs)}"
}

private fun subtitleOf(s: Step) = when (s.type) {
    StepType.TAP -> "Hold ${s.durationMs} ms"
    StepType.SWIPE -> "Over ${s.durationMs} ms"
    StepType.WAIT -> "Pause between steps"
    StepType.LOOP -> if (s.children.isEmpty()) "Empty — add steps inside" else "${s.children.totalSteps()} steps inside"
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EditorScreen(initial: Macro, isNew: Boolean, onSave: (Macro) -> Unit, onCancel: () -> Unit) {
    val ctx = LocalContext.current
    var name by remember { mutableStateOf(initial.name) }
    var days by remember { mutableStateOf(initial.days) }
    var hour by remember { mutableIntStateOf(initial.hour) }
    var minute by remember { mutableIntStateOf(initial.minute) }
    var second by remember { mutableIntStateOf(initial.second) }
    var showTime by remember { mutableStateOf(false) }
    var steps by remember { mutableStateOf(initial.steps) }
    var pickParent by remember { mutableStateOf<StepPath?>(null) }
    var dialog by remember { mutableStateOf<StepDialogState?>(null) }

    val rows = remember(steps) { steps.flatten() }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            TopAppBar(
                title = { Text(if (isNew) "New macro" else "Edit macro") },
                navigationIcon = { IconButton(onClick = onCancel) { Icon(Icons.Rounded.ArrowBack, "Back") } },
                actions = {
                    Button(
                        enabled = name.isNotBlank(),
                        onClick = { onSave(initial.copy(name = name.trim(), days = days, hour = hour, minute = minute, second = second, steps = steps)) }
                    ) { Text("Save") }
                    Spacer(Modifier.width(12.dp))
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background)
            )
        }
    ) { pad ->
        LazyColumn(
            modifier = Modifier.padding(pad),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            item {
                OutlinedTextField(
                    value = name, onValueChange = { name = it },
                    label = { Text("Macro name") }, singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
            }
            item {
                Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow)) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                        Text("Schedule", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                        DayDots(days, onToggle = { d -> days = if (d in days) days - d else days + d })
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text(
                                    LocalTime.of(hour, minute, second).format(DateTimeFormatter.ofLocalizedTime(FormatStyle.MEDIUM)),
                                    style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold
                                )
                                Text(
                                    if (days.isEmpty()) "No days selected — runs only when you tap Run now" else "Runs on the selected days at this time",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                            FilledTonalButton(onClick = { showTime = true }) { Text("Set time") }
                        }
                    }
                }
            }
            item {
                Row(Modifier.padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text("Steps", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                    FilledTonalButton(onClick = { pickParent = emptyList() }) {
                        Icon(Icons.Rounded.Add, null, Modifier.size(18.dp)); Spacer(Modifier.width(6.dp)); Text("Add step")
                    }
                }
            }
            if (rows.isEmpty()) item {
                Text(
                    "Add taps, swipes, waits and loops. Steps run top to bottom.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(vertical = 16.dp)
                )
            }
            items(rows, key = { it.path.joinToString("-") }) { r ->
                StepRow(
                    r,
                    onEdit = { dialog = StepDialogState(r.step, r.path.dropLast(1), r.path) },
                    onUp = { steps = steps.moveAt(r.path, -1) },
                    onDown = { steps = steps.moveAt(r.path, +1) },
                    onDelete = { steps = steps.removeAt(r.path) },
                    onAddInside = { pickParent = r.path }
                )
            }
            item { Spacer(Modifier.height(48.dp)) }
        }
    }

    if (showTime) {
        TimeDialog(
            hour, minute, second, DateFormat.is24HourFormat(ctx),
            onDismiss = { showTime = false },
            onConfirm = { h, m, s -> hour = h; minute = m; second = s; showTime = false }
        )
    }

    pickParent?.let { parent ->
        AlertDialog(
            onDismissRequest = { pickParent = null },
            title = { Text(if (parent.isEmpty()) "Add step" else "Add step inside loop") },
            text = {
                Column {
                    listOf(
                        Triple(StepType.TAP, "Tap", "Tap an X, Y position"),
                        Triple(StepType.SWIPE, "Swipe", "Drag from one point to another"),
                        Triple(StepType.WAIT, "Wait", "Pause before the next step"),
                        Triple(StepType.LOOP, "Loop", "Repeat a group of steps")
                    ).forEach { (t, title, sub) ->
                        ListItem(
                            headlineContent = { Text(title) },
                            supportingContent = { Text(sub) },
                            leadingContent = { Icon(iconFor(t), null, tint = MaterialTheme.colorScheme.primary) },
                            colors = ListItemDefaults.colors(containerColor = androidx.compose.ui.graphics.Color.Transparent),
                            modifier = Modifier.clip(MaterialTheme.shapes.medium).clickable {
                                dialog = StepDialogState(defaultStep(t), parent, null); pickParent = null
                            }
                        )
                    }
                }
            },
            confirmButton = {},
            dismissButton = { TextButton(onClick = { pickParent = null }) { Text("Cancel") } }
        )
    }

    dialog?.let { d ->
        StepDialog(
            initial = d.step, isEdit = d.editPath != null,
            onDismiss = { dialog = null },
            onConfirm = { s ->
                steps = if (d.editPath != null) steps.replaceAt(d.editPath, s) else steps.addAt(d.parent, s)
                dialog = null
            }
        )
    }
}

@Composable
private fun StepRow(
    r: FlatStep, onEdit: () -> Unit, onUp: () -> Unit, onDown: () -> Unit,
    onDelete: () -> Unit, onAddInside: () -> Unit
) {
    val isLoop = r.step.type == StepType.LOOP
    Card(
        modifier = Modifier.fillMaxWidth().padding(start = (r.depth * 18).dp),
        colors = CardDefaults.cardColors(
            containerColor = if (isLoop) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceContainerHigh
        )
    ) {
        Column(Modifier.clickable(onClick = onEdit).padding(start = 12.dp, top = 8.dp, bottom = 8.dp, end = 4.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    Modifier.size(36.dp).clip(CircleShape).background(MaterialTheme.colorScheme.surface.copy(alpha = 0.6f)),
                    contentAlignment = Alignment.Center
                ) { Icon(iconFor(r.step.type), null, Modifier.size(20.dp), tint = MaterialTheme.colorScheme.primary) }
                Spacer(Modifier.width(10.dp))
                Column(Modifier.weight(1f)) {
                    Text(titleOf(r.step), fontWeight = FontWeight.SemiBold, maxLines = 1)
                    Text(subtitleOf(r.step), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                IconButton(onClick = onUp, enabled = r.index > 0, modifier = Modifier.size(36.dp)) { Icon(Icons.Rounded.KeyboardArrowUp, "Move up") }
                IconButton(onClick = onDown, enabled = r.index < r.siblings - 1, modifier = Modifier.size(36.dp)) { Icon(Icons.Rounded.KeyboardArrowDown, "Move down") }
                IconButton(onClick = onDelete, modifier = Modifier.size(36.dp)) { Icon(Icons.Rounded.Delete, "Delete") }
            }
            if (isLoop) TextButton(onClick = onAddInside) {
                Icon(Icons.Rounded.Add, null, Modifier.size(16.dp)); Spacer(Modifier.width(4.dp)); Text("Add step inside")
            }
        }
    }
}

@Composable
private fun NumField(label: String, value: String, onChange: (String) -> Unit, modifier: Modifier = Modifier, decimal: Boolean = false) {
    OutlinedTextField(
        value = value,
        onValueChange = { v -> onChange(v.filter { it.isDigit() || (decimal && it == '.') }) },
        label = { Text(label) }, singleLine = true, modifier = modifier,
        keyboardOptions = KeyboardOptions(keyboardType = if (decimal) KeyboardType.Decimal else KeyboardType.Number)
    )
}

@Composable
private fun TimeDialog(
    hour: Int, minute: Int, second: Int, is24: Boolean,
    onDismiss: () -> Unit, onConfirm: (Int, Int, Int) -> Unit
) {
    var h by remember { mutableStateOf(if (is24) hour.toString() else (if (hour % 12 == 0) 12 else hour % 12).toString()) }
    var m by remember { mutableStateOf(minute.toString().padStart(2, '0')) }
    var s by remember { mutableStateOf(second.toString().padStart(2, '0')) }
    var pm by remember { mutableStateOf(hour >= 12) }

    val hv = h.toIntOrNull(); val mv = m.toIntOrNull(); val sv = s.toIntOrNull()
    val hour24: Int? = when {
        hv == null -> null
        is24 -> if (hv in 0..23) hv else null
        else -> if (hv in 1..12) (hv % 12) + (if (pm) 12 else 0) else null
    }
    val valid = hour24 != null && mv != null && mv in 0..59 && sv != null && sv in 0..59

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Trigger time") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    NumField(if (is24) "Hour (0–23)" else "Hour", h, { h = it.take(2) }, Modifier.weight(1f))
                    NumField("Min", m, { m = it.take(2) }, Modifier.weight(1f))
                    NumField("Sec", s, { s = it.take(2) }, Modifier.weight(1f))
                }
                if (!is24) Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(selected = !pm, onClick = { pm = false }, label = { Text("AM") })
                    FilterChip(selected = pm, onClick = { pm = true }, label = { Text("PM") })
                }
            }
        },
        confirmButton = {
            TextButton(enabled = valid, onClick = { onConfirm(hour24!!, mv!!, sv!!) }) { Text("OK") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } }
    )
}

private fun secStr(ms: Long) = (ms / 1000.0).toString().removeSuffix(".0")

@Composable
private fun StepDialog(initial: Step, isEdit: Boolean, onDismiss: () -> Unit, onConfirm: (Step) -> Unit) {
    val dm = LocalContext.current.resources.displayMetrics
    var x by remember { mutableStateOf(initial.x.toString()) }
    var y by remember { mutableStateOf(initial.y.toString()) }
    var x2 by remember { mutableStateOf(initial.x2.toString()) }
    var y2 by remember { mutableStateOf(initial.y2.toString()) }
    var dur by remember { mutableStateOf(initial.durationMs.toString()) }
    var waitSec by remember { mutableStateOf(secStr(initial.waitMs)) }
    var mode by remember { mutableStateOf(initial.loopMode) }
    var count by remember { mutableStateOf(initial.loopCount.toString()) }
    var loopSec by remember { mutableStateOf(secStr(initial.loopDurationMs)) }

    val built: Step? = when (initial.type) {
        StepType.TAP -> {
            val a = x.toIntOrNull(); val b = y.toIntOrNull()
            if (a == null || b == null) null else initial.copy(x = a, y = b, durationMs = (dur.toLongOrNull() ?: 50L).coerceIn(1, 60_000))
        }
        StepType.SWIPE -> {
            val a = x.toIntOrNull(); val b = y.toIntOrNull(); val c = x2.toIntOrNull(); val d = y2.toIntOrNull()
            if (a == null || b == null || c == null || d == null) null
            else initial.copy(x = a, y = b, x2 = c, y2 = d, durationMs = (dur.toLongOrNull() ?: 300L).coerceIn(1, 60_000))
        }
        StepType.WAIT -> waitSec.toDoubleOrNull()?.let { initial.copy(waitMs = (it * 1000).toLong().coerceAtLeast(0)) }
        StepType.LOOP -> when (mode) {
            LoopMode.COUNT -> count.toIntOrNull()?.takeIf { it >= 1 }?.let { initial.copy(loopMode = mode, loopCount = it) }
            LoopMode.DURATION -> loopSec.toDoubleOrNull()?.takeIf { it > 0 }
                ?.let { initial.copy(loopMode = mode, loopDurationMs = (it * 1000).toLong()) }
        }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text((if (isEdit) "Edit " else "New ") + initial.type.name.lowercase()) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                when (initial.type) {
                    StepType.TAP -> {
                        Text("Screen is ${dm.widthPixels} × ${dm.heightPixels} px", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            NumField("X", x, { x = it }, Modifier.weight(1f)); NumField("Y", y, { y = it }, Modifier.weight(1f))
                        }
                        NumField("Hold time (ms)", dur, { dur = it }, Modifier.fillMaxWidth())
                    }
                    StepType.SWIPE -> {
                        Text("Screen is ${dm.widthPixels} × ${dm.heightPixels} px", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            NumField("From X", x, { x = it }, Modifier.weight(1f)); NumField("From Y", y, { y = it }, Modifier.weight(1f))
                        }
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            NumField("To X", x2, { x2 = it }, Modifier.weight(1f)); NumField("To Y", y2, { y2 = it }, Modifier.weight(1f))
                        }
                        NumField("Duration (ms)", dur, { dur = it }, Modifier.fillMaxWidth())
                    }
                    StepType.WAIT -> NumField("Seconds", waitSec, { waitSec = it }, Modifier.fillMaxWidth(), decimal = true)
                    StepType.LOOP -> {
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            FilterChip(selected = mode == LoopMode.COUNT, onClick = { mode = LoopMode.COUNT }, label = { Text("Number of times") })
                            FilterChip(selected = mode == LoopMode.DURATION, onClick = { mode = LoopMode.DURATION }, label = { Text("For a duration") })
                        }
                        if (mode == LoopMode.COUNT) NumField("Repeat count", count, { count = it }, Modifier.fillMaxWidth())
                        else NumField("Seconds", loopSec, { loopSec = it }, Modifier.fillMaxWidth(), decimal = true)
                    }
                }
            }
        },
        confirmButton = { TextButton(enabled = built != null, onClick = { built?.let(onConfirm) }) { Text(if (isEdit) "Update" else "Add") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } }
    )
}
