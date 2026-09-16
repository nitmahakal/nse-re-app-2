package com.nitmahakal.nsemarketresearch

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.animation.AnimatedContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.rounded.CloudDownload
import androidx.compose.material.icons.rounded.DataObject
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Save
import androidx.compose.material.icons.rounded.Schedule
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.Storage
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.nitmahakal.nsemarketresearch.data.AppStore
import com.nitmahakal.nsemarketresearch.data.ConditionDraft
import com.nitmahakal.nsemarketresearch.data.IndicatorSpec
import com.nitmahakal.nsemarketresearch.data.SavedScanRepository
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.*

private val Bg = Color(0xFF0B0F14)
private val Card = Color(0xFF121820)
private val Accent = Color(0xFF7CDAFF)

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent { NSEApp() }
    }
}

@Composable
fun NSEApp(vm: MainViewModel = viewModel()) {
    var tab by remember { mutableIntStateOf(0) }
    MaterialTheme(
        colorScheme = darkColorScheme(
            background = Bg,
            surface = Card,
            primary = Accent,
            onPrimary = Color.Black
        )
    ) {
        Scaffold(
            containerColor = Bg,
            bottomBar = {
                NavigationBar(containerColor = Color(0xFF0F141B)) {
                    NavigationBarItem(selected = tab == 0, onClick = { tab = 0 }, icon = { Icon(Icons.Rounded.CloudDownload, null) }, label = { Text("Update") })
                    NavigationBarItem(selected = tab == 1, onClick = { tab = 1 }, icon = { Icon(Icons.Rounded.Search, null) }, label = { Text("Scanner") })
                    NavigationBarItem(selected = tab == 2, onClick = { tab = 2 }, icon = { Icon(Icons.Rounded.Storage, null) }, label = { Text("Saved") })
                }
            }
        ) { pad ->
            AnimatedContent(tab, modifier = Modifier.padding(pad), label = "main-tabs") { current ->
                when (current) {
                    0 -> UpdateScreen(vm)
                    1 -> ScannerScreen(vm)
                    else -> SavedScreen()
                }
            }
        }
    }
}

