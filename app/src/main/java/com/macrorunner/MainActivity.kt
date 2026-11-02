package com.macrorunner

import android.app.Application
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.animation.Crossfade
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
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
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.AndroidViewModel
import java.time.format.DateTimeFormatter
import java.util.Locale

class MacroViewModel(app: Application) : AndroidViewModel(app) {
    val macros = mutableStateListOf<Macro>().also { it.addAll(MacroStorage.load(app)) }

    private fun persist() {
        MacroStorage.save(getApplication(), macros.toList())
        Scheduler.rescheduleAll(getApplication())
    }

    fun upsert(m: Macro) {
        val i = macros.indexOfFirst { it.id == m.id }
        if (i >= 0) macros[i] = m else macros.add(m)
        persist()
    }

    fun setEnabled(id: String, on: Boolean) {
        val i = macros.indexOfFirst { it.id == id }
        if (i >= 0) { macros[i] = macros[i].copy(enabled = on); persist() }
    }

    fun delete(id: String) {
        MacroEngine.stop(id)
        Scheduler.cancel(getApplication(), id)
        RunLog.clear(getApplication(), id)
        macros.removeAll { it.id == id }
        persist()
    }
}

class MainActivity : ComponentActivity() {
    private val vm: MacroViewModel by viewModels()
    private val tick = mutableIntStateOf(0)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        RunLog.load(this)
        setContent { MacroTheme { AppRoot(vm, tick.intValue) } }
    }

    override fun onResume() { super.onResume(); tick.intValue++ }
}

@Composable
fun AppRoot(vm: MacroViewModel, tick: Int) {
    var editing by remember { mutableStateOf<Macro?>(null) }
    var isNew by remember { mutableStateOf(false) }
    BackHandler(enabled = editing != null) { editing = null }

    Surface(color = MaterialTheme.colorScheme.background, modifier = Modifier.fillMaxSize()) {
        Crossfade(targetState = editing, label = "screen") { m ->
            if (m == null) {
                HomeScreen(vm, tick,
                    onNew = { isNew = true; editing = Macro() },
                    onEdit = { isNew = false; editing = it })
            } else {
                EditorScreen(m, isNew,
                    onSave = { vm.upsert(it); editing = null },
                    onCancel = { editing = null })
            }
        }
    }
}

// ---------------------------------------------------------------- Home

@Composable
fun HomeScreen(vm: MacroViewModel, tick: Int, onNew: () -> Unit, onEdit: (Macro) -> Unit) {
    val ctx = LocalContext.current
    val connected by MacroAccessibilityService.connected.collectAsState()
    val running by MacroEngine.running.collectAsState()
    val lastRuns by RunLog.lastRun.collectAsState()
    val canExact = remember(tick) { Scheduler.canExact(ctx) }
    var deleteTarget by remember { mutableStateOf<Macro?>(null) }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        floatingActionButton = {
            ExtendedFloatingActionButton(
                onClick = onNew,
                icon = { Icon(Icons.Rounded.Add, null) },
                text = { Text("New macro") },
                containerColor = MaterialTheme.colorScheme.primary,
                contentColor = MaterialTheme.colorScheme.onPrimary
            )
        }
    ) { pad ->
        LazyColumn(
            modifier = Modifier.padding(pad),
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            item {
                Column(Modifier.padding(top = 24.dp, bottom = 4.dp, start = 4.dp)) {
                    Text("Macros", style = MaterialTheme.typography.displaySmall, fontWeight = FontWeight.Bold)
                    Text(
                        "${vm.macros.count { it.enabled }} active · ${vm.macros.size} total",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
            if (!connected) item {
                Banner(
                    Icons.Rounded.Accessibility, "Turn on the accessibility service",
                    "Macro Runner needs it to perform taps. Find “Macro Runner” under Installed apps and switch it on.",
                    "Open settings"
                ) { ctx.startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)) }
            }
            if (!canExact && Build.VERSION.SDK_INT >= 31) item {
                Banner(
                    Icons.Rounded.Schedule, "Allow exact alarms",
                    "Without this, scheduled macros may start a few minutes late.",
                    "Allow"
                ) {
                    ctx.startActivity(
                        Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM, Uri.parse("package:${ctx.packageName}"))
                    )
                }
            }
            if (vm.macros.isEmpty()) item { EmptyState() }

            items(vm.macros, key = { it.id }) { m ->
                MacroCard(
                    m, running = m.id in running, lastRun = lastRuns[m.id],
                    onToggle = { vm.setEnabled(m.id, it) },
                    onRun = {
                        if (!MacroEngine.start(m)) Toast.makeText(
                            ctx, "Enable the accessibility service first", Toast.LENGTH_LONG
                        ).show()
                    },
                    onStop = { MacroEngine.stop(m.id) },
                    onEdit = { onEdit(m) },
                    onDelete = { deleteTarget = m }
                )
            }
            item { Spacer(Modifier.height(96.dp)) }
        }
    }

    deleteTarget?.let { t ->
        AlertDialog(
            onDismissRequest = { deleteTarget = null },
            title = { Text("Delete “${t.name}”?") },
            text = { Text("This can’t be undone.") },
            confirmButton = { TextButton(onClick = { vm.delete(t.id); deleteTarget = null }) { Text("Delete") } },
            dismissButton = { TextButton(onClick = { deleteTarget = null }) { Text("Cancel") } }
        )
    }
}

