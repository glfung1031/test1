package com.example.stocktracker

import android.Manifest
import android.os.Build
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Scheduler.apply(this, Store.settings(this).intervalSec)
        setContent { MaterialTheme { App() } }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun App() {
    val ctx = LocalContext.current
    var items by remember { mutableStateOf(Store.items(ctx)) }
    var showAdd by remember { mutableStateOf(false) }
    var showSettings by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { while (true) { items = Store.items(ctx); delay(2000) } }

    val permLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {}
    LaunchedEffect(Unit) { if (Build.VERSION.SDK_INT >= 33) permLauncher.launch(Manifest.permission.POST_NOTIFICATIONS) }

    Scaffold(
        topBar = {
            TopAppBar(title = { Text("Stock Tracker") }, actions = {
                IconButton(onClick = { CheckWorker.runNow(ctx); Toast.makeText(ctx, "Checking now…", Toast.LENGTH_SHORT).show() }) { Icon(Icons.Default.Refresh, "Check now") }
                IconButton(onClick = { showSettings = true }) { Icon(Icons.Default.Settings, "Settings") }
            })
        },
        floatingActionButton = { FloatingActionButton(onClick = { showAdd = true }) { Icon(Icons.Default.Add, "Add") } }
    ) { pad ->
        if (items.isEmpty()) Box(Modifier.padding(pad).fillMaxSize(), Alignment.Center) { Text("Tap + to add a product URL") }
        else LazyColumn(Modifier.padding(pad), contentPadding = PaddingValues(12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            items(items, key = { it.id }) { ItemCard(it) { items = Store.items(ctx) } }
        }
    }
    if (showAdd) AddDialog { showAdd = false; items = Store.items(ctx) }
    if (showSettings) SettingsDialog { showSettings = false }
}

@Composable
fun ItemCard(item: Item, refresh: () -> Unit) {
    val ctx = LocalContext.current
    var editing by remember { mutableStateOf(false) }
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(item.name, style = MaterialTheme.typography.titleMedium)
                    Text(Checker.retailer(item.url), style = MaterialTheme.typography.labelMedium)
                }
                Switch(item.enabled, { on -> Store.mutate(ctx) { l -> l.map { if (it.id == item.id) it.copy(enabled = on) else it } }; refresh() })
            }
            Spacer(Modifier.height(6.dp))
            val stock = when (item.inStock) { true -> "In stock"; false -> "Out of stock"; null -> "Unknown" }
            Text("$stock  •  now: ${item.lastPrice?.let { "$" + "%.2f".format(it) } ?: "—"}  •  alert ≤ $${"%.2f".format(item.maxPrice)}")
            if (item.lastChecked > 0) Text("Checked " + SimpleDateFormat("MMM d, h:mm a", Locale.US).format(Date(item.lastChecked)), style = MaterialTheme.typography.bodySmall)
            if (item.note.isNotBlank()) Text("⚠ ${item.note}", color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
            Row {
                TextButton({ editing = true }) { Text("Edit price") }
                TextButton({ Store.mutate(ctx) { l -> l.filter { it.id != item.id } }; refresh() }) { Text("Remove") }
            }
        }
    }
    if (editing) {
        var t by remember { mutableStateOf("%.2f".format(item.maxPrice)) }
        AlertDialog(onDismissRequest = { editing = false },
            title = { Text("Max price") },
            text = { OutlinedTextField(t, { t = it }, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal), prefix = { Text("$") }) },
            confirmButton = { TextButton({
                t.toDoubleOrNull()?.let { p -> Store.mutate(ctx) { l -> l.map { if (it.id == item.id) it.copy(maxPrice = p, alerted = false) else it } } }
                editing = false; refresh() }) { Text("Save") } })
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
            OutlinedTextField(price, { price = it }, label = { Text("Alert at or below") }, prefix = { Text("$") },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal), singleLine = true)
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
    // Exact polling choices: 1:00 through 2:00 in 5-second increments.
    val steps = (60..120 step 5).toList()
    var idx by remember {
        mutableStateOf(steps.indexOfFirst { it >= s0.intervalSec }.coerceAtLeast(0).toFloat())
    }
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
            Slider(idx, { idx = Math.round(it).toFloat() },
                valueRange = 0f..(steps.size - 1).toFloat(), steps = steps.size - 2)
            Text("Choose any interval from 1:00 to 2:00 in 5-second increments. A foreground service keeps polling while monitoring is enabled.",
                style = MaterialTheme.typography.bodySmall)
            Spacer(Modifier.height(4.dp))
            Text("Randomize each check by up to ${jitter.toInt()}s")
            Slider(jitter, { jitter = Math.round(it / 5f) * 5f }, valueRange = 0f..120f)
            Text("Adds a random delay (0 to this many seconds) before each check, so requests don't land on a perfectly predictable schedule.",
                style = MaterialTheme.typography.bodySmall)
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