@Composable
private fun UpdateScreen(vm: MainViewModel) {
    val context = LocalContext.current
    val running by vm.updateRunning.collectAsState()
    val status by vm.updateText.collectAsState()
    val lastUpdated by vm.lastUpdated.collectAsState()
    val scope = rememberCoroutineScope()
    var scheduleOn by remember { mutableStateOf(false) }
    var hour by remember { mutableIntStateOf(18) }
    var minute by remember { mutableIntStateOf(30) }
    val appStore = remember { AppStore(context) }
    LaunchedEffect(Unit) { appStore.scheduleEnabled.collect { scheduleOn = it } }
    LaunchedEffect(Unit) { appStore.scheduleHour.collect { hour = it } }
    LaunchedEffect(Unit) { appStore.scheduleMinute.collect { minute = it } }
    var showSchedule by remember { mutableStateOf(false) }
    var alert by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(running) {
        while (running) {
            val p = try {
                val f = context.filesDir.resolve("market_data/update_progress.json")
                if (f.exists()) JSONObject(f.readText()) else null
            } catch (_: Exception) { null }
            if (p != null) {
                val t = p.optInt("total", 0)
                val c = p.optInt("completed", 0)
                vm // keep state source alive
                delay(500)
            } else delay(700)
        }
    }

    LazyColumn(
        modifier = Modifier.fillMaxSize().background(Bg),
        contentPadding = PaddingValues(18.dp), verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        item {
            Text("NSE Market Research", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
            Text("Data first. Custom scan next. Saved tracking after that.", color = Color.LightGray)
        }
        item {
            StatusCard(
                title = "MARKET DATA",
                subtitle = if (lastUpdated.isBlank()) "No completed update yet" else "Updated data upto ${prettyDate(lastUpdated)}",
                icon = Icons.Rounded.DataObject
            ) {
                Button(
                    onClick = {
                        if (running) return@Button
                        val expected = expectedTradingDay()
                        val last = readLocalMinLastDate(context)
                        if (last == expected) {
                            alert = "Already updated through ${prettyDate(last)}"
                        } else {
                            vm.startUpdate()
                        }
                    },
                    enabled = !running,
                    modifier = Modifier.fillMaxWidth().height(52.dp)
                ) {
                    Icon(Icons.Rounded.CloudDownload, null); Spacer(Modifier.width(8.dp)); Text(if (running) "Update in progress" else "Update Data")
                }
                Spacer(Modifier.height(10.dp))
                ProgressLine(context, "update_progress.json", running)
                if (status.isNotBlank()) {
                    Spacer(Modifier.height(8.dp)); Text(status, color = Color.LightGray)
                }
            }
        }
        item {
            StatusCard("DAILY AUTO UPDATE", "Runs at your selected time. Android may delay background work for power-saving reasons.", Icons.Rounded.Schedule) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Switch(checked = scheduleOn, onCheckedChange = { scheduleOn = it; scope.launch { appStore.setSchedule(it, hour, minute) }; if (it) ScheduleManager.schedule(context, hour, minute) else ScheduleManager.cancel(context) })
                    Spacer(Modifier.width(10.dp))
                    Text(if (scheduleOn) "Enabled" else "Disabled")
                    Spacer(Modifier.weight(1f))
                    OutlinedButton(onClick = { showSchedule = true }) { Text(String.format(Locale.US, "%02d:%02d", hour, minute)) }
                }
            }
        }
        item {
            StatusCard("HOW RESUME WORKS", "The index is written after each successful stock. If the run stops in the middle, the next update continues from the saved state; failed stocks receive exactly one retry.", Icons.Rounded.AutoAwesome) { }
        }
    }

    if (showSchedule) {
        TimeDialog(hour, minute, { h, m -> hour = h; minute = m; showSchedule = false; scope.launch { appStore.setSchedule(scheduleOn, h, m) }; if (scheduleOn) ScheduleManager.schedule(context, h, m) }, { showSchedule = false })
    }
    alert?.let { msg -> AlertDialog(onDismissRequest = { alert = null }, confirmButton = { TextButton(onClick = { alert = null }) { Text("OK") } }, title = { Text("NSE Data") }, text = { Text(msg) }) }
}

