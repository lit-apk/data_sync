package top.lighilit.watch_data_sync

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.widget.Toast
import org.json.JSONArray
import org.json.JSONObject
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.PrimaryTabRow
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import top.lighilit.watch_data_sync.ui.theme.Watch_data_syncTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        CrashReporter.install(applicationContext)
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent { Watch_data_syncTheme { DataSyncScreen() } }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun DataSyncScreen() {
    val context = LocalContext.current
    val preferences = remember { context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE) }
    val historyStore = remember { ReadingHistoryStore(context.applicationContext) }
    val transferState = remember { mutableStateOf<FileTransfer?>(null) }
    val activeHistoryId = remember { mutableStateOf<String?>(null) }
    val remoteHistoryRequest = remember { mutableStateOf(false) }
    var selectedTab by rememberSaveable { mutableStateOf(0) }
    var input by rememberSaveable { mutableStateOf("") }
    var status by remember { mutableStateOf("Connecting...") }
    var selectedLabel by remember { mutableStateOf<String?>(null) }
    var history by remember { mutableStateOf(historyStore.load()) }
    var historyEditMode by remember { mutableStateOf(false) }
    var editingId by remember { mutableStateOf<String?>(null) }
    var editOffset by remember { mutableStateOf("") }
    var editName by remember { mutableStateOf("") }
    var editSource by remember { mutableStateOf("") }
    var editBackup by remember { mutableStateOf(false) }
    var deleteEntry by remember { mutableStateOf<ReadingHistory?>(null) }
    var errorTrace by rememberSaveable { mutableStateOf(CrashReporter.consumeLastCrash(context)) }
    val controller = remember {
        DataSyncController(context.applicationContext) { source, throwable ->
            errorTrace = CrashReporter.format(source, throwable)
        }
    }

    fun historyLimit() = preferences.getInt(HISTORY_LIMIT_KEY, 10)

    fun sendHistory() {
        val items = JSONArray()
        historyStore.load().forEach { entry ->
            items.put(JSONObject().put("id", entry.id).put("name", entry.name.substringAfterLast('/')))
        }
        controller.sendJson(
            JSONObject().put("type", "history_list").put("items", items)
        ) { result ->
            if (result != "sent") status = "History send failed: $result"
        }
    }

    fun sendHistoryError(message: String) {
        controller.sendJson(
            JSONObject().put("type", "history_error").put("message", message)
        ) { result ->
            status = if (result == "sent") message else "History error send failed: $result"
        }
    }

    fun sendNextPart() {
        val transfer = transferState.value ?: run {
            status = "No active transfer"
            return
        }
        val part = transfer.pendingPart() ?: run {
            status = "No unsent content"
            return
        }
        controller.sendText(part.text) { result ->
            if (result == "sent") {
                transfer.markSent()
                activeHistoryId.value?.let {
                    historyStore.updateOffset(it, transfer.currentOffset, historyLimit())
                    history = historyStore.load()
                }
                status = if (transfer.isComplete) {
                    "Sent through character ${transfer.currentOffset}; complete"
                } else {
                    "Sent through character ${transfer.currentOffset}; tap Next on watch"
                }
            } else {
                status = result
                if (remoteHistoryRequest.value) {
                    remoteHistoryRequest.value = false
                    sendHistoryError("Unable to send reading content: $result")
                }
            }
        }
    }

    fun startTransfer(content: String, label: String, offset: Int = 0, historyId: String? = null) {
        val chunkSize = preferences.getInt(CHUNK_SIZE_KEY, FileTransfer.DEFAULT_CHUNK_SIZE)
        transferState.value = FileTransfer(content, chunkSize, offset)
        activeHistoryId.value = historyId
        selectedLabel = label
        status = "Sending from character ${offset.coerceIn(0, content.length)}"
        sendNextPart()
    }

    fun resume(entry: ReadingHistory) {
        runCatching { historyStore.read(entry) }
            .onSuccess { startTransfer(it, entry.name, entry.offset, entry.id) }
            .onFailure { status = "Open failed: ${it.message}" }
    }

    val filePicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
        if (uri != null) {
            runCatching {
                runCatching {
                    context.contentResolver.takePersistableUriPermission(
                        uri,
                        Intent.FLAG_GRANT_READ_URI_PERMISSION
                    )
                }
                val content = context.contentResolver.openInputStream(uri)
                    ?.bufferedReader()?.use { it.readText() } ?: error("Unable to open file")
                val name = uri.lastPathSegment?.substringAfterLast('/') ?: "document.txt"
                val backupOnSend = preferences.getBoolean(BACKUP_ON_SEND_KEY, false)
                val entry = historyStore.addUri(uri, name, backupOnSend, content, historyLimit())
                history = historyStore.load()
                startTransfer(content, entry.name, entry.offset, entry.id)
            }.onFailure { status = "File read failed: ${it.message}" }
        }
    }

    val editFilePicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
        if (uri != null) {
            runCatching {
                context.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            editSource = uri.toString()
            editName = uri.lastPathSegment?.substringAfterLast('/') ?: editName
        }
    }

    SideEffect {
        controller.registerActionCallback("next") { sendNextPart() }
        controller.registerActionCallback("reset") {
            transferState.value = null
            activeHistoryId.value = null
            selectedLabel = null
            remoteHistoryRequest.value = false
            status = "Transfer reset by watch"
        }
        controller.registerActionCallback("received") {
            status = "Watch displayed the latest message"
        }
        controller.registerActionCallback("history_list") { sendHistory() }
        controller.registerActionCallback("history_open") { request ->
            val id = request.optString("id")
            val entry = historyStore.load().firstOrNull { it.id == id }
            if (entry == null) {
                sendHistoryError("History entry no longer exists")
            } else {
                runCatching { historyStore.read(entry) }
                    .onSuccess {
                        remoteHistoryRequest.value = true
                        startTransfer(it, entry.name, entry.offset, entry.id)
                    }
                    .onFailure { sendHistoryError("Unable to open ${entry.name}: ${it.message}") }
            }
        }
    }

    DisposableEffect(controller) {
        controller.connect(onStatus = { status = it })
        onDispose { controller.close() }
    }

    errorTrace?.let { trace ->
        AlertDialog(
            onDismissRequest = { errorTrace = null },
            title = { Text("Data Sync error") },
            text = { Text(trace, Modifier.verticalScroll(rememberScrollState())) },
            confirmButton = {
                Button(onClick = {
                    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                    clipboard.setPrimaryClip(ClipData.newPlainText("Data Sync traceback", trace))
                    Toast.makeText(context, "Traceback copied", Toast.LENGTH_SHORT).show()
                }) { Text("Copy") }
            },
            dismissButton = { Button(onClick = { errorTrace = null }) { Text("Dismiss") } }
        )
    }

    deleteEntry?.let { entry ->
        AlertDialog(
            onDismissRequest = { deleteEntry = null },
            title = { Text("Delete history entry?") },
            text = { Text(entry.name) },
            confirmButton = {
                Button(onClick = {
                    historyStore.delete(entry.id)
                    history = historyStore.load()
                    if (activeHistoryId.value == entry.id) activeHistoryId.value = null
                    editingId = null
                    historyEditMode = false
                    deleteEntry = null
                    status = "History deleted"
                }) { Text("Delete") }
            },
            dismissButton = { Button(onClick = { deleteEntry = null }) { Text("Cancel") } }
        )
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Data Sync") },
                actions = {
                    IconButton(onClick = {
                        context.startActivity(Intent(context, SettingsActivity::class.java))
                    }) {
                        Icon(painterResource(R.drawable.ic_settings), "Settings")
                    }
                }
            )
        }
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            PrimaryTabRow(selectedTabIndex = selectedTab) {
                    listOf("Text", "File").forEachIndexed { index, label ->
                        Tab(selectedTab == index, { selectedTab = index }, text = { Text(label) })
                    }
                }
                Column(
                    Modifier.fillMaxSize().padding(20.dp).verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(16.dp)
                ) {
                    Text(status, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    if (selectedTab == 0) {
                        OutlinedTextField(
                            input,
                            { input = it },
                            Modifier.fillMaxWidth().height(260.dp),
                            label = { Text("Text to send") },
                            minLines = 7
                        )
                        Button(
                            onClick = { startTransfer(input, "Text input") },
                            enabled = input.isNotEmpty(),
                            modifier = Modifier.fillMaxWidth()
                        ) { Text("Submit") }
                    } else {
                        Button(
                            onClick = { filePicker.launch(arrayOf("text/*", "application/json")) },
                            modifier = Modifier.fillMaxWidth()
                        ) { Text("Upload file") }
                        selectedLabel?.let { Text("Active: $it") }
                        transferState.value?.let { Text("Character offset: ${it.currentOffset}") }
                        HistoryView(
                            history = history,
                            editMode = historyEditMode,
                            editingId = editingId,
                            editOffset = editOffset,
                            editName = editName,
                            editSource = editSource,
                            editBackup = editBackup,
                            onEntryClick =(::resume),
                            onEdit = { entry ->
                                editingId = entry.id
                                editOffset = entry.offset.toString()
                                editName = entry.name
                                editSource = entry.source
                                editBackup = entry.backedUp
                            },
                            onHeaderAction = {
                                if (!historyEditMode) {
                                    historyEditMode = true
                                } else {
                                    val entry = history.firstOrNull { it.id == editingId }
                                    if (entry == null) {
                                        historyEditMode = false
                                    } else {
                                        val offset = editOffset.toIntOrNull()
                                        if (offset == null || offset < 0) {
                                            status = "Offset must be a natural number"
                                        } else runCatching {
                                            historyStore.update(
                                                entry.copy(offset = offset),
                                                editName,
                                                editSource,
                                                editBackup,
                                                historyLimit()
                                            )
                                        }.onSuccess {
                                            history = historyStore.load()
                                            editingId = null
                                            historyEditMode = false
                                            status = "History saved"
                                        }.onFailure { status = "History save failed: ${it.message}" }
                                    }
                                }
                            },
                            onOffsetChange = { if (it.all(Char::isDigit)) editOffset = it },
                            onNameChange = { editName = it },
                            onBackupChange = { editBackup = it },
                            onChoosePath = { editFilePicker.launch(arrayOf("text/*", "application/json")) },
                            onDelete = { deleteEntry = it }
                        )
                    }
                }
        }
    }
}