@Composable
private fun Banner(icon: ImageVector, title: String, body: String, action: String, onClick: () -> Unit) {
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.tertiaryContainer)) {
        Column(Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(icon, null, tint = MaterialTheme.colorScheme.onTertiaryContainer)
                Spacer(Modifier.width(10.dp))
                Text(title, fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.onTertiaryContainer)
            }
            Spacer(Modifier.height(6.dp))
            Text(body, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onTertiaryContainer)
            TextButton(onClick = onClick, modifier = Modifier.align(Alignment.End)) { Text(action) }
        }
    }
}

@Composable
private fun EmptyState() {
    Column(
        Modifier.fillMaxWidth().padding(vertical = 56.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Box(
            Modifier.size(84.dp).clip(CircleShape).background(MaterialTheme.colorScheme.primaryContainer),
            contentAlignment = Alignment.Center
        ) { Icon(Icons.Rounded.TouchApp, null, Modifier.size(40.dp), tint = MaterialTheme.colorScheme.primary) }
        Spacer(Modifier.height(16.dp))
        Text("No macros yet", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
        Text(
            "Tap “New macro” to record taps, waits and loops\nand run them on a schedule.",
            textAlign = TextAlign.Center,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@Composable
private fun MacroCard(
    m: Macro, running: Boolean, lastRun: Long?,
    onToggle: (Boolean) -> Unit, onRun: () -> Unit, onStop: () -> Unit,
    onEdit: () -> Unit, onDelete: () -> Unit
) {
    val next = remember(m) {
        if (m.enabled) Scheduler.nextTrigger(m)?.format(DateTimeFormatter.ofPattern("EEE h:mm:ss a", Locale.getDefault())) else null
    }
    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp)
    ) {
        Column(Modifier.padding(18.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(m.name, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
                    Text(
                        if (m.days.isEmpty()) "Manual only" else if (m.days.size == 7) "Every day · ${m.timeLabel()}" else m.timeLabel(),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Switch(checked = m.enabled, onCheckedChange = onToggle)
            }
            if (m.days.isNotEmpty() && m.days.size < 7) {
                Spacer(Modifier.height(12.dp))
                DayDots(m.days, compact = true)
            }
            Spacer(Modifier.height(10.dp))
            Text(
                buildString {
                    append("${m.steps.totalSteps()} steps")
                    if (running) append(" · running…") else if (next != null) append(" · next $next")
                },
                style = MaterialTheme.typography.bodySmall,
                color = if (running) MaterialTheme.colorScheme.secondary else MaterialTheme.colorScheme.onSurfaceVariant
            )
            Text(
                lastRunLabel(lastRun),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.height(10.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (running) FilledTonalButton(onClick = onStop) {
                    Icon(Icons.Rounded.Stop, null, Modifier.size(18.dp)); Spacer(Modifier.width(6.dp)); Text("Stop")
                } else FilledTonalButton(onClick = onRun, enabled = m.steps.isNotEmpty()) {
                    Icon(Icons.Rounded.PlayArrow, null, Modifier.size(18.dp)); Spacer(Modifier.width(6.dp)); Text("Run now")
                }
                Spacer(Modifier.weight(1f))
                IconButton(onClick = onEdit) { Icon(Icons.Rounded.Edit, "Edit") }
                IconButton(onClick = onDelete) { Icon(Icons.Rounded.Delete, "Delete") }
            }
        }
    }
}

private fun lastRunLabel(at: Long?): String {
    if (at == null) return "Last run: never"
    val dt = java.time.Instant.ofEpochMilli(at).atZone(java.time.ZoneId.systemDefault())
    val today = java.time.LocalDate.now()
    val time = dt.format(DateTimeFormatter.ofPattern("h:mm:ss a", Locale.getDefault()))
    return when (dt.toLocalDate()) {
        today -> "Last run: today, $time"
        today.minusDays(1) -> "Last run: yesterday, $time"
        else -> "Last run: " + dt.format(DateTimeFormatter.ofPattern("EEE, MMM d", Locale.getDefault())) + ", $time"
    }
}

/** Mon..Sun pills. Pass [onToggle] to make them tappable. */
@Composable
fun DayDots(days: Set<Int>, onToggle: ((Int) -> Unit)? = null, compact: Boolean = false) {
    val labels = listOf("M", "T", "W", "T", "F", "S", "S")
    Row(
        modifier = if (compact) Modifier else Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(if (compact) 6.dp else 8.dp)
    ) {
        labels.forEachIndexed { i, l ->
            val d = i + 1
            val on = d in days
            var mod: Modifier = if (compact) Modifier.size(26.dp) else Modifier.weight(1f).aspectRatio(1f)
            mod = mod.clip(CircleShape).background(
                if (on) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceVariant
            )
            if (onToggle != null) mod = mod.clickable { onToggle(d) }
            Box(mod, contentAlignment = Alignment.Center) {
                Text(
                    l, fontWeight = FontWeight.SemiBold,
                    style = if (compact) MaterialTheme.typography.labelSmall else MaterialTheme.typography.labelLarge,
                    color = if (on) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}
