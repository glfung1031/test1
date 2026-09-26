package com.example.stocktracker

import android.Manifest
import android.os.Build
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Scheduler.apply(this, Store.settings(this).intervalSec)
        setContent { StockTrackerTheme { App() } }
    }
}

@Composable
fun StockTrackerTheme(content: @Composable () -> Unit) {
    val scheme = lightColorScheme(
        primary = Color(0xFF1769AA),
        secondary = Color(0xFF4F6475),
        tertiary = Color(0xFF7B4B94),
        surface = Color(0xFFF8FAFC),
        background = Color(0xFFF4F7FA)
    )
    MaterialTheme(colorScheme = scheme, typography = Typography(), content = content)
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun App() {
    val ctx = LocalContext.current
    var items by remember { mutableStateOf(Store.items(ctx)) }
    var showAdd by remember { mutableStateOf(false) }
    var showSettings by remember { mutableStateOf(false) }
    val configuration = LocalConfiguration.current
    val width = configuration.screenWidthDp.dp
    // At the user's 3168x1440 landscape display this produces five cards across.
    // Smaller phones/tablets automatically fall back to fewer columns.
    val columns = when {
        width >= 900.dp -> 5
        width >= 700.dp -> 4
        width >= 500.dp -> 3
        width >= 360.dp -> 2
        else -> 1
    }

    LaunchedEffect(Unit) {
        while (true) {
            items = Store.items(ctx)
            delay(2000)
        }
    }

    val permLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {}
    LaunchedEffect(Unit) { if (Build.VERSION.SDK_INT >= 33) permLauncher.launch(Manifest.permission.POST_NOTIFICATIONS) }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            CenterAlignedTopAppBar(
                title = {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text("Stock Tracker", fontWeight = FontWeight.Bold)
                        Text("Amazon • Target • Pokémon Center • Walmart", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                },
                navigationIcon = {
                    IconButton(onClick = { CheckWorker.runNow(ctx); Toast.makeText(ctx, "Checking now…", Toast.LENGTH_SHORT).show() }) {
                        Icon(Icons.Default.Refresh, "Check now")
                    }
                },
                actions = {
                    IconButton(onClick = { showSettings = true }) { Icon(Icons.Default.Settings, "Settings") }
                }
            )
        },
        floatingActionButton = {
            ExtendedFloatingActionButton(
                onClick = { showAdd = true },
                icon = { Icon(Icons.Default.Add, null) },
                text = { Text("Add item") }
            )
        }
    ) { pad ->
        if (items.isEmpty()) {
            Box(Modifier.padding(pad).fillMaxSize(), Alignment.Center) {
                ElevatedCard(shape = RoundedCornerShape(24.dp)) {
                    Column(Modifier.padding(32.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                        Icon(Icons.Default.Add, null, modifier = Modifier.size(42.dp), tint = MaterialTheme.colorScheme.primary)
                        Spacer(Modifier.height(10.dp))
                        Text("Start tracking products", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                        Text("Add an Amazon or Target product URL to begin.", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
        } else {
            LazyVerticalGrid(
                columns = GridCells.Fixed(columns),
                modifier = Modifier.padding(pad).fillMaxSize(),
                contentPadding = PaddingValues(start = 14.dp, end = 14.dp, top = 10.dp, bottom = 100.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                items(items, key = { it.id }) { item -> ItemCard(item) { items = Store.items(ctx) } }
            }
        }
    }
    if (showAdd) AddDialog { showAdd = false; items = Store.items(ctx) }
    if (showSettings) SettingsDialog { showSettings = false }
}

@Composable
fun ItemCard(item: Item, refresh: () -> Unit) {
    val ctx = LocalContext.current
    var editing by remember { mutableStateOf(false) }
    val retailer = Checker.retailer(item.url)
    val statusColor = when (item.inStock) {
        true -> Color(0xFF16803C)
        false -> MaterialTheme.colorScheme.error
        null -> MaterialTheme.colorScheme.onSurfaceVariant
    }
    val statusText = when (item.inStock) {
        true -> "IN STOCK"
        false -> "OUT OF STOCK"
        null -> "CHECKING"
    }

    ElevatedCard(
        modifier = Modifier.fillMaxWidth().heightIn(min = 190.dp),
        shape = RoundedCornerShape(18.dp),
        elevation = CardDefaults.elevatedCardElevation(defaultElevation = 2.dp)
    ) {
        Column(Modifier.padding(14.dp).fillMaxWidth()) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Surface(shape = RoundedCornerShape(8.dp), color = MaterialTheme.colorScheme.primaryContainer) {
                    Text(retailer, modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp), style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.onPrimaryContainer)
                }
                Spacer(Modifier.weight(1f))
                Switch(checked = item.enabled, onCheckedChange = { on ->
                    Store.mutate(ctx) { l -> l.map { if (it.id == item.id) it.copy(enabled = on) else it } }
                    refresh()
                }, modifier = Modifier.height(32.dp))
            }
            Spacer(Modifier.height(9.dp))
            Text(item.name, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold, maxLines = 3)
            Spacer(Modifier.height(8.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Surface(shape = RoundedCornerShape(50), color = statusColor.copy(alpha = .12f)) {
                    Text(statusText, modifier = Modifier.padding(horizontal = 9.dp, vertical = 5.dp), color = statusColor, style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold)
                }
                Spacer(Modifier.width(8.dp))
                Text(
                    item.lastPrice?.let { "$" + "%.2f".format(it) } ?: "—",
                    modifier = Modifier
                        .weight(1f)
                        .padding(start = 2.dp),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    maxLines = 1,
                    softWrap = false,
                    overflow = TextOverflow.Ellipsis,
                    textAlign = TextAlign.End
                )
            }
            Spacer(Modifier.height(5.dp))
            Text("Alert ≤ $${"%.2f".format(item.maxPrice)}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (item.lastChecked > 0) {
                Text("Checked " + SimpleDateFormat("MMM d, h:mm a", Locale.US).format(Date(item.lastChecked)), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            if (item.note.isNotBlank()) {
                Spacer(Modifier.height(4.dp))
                Text(item.note, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.labelSmall, maxLines = 2)
            }
            Spacer(Modifier.weight(1f))
            Row(horizontalArrangement = Arrangement.End, modifier = Modifier.fillMaxWidth()) {
                IconButton(onClick = { editing = true }) { Icon(Icons.Default.Edit, "Edit price") }
                IconButton(onClick = { Store.mutate(ctx) { l -> l.filter { it.id != item.id } }; refresh() }) { Icon(Icons.Default.DeleteOutline, "Remove") }
            }
        }
    }
    if (editing) {
        var t by remember { mutableStateOf("%.2f".format(item.maxPrice)) }
        AlertDialog(
            onDismissRequest = { editing = false },
            title = { Text("Max alert price") },
            text = { OutlinedTextField(t, { t = it }, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal), prefix = { Text("$") }, singleLine = true) },
            confirmButton = { TextButton({ t.toDoubleOrNull()?.let { p -> Store.mutate(ctx) { l -> l.map { if (it.id == item.id) it.copy(maxPrice = p, alerted = false) else it } } }; editing = false; refresh() }) { Text("Save") } }
        )
    }
}

@Composable
fun AddDialog(onClose: () -> Unit) {
    val ctx = LocalContext.current
    var name by remember { mutableStateOf("") }
    var url by remember { mutableStateOf("") }
    var price by remember { mutableStateOf("") }
    AlertDialog(onDismissRequest = onClose, title = { Text("Add item") },
        text = { Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(name, { name = it }, label = { Text("Name") }, singleLine = true)
            OutlinedTextField(url, { url = it.trim() }, label = { Text("Product URL (Amazon, Target, Pokémon Center, Walmart)") })
            OutlinedTextField(price, { price = it }, label = { Text("Alert at or below") }, prefix = { Text("$") }, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal), singleLine = true)
        } },
        confirmButton = { TextButton({
            val p = price.toDoubleOrNull()
            if (url.startsWith("http") && p != null) {
                Store.mutate(ctx) { it + Item(name = name.ifBlank { Checker.retailer(url) + " item" }, url = url, maxPrice = p) }
                CheckWorker.runNow(ctx); onClose()
            } else Toast.makeText(ctx, "Need a valid https URL and price", Toast.LENGTH_SHORT).show()
        }) { Text("Add") } },
        dismissButton = { TextButton(onClose) { Text("Cancel") } })
}

@Composable
fun SettingsDialog(onClose: () -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val s0 = remember { Store.settings(ctx) }
    val steps = (60..120 step 5).toList()
    var idx by remember { mutableStateOf(steps.indexOfFirst { it >= s0.intervalSec }.coerceAtLeast(0).toFloat()) }
    val intervalSec = steps[idx.toInt()]
    var jitter by remember { mutableStateOf(s0.jitterSec.toFloat()) }
    var emailOn by remember { mutableStateOf(s0.emailOn) }
    var user by remember { mutableStateOf(s0.smtpUser) }
    var pass by remember { mutableStateOf(s0.smtpPass) }
    var to by remember { mutableStateOf(s0.emailTo) }
    fun current() = Settings(intervalSec, jitter.toInt(), emailOn, user.trim(), pass.trim(), to.trim())
    AlertDialog(onDismissRequest = onClose, title = { Text("Settings") },
        text = { Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text("Check every ${intervalSec / 60}:${"%02d".format(intervalSec % 60)}")
            Slider(idx, { idx = Math.round(it).toFloat() }, valueRange = 0f..(steps.size - 1).toFloat(), steps = steps.size - 2)
            Text("Choose any interval from 1:00 to 2:00 in 5-second increments.", style = MaterialTheme.typography.bodySmall)
            Spacer(Modifier.height(4.dp))
            Text("Randomize each check by up to ${jitter.toInt()}s")
            Slider(jitter, { jitter = Math.round(it / 5f) * 5f }, valueRange = 0f..120f)
            Row(verticalAlignment = Alignment.CenterVertically) { Switch(emailOn, { emailOn = it }); Spacer(Modifier.width(8.dp)); Text("Email alerts (Gmail)") }
            if (emailOn) {
                OutlinedTextField(user, { user = it }, label = { Text("Gmail address") }, singleLine = true)
                OutlinedTextField(pass, { pass = it }, label = { Text("Gmail App Password") }, singleLine = true, visualTransformation = PasswordVisualTransformation())
                OutlinedTextField(to, { to = it }, label = { Text("Send to (blank = same)") }, singleLine = true)
                TextButton({ scope.launch {
                    val msg = try { withContext(Dispatchers.IO) { Alerts.email(current(), "Stock Tracker test", "It works!") }; "Test email sent" }
                    catch (e: Exception) { "Email failed: ${e.message}" }
                    Toast.makeText(ctx, msg, Toast.LENGTH_LONG).show() } }) { Text("Send test email") }
            }
        } },
        confirmButton = { TextButton({ Store.saveSettings(ctx, current()); Scheduler.apply(ctx, intervalSec); onClose() }) { Text("Save") } },
        dismissButton = { TextButton(onClose) { Text("Cancel") } })
}