@Composable
private fun ScannerScreen(vm: MainViewModel) {
    val context = LocalContext.current
    val running by vm.scanRunning.collectAsState()
    val result by vm.scanResultJson.collectAsState()
    var timeframe by remember { mutableStateOf("Daily") }
    val conditions = remember { mutableStateListOf(defaultCondition()) }
    var addPrompt by remember { mutableStateOf(false) }
    var logicPrompt by remember { mutableStateOf(false) }
    var pendingLogic by remember { mutableStateOf("AND") }
    var savePrompt by remember { mutableStateOf(false) }
    var savedName by remember { mutableStateOf("") }
    var savedAuto by remember { mutableStateOf(false) }

    LazyColumn(modifier = Modifier.fillMaxSize().background(Bg), contentPadding = PaddingValues(18.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item {
            Text("Custom Scanner", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
            Text("Build conditions one by one. Nothing runs until you say NO to another condition.", color = Color.LightGray)
        }
        item {
            ChoiceField("Timeframe", timeframe, listOf("Daily", "Weekly", "Monthly", "Quarterly", "Six Month", "Yearly", "All Timeframes")) { timeframe = it }
        }
        items(conditions.indices.toList()) { i -> ConditionCard(conditions[i], i + 1) }
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Button(onClick = { addPrompt = true }, enabled = !running, modifier = Modifier.weight(1f)) { Icon(Icons.Rounded.Add, null); Spacer(Modifier.width(6.dp)); Text("Add another condition") }
                Button(onClick = {
                    val json = JSONArray().apply { conditions.forEach { put(it.toJson()) } }.toString()
                    vm.startScan(timeframe, json)
                }, enabled = !running, modifier = Modifier.weight(1f)) { Icon(Icons.Rounded.PlayArrow, null); Spacer(Modifier.width(6.dp)); Text("Run scanner") }
            }
            Spacer(Modifier.height(10.dp))
            ProgressLine(context, "scan_progress.json", running)
        }
        if (result.isNotBlank()) {
            item { ResultsCard(result) }
            item { Button(onClick = { savePrompt = true }, modifier = Modifier.fillMaxWidth()) { Icon(Icons.Rounded.Save, null); Spacer(Modifier.width(8.dp)); Text("Save this scan") } }
        }
    }

    if (addPrompt) {
        AlertDialog(onDismissRequest = { addPrompt = false }, title = { Text("Add another condition?") },
            text = { Text("YES = choose AND/OR and build another condition. NO = run the scanner now.") },
            confirmButton = { TextButton(onClick = { addPrompt = false; logicPrompt = true }) { Text("YES") } },
            dismissButton = { TextButton(onClick = { addPrompt = false; val json = JSONArray().apply { conditions.forEach { put(it.toJson()) } }.toString(); vm.startScan(timeframe, json) }) { Text("NO") } })
    }
    if (logicPrompt) {
        ChoiceDialog(title = "Condition link", options = listOf("AND", "OR"), selected = pendingLogic, onSelect = { pendingLogic = it; logicPrompt = false; conditions.add(defaultCondition().also { c -> c.logic = pendingLogic }) }, onDismiss = { logicPrompt = false })
    }
    if (savePrompt) {
        AlertDialog(onDismissRequest = { savePrompt = false }, title = { Text("Save scanner") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    OutlinedTextField(savedName, { savedName = it }, modifier = Modifier.fillMaxWidth(), label = { Text("Scan name") })
                    Row(verticalAlignment = Alignment.CenterVertically) { Switch(savedAuto, { savedAuto = it }); Spacer(Modifier.width(8.dp)); Text("Default / auto mode") }
                }
            },
            confirmButton = { TextButton(onClick = {
                if (savedName.isNotBlank()) {
                    val conditionJson = JSONArray().apply { conditions.forEach { put(it.toJson()) } }.toString()
                    val repo = SavedScanRepository(context)
                    repo.save(savedName.trim(), timeframe, conditionJson, savedAuto)
                    if (result.isNotBlank()) repo.saveResult(savedName.trim(), SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date()), result)
                    savePrompt = false; savedName = ""
                }
            }) { Text("Save") } }, dismissButton = { TextButton(onClick = { savePrompt = false }) { Text("Cancel") } })
    }
}