@Composable
private fun HistoryView(
    history: List<ReadingHistory>,
    editMode: Boolean,
    editingId: String?,
    editOffset: String,
    editName: String,
    editSource: String,
    editBackup: Boolean,
    onEntryClick: (ReadingHistory) -> Unit,
    onEdit: (ReadingHistory) -> Unit,
    onOffsetChange: (String) -> Unit,
    onNameChange: (String) -> Unit,
    onBackupChange: (Boolean) -> Unit,
    onChoosePath: () -> Unit,
    onHeaderAction: () -> Unit,
    onDelete: (ReadingHistory) -> Unit
) {
    if (history.isEmpty()) return
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text("Reading history", style = MaterialTheme.typography.titleMedium)
        IconButton(onClick = onHeaderAction) {
            Icon(
                painterResource(if (editMode) R.drawable.ic_save else R.drawable.ic_edit),
                if (editMode) "Save" else "Edit"
            )
        }
    }
    history.forEach { entry ->
        Card(
            Modifier.fillMaxWidth().clickable {
                if (editMode) onEdit(entry) else onEntryClick(entry)
            }
        ) {
            Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(entry.name, style = MaterialTheme.typography.titleSmall)
                if (editingId == entry.id) {
                    NumberField("Character offset", editOffset, onOffsetChange)
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text("Back up in app storage")
                        Checkbox(editBackup, onBackupChange)
                    }
                    if (editBackup) {
                        OutlinedTextField(
                            editName,
                            onNameChange,
                            Modifier.fillMaxWidth(),
                            label = { Text("Backup name") },
                            singleLine = true
                        )
                    } else {
                        Text(
                            editSource,
                            style = MaterialTheme.typography.bodySmall
                        )
                        Button(onClick = onChoosePath, modifier = Modifier.fillMaxWidth()) {
                            Text("Choose path")
                        }
                    }
                    Button(onClick = { onDelete(entry) }, modifier = Modifier.fillMaxWidth()) {
                        Icon(painterResource(R.drawable.ic_delete), "Delete")
                        Text("Delete")
                    }
                } else {
                    Text("Character offset: ${entry.offset}")
                    Text(
                        if (entry.backedUp) "Internal backup" else entry.source,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
        }
    }
}

@Composable
private fun NumberField(label: String, value: String, onChange: (String) -> Unit) {
    OutlinedTextField(
        value,
        onChange,
        Modifier.fillMaxWidth(),
        label = { Text(label) },
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
        singleLine = true
    )
}