@Composable
private fun SavedScreen() {
    val context = LocalContext.current
    var scans by remember { mutableStateOf(SavedScanRepository(context).list()) }
    var selected by remember { mutableIntStateOf(0) }
    val repo = remember { SavedScanRepository(context) }
    val activeRows = remember { mutableStateOf<List<JSONObject>>(emptyList()) }
    val closedRows = remember { mutableStateOf<List<JSONObject>>(emptyList()) }

    LaunchedEffect(scans) {
        val active = mutableListOf<JSONObject>()
        val closed = mutableListOf<JSONObject>()
        repo.trackingDir().listFiles()?.forEach { file ->
            try {
                val arr = JSONArray(file.readText())
                for (i in 0 until arr.length()) {
                    val obj = arr.getJSONObject(i)
                    if (obj.optString("status") == "ACTIVE") active += obj else closed += obj
                }
            } catch (_: Exception) {}
        }
        activeRows.value = active
        closedRows.value = closed
    }

    LazyColumn(modifier = Modifier.fillMaxSize().background(Bg), contentPadding = PaddingValues(18.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item {
            Text("Saved Scans & Tracking", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
            Text("Saved definitions stay separate from daily result files and ongoing P&L tracking.", color = Color.LightGray)
        }
        item {
            SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                SegmentedButton(selected = selected == 0, onClick = { selected = 0 }, shape = SegmentedButtonDefaults.itemShape(0, 3)) { Text("Scans") }
                SegmentedButton(selected = selected == 1, onClick = { selected = 1 }, shape = SegmentedButtonDefaults.itemShape(1, 3)) { Text("Active") }
                SegmentedButton(selected = selected == 2, onClick = { selected = 2 }, shape = SegmentedButtonDefaults.itemShape(2, 3)) { Text("Closed") }
            }
        }
        if (selected == 0) {
            items(scans, key = { it.optString("name") }) { scan ->
                Card(shape = RoundedCornerShape(18.dp), colors = CardDefaults.cardColors(containerColor = Card)) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text(scan.optString("name"), fontWeight = FontWeight.Bold)
                                Text(scan.optString("timeframe"), color = Color.LightGray)
                            }
                            Text(if (scan.optBoolean("auto")) "AUTO" else "MANUAL", color = if (scan.optBoolean("auto")) Accent else Color.LightGray, fontWeight = FontWeight.Bold)
                        }
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            OutlinedButton(onClick = { SavedScanRepository(context).setAuto(scan.optString("name"), !scan.optBoolean("auto")); scans = SavedScanRepository(context).list() }) { Text(if (scan.optBoolean("auto")) "Turn off auto" else "Set auto") }
                            IconButton(onClick = { SavedScanRepository(context).delete(scan.optString("name")); scans = SavedScanRepository(context).list() }) { Icon(Icons.Rounded.Delete, null) }
                        }
                    }
                }
            }
            item {
                if (scans.isEmpty()) Text("No saved scans yet. Run a custom scan and save it.", color = Color.LightGray)
            }
        } else {
            val rows = if (selected == 1) activeRows.value else closedRows.value
            if (rows.isEmpty()) item { Text(if (selected == 1) "No active tracked results." else "No closed tracked results yet.", color = Color.LightGray) }
            items(rows) { row ->
                Card(shape = RoundedCornerShape(18.dp), colors = CardDefaults.cardColors(containerColor = Card)) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                        Row { Text(row.optString("symbol"), fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f)); Text(row.optString("timeframe"), color = Accent) }
                        Text("Entry  ${row.optString("entry_date")}  @  ${formatNum(row.optDouble("entry_close", Double.NaN))}", color = Color.LightGray)
                        if (row.optString("status") == "ACTIVE") {
                            Text("Last  ${row.optString("last_date")}  @  ${formatNum(row.optDouble("last_close", Double.NaN))}", color = Color.LightGray)
                        } else {
                            val pnl = row.optDouble("pnl_pct", Double.NaN)
                            Text("Exit  ${row.optString("exit_date")}  @  ${formatNum(row.optDouble("exit_close", Double.NaN))}", color = Color.LightGray)
                            Text("P&L  ${if (pnl.isNaN()) "—" else String.format(Locale.US, "%.2f%%", pnl)}", fontWeight = FontWeight.Bold)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ConditionCard(condition: ConditionDraft, number: Int) {
    Card(shape = RoundedCornerShape(18.dp), colors = CardDefaults.cardColors(containerColor = Card)) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("Condition $number", fontWeight = FontWeight.Bold)
            SpecEditor("First indicator", condition.left) { condition.left = it }
            ChoiceField("Comparator", condition.comparator, listOf("Equal", "Greater", "Greater Equal", "Less", "Less Equal", "Cross Above", "Cross Below")) { condition.comparator = it }
            SpecEditor("Second indicator", condition.right) { condition.right = it }
        }
    }
}

private fun defaultCondition() = ConditionDraft(
    IndicatorSpec("Close"), "Greater", IndicatorSpec("EMA", linkedMapOf("length" to "20"))
)

private val indicatorParams = linkedMapOf(
    "Close" to emptyList(),
    "EMA" to listOf("length"), "HMA" to listOf("length"), "RSI" to listOf("length"),
    "EMA of RSI" to listOf("rsi_length", "ema_length"),
    "MACD" to listOf("fast_length", "slow_length", "signal_length"),
    "MACD Signal" to listOf("fast_length", "slow_length", "signal_length"),
    "MACD Histogram" to listOf("fast_length", "slow_length", "signal_length"),
    "Stoch RSI %K" to listOf("rsi_length", "stoch_length", "k_length", "d_length"),
    "Stoch RSI %D" to listOf("rsi_length", "stoch_length", "k_length", "d_length"),
    "Numeric Value" to listOf("value"),
    "Reverse RSI" to listOf("target", "rsi_length", "smoothing_length"),
    "Reverse Stoch RSI %K" to listOf("target", "rsi_length", "stoch_length", "k_length", "d_length", "smoothing_length"),
    "Reverse Stoch RSI %D" to listOf("target", "rsi_length", "stoch_length", "k_length", "d_length", "smoothing_length")
)

private fun defaultParams(name: String): LinkedHashMap<String, String> = linkedMapOf<String, String>().apply {
    indicatorParams[name].orEmpty().forEach { key ->
        put(key, when (key) { "target" -> if (name == "Reverse RSI") "50" else "50"; "length" -> "20"; "rsi_length" -> "14"; "ema_length" -> "9"; "fast_length" -> "12"; "slow_length" -> "26"; "signal_length" -> "9"; "stoch_length" -> "14"; "k_length" -> "3"; "d_length" -> "3"; "smoothing_length" -> "1"; "value" -> "0"; else -> "1" })
    }
}

@Composable
private fun SpecEditor(label: String, initial: IndicatorSpec, onChange: (IndicatorSpec) -> Unit) {
    var spec by remember { mutableStateOf(initial) }
    val options = indicatorParams.keys.toList()
    ChoiceField(label, spec.name, options) { name -> spec = IndicatorSpec(name, defaultParams(name)); onChange(spec) }
    if (indicatorParams[spec.name].orEmpty().isNotEmpty()) {
        indicatorParams[spec.name].orEmpty().forEach { key ->
            var value by remember(spec.name + key) { mutableStateOf(spec.params[key] ?: "") }
            OutlinedTextField(value, { value = it; val updated = LinkedHashMap(spec.params); updated[key] = it; spec = spec.copy(params = updated); onChange(spec) }, label = { Text(key.replace('_', ' ').replaceFirstChar { it.uppercase() }) }, modifier = Modifier.fillMaxWidth(), singleLine = true)
        }
    }
}

@Composable
private fun ChoiceField(label: String, value: String, options: List<String>, onSelect: (String) -> Unit) {
    var open by remember { mutableStateOf(false) }
    OutlinedButton(onClick = { open = true }, modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(12.dp)) { Text("$label: $value", modifier = Modifier.fillMaxWidth()) }
    if (open) ChoiceDialog(label, options, value, onSelect) { open = false }
}

@Composable
private fun ChoiceDialog(title: String, options: List<String>, selected: String, onSelect: (String) -> Unit, onDismiss: (() -> Unit)? = null) {
    AlertDialog(onDismissRequest = { onDismiss?.invoke() }, title = { Text(title) }, text = {
        Column { options.forEach { option -> Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) { RadioButton(selected = option == selected, onClick = { onSelect(option); onDismiss?.invoke() }); Text(option) } } }
    }, confirmButton = { TextButton(onClick = { onDismiss?.invoke() }) { Text("Close") } })
}

@Composable
private fun StatusCard(title: String, subtitle: String, icon: androidx.compose.ui.graphics.vector.ImageVector, content: @Composable ColumnScope.() -> Unit) {
    Card(shape = RoundedCornerShape(20.dp), colors = CardDefaults.cardColors(containerColor = Card)) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) { Icon(icon, null, tint = Accent); Spacer(Modifier.width(8.dp)); Text(title, fontWeight = FontWeight.Bold) }
            Text(subtitle, color = Color.LightGray)
            content()
        }
    }
}

@Composable
private fun ProgressLine(context: android.content.Context, filename: String, running: Boolean) {
    var fraction by remember { mutableFloatStateOf(0f) }
    var label by remember { mutableStateOf("") }
    LaunchedEffect(running) {
        while (running) {
            try {
                val file = context.filesDir.resolve("market_data/$filename")
                if (file.exists()) {
                    val o = JSONObject(file.readText()); val t = o.optInt("total", 0); val c = o.optInt("completed", 0)
                    fraction = if (t > 0) c.toFloat()/t else 0f
                    label = if (t > 0) "${o.optInt("completed")} / $t" else "Working…"
                }
            } catch (_: Exception) {}
            delay(500)
        }
    }
    if (running) {
        LinearProgressIndicator(progress = { fraction }, modifier = Modifier.fillMaxWidth())
        Text(label, color = Color.LightGray)
    }
}

@Composable
private fun ResultsCard(json: String) {
    val rows = remember(json) {
        try {
            val o = JSONObject(json); val a = o.optJSONArray("matches") ?: JSONArray(); (0 until a.length()).map { a.getJSONObject(it) }
        } catch (_: Exception) { emptyList() }
    }
    Card(shape = RoundedCornerShape(20.dp), colors = CardDefaults.cardColors(containerColor = Card)) {
        Column(Modifier.padding(16.dp)) {
            Text("SCAN RESULTS", fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(8.dp))
            if (rows.isEmpty()) Text("No matching stocks.", color = Color.LightGray)
            else rows.take(250).forEach { row ->
                Column(Modifier.fillMaxWidth().padding(vertical = 8.dp)) {
                    Row { Text(row.optString("symbol"), fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f)); Text(row.optString("timeframe"), color = Accent) }
                    Text("Close  ${formatNum(row.optDouble("close", Double.NaN))}", color = Color.LightGray)
                    val values = row.optJSONObject("values")
                    if (values != null && values.length() > 0) {
                        val it = values.keys()
                        while (it.hasNext()) {
                            val key = it.next()
                            val compact = try {
                                val spec = JSONObject(key)
                                val n = spec.optString("name")
                                val v = values.optDouble(key, Double.NaN)
                                "$n  ${formatNum(v)}"
                            } catch (_: Exception) { null }
                            compact?.let { Text(it, color = Color.LightGray) }
                        }
                    }
                    Divider(Modifier.padding(top = 8.dp))
                }
            }
            if (rows.size > 250) Text("Showing first 250 results. Full JSON is saved by the saved-scan system.", color = Color.LightGray)
        }
    }
}

@Composable
private fun TimeDialog(hour: Int, minute: Int, onSave: (Int, Int) -> Unit, onCancel: () -> Unit) {
    var h by remember { mutableStateOf(hour.toString()) }
    var m by remember { mutableStateOf(minute.toString()) }
    AlertDialog(onDismissRequest = onCancel, title = { Text("Daily update time") }, text = {
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            OutlinedTextField(h, { h = it.filter(Char::isDigit).take(2) }, label = { Text("Hour") }, modifier = Modifier.weight(1f))
            OutlinedTextField(m, { m = it.filter(Char::isDigit).take(2) }, label = { Text("Minute") }, modifier = Modifier.weight(1f))
        }
    }, confirmButton = { TextButton(onClick = { onSave((h.toIntOrNull() ?: 18).coerceIn(0,23), (m.toIntOrNull() ?: 30).coerceIn(0,59)) }) { Text("Save") } }, dismissButton = { TextButton(onClick = onCancel) { Text("Cancel") } })
}

private fun prettyDate(value: String): String = try { SimpleDateFormat("dd.MM.yy", Locale.US).format(SimpleDateFormat("yyyy-MM-dd", Locale.US).parse(value)!!) } catch (_: Exception) { value }
private fun expectedTradingDay(): String { val c = Calendar.getInstance(); while (c.get(Calendar.DAY_OF_WEEK) == Calendar.SATURDAY || c.get(Calendar.DAY_OF_WEEK) == Calendar.SUNDAY) c.add(Calendar.DAY_OF_MONTH, -1); return String.format(Locale.US, "%04d-%02d-%02d", c.get(Calendar.YEAR), c.get(Calendar.MONTH)+1, c.get(Calendar.DAY_OF_MONTH)) }
private fun readLocalMinLastDate(context: android.content.Context): String? = try { val f = context.filesDir.resolve("market_data/database_index.json"); if (!f.exists()) null else { val o = JSONObject(f.readText()); val keys = o.keys(); var min: String? = null; while(keys.hasNext()) { val k = keys.next(); val d = o.getJSONObject(k).optString("last_date"); if (d.isNotBlank() && (min == null || d < min!!)) min = d }; min } } catch (_: Exception) { null }
private fun formatNum(v: Double): String = if (v.isNaN()) "—" else String.format(Locale.US, "%.2f", v)
