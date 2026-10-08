package top.lighilit.watch_data_sync

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
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
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import top.lighilit.watch_data_sync.ui.theme.Watch_data_syncTheme
import java.util.concurrent.Executors

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        CrashReporter.install(applicationContext)
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent { Watch_data_syncTheme { DataSyncScreen() } }
    }
}

private val DOCUMENT_MIME_TYPES: Array<String>
    get() = DocumentFormats.mimeTypes()

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun DataSyncScreen() {
    val context = LocalContext.current
    val resources = LocalResources.current
    val preferences = remember { context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE) }
    val historyStore = remember { ReadingHistoryStore(context.applicationContext) }
    val readerState = remember { mutableStateOf<Reader?>(null) }
    val activeHistoryId = remember { mutableStateOf<String?>(null) }
    val activeChapter = remember { mutableStateOf(0) }
    val remoteHistoryRequest = remember { mutableStateOf(false) }
    var selectedTab by rememberSaveable { mutableStateOf(0) }
    var input by rememberSaveable { mutableStateOf("") }
    var status by remember { mutableStateOf(resources.getString(R.string.connecting)) }
    var selectedLabel by remember { mutableStateOf<String?>(null) }
    var history by remember { mutableStateOf(historyStore.load()) }
    var historyEditMode by remember { mutableStateOf(false) }
    var editingId by remember { mutableStateOf<String?>(null) }
    var editOffset by remember { mutableStateOf("") }
    var editChapter by remember { mutableStateOf(0) }
    var editName by remember { mutableStateOf("") }
    var editSource by remember { mutableStateOf("") }
    var editBackup by remember { mutableStateOf(false) }
    var deleteEntry by remember { mutableStateOf<ReadingHistory?>(null) }
    var errorTrace by rememberSaveable { mutableStateOf(CrashReporter.consumeLastCrash(context)) }
    var chapterDialog by remember { mutableStateOf<List<TextChapter>?>(null) }
    var chapterDialogAction by remember { mutableStateOf<((TextChapter) -> Unit)?>(null) }
    var editChapters by remember { mutableStateOf<List<TextChapter>>(emptyList()) }
    var editChapterExpanded by remember { mutableStateOf(false) }
    val ioExecutor = remember { Executors.newSingleThreadExecutor() }
    val mainHandler = remember { Handler(Looper.getMainLooper()) }
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
            if (result != "sent") status = resources.getString(R.string.history_send_failed, result)
        }
    }

    fun sendHistoryError(message: String) {
        controller.sendJson(
            JSONObject().put("type", "history_error").put("message", message)
        ) { result ->
            status = if (result == "sent") message else resources.getString(R.string.history_error_send_failed, result)
        }
    }

    fun sendNextPart() {
        val reader = readerState.value ?: run {
            status = resources.getString(R.string.no_active_transfer)
            return
        }
        val part = reader.nextPart() ?: run {
            status = resources.getString(R.string.no_unsent_content)
            return
        }
        controller.sendText(part.text) { result ->
            if (result == "sent") {
                reader.markSent()
                activeHistoryId.value?.let {
                    historyStore.updatePosition(it, part.startOffset, activeChapter.value, historyLimit())
                    history = historyStore.load()
                }
                status = if (reader.isComplete) {
                    resources.getString(R.string.sent_complete, reader.currentOffset)
                } else {
                    resources.getString(R.string.sent_next, reader.currentOffset)
                }
            } else {
                status = result
                if (remoteHistoryRequest.value) {
                    remoteHistoryRequest.value = false
                    sendHistoryError(resources.getString(R.string.send_content_failed, result))
                }
            }
        }
    }

    fun sendPreviousPart() {
        val reader = readerState.value ?: run {
            status = resources.getString(R.string.no_active_transfer)
            return
        }
        runCatching { reader.previousPart() }
            .onSuccess { part ->
                if (part == null) {
                    status = resources.getString(R.string.first_page)
                    return@onSuccess
                }
                controller.sendText(part.text) { result ->
                    if (result == "sent") {
                        reader.markPreviousSent()
                        activeHistoryId.value?.let {
                            historyStore.updatePosition(it, part.startOffset, activeChapter.value, historyLimit())
                            history = historyStore.load()
                        }
                        status = resources.getString(R.string.sent_from, part.startOffset)
                    } else {
                        status = result
                    }
                }
            }
            .onFailure { error ->
                val message = error.message ?: resources.getString(R.string.previous_unsupported)
                status = message
                if (error is PreviousPageNotSupportedException) {
                    controller.sendJson(
                        JSONObject().put("type", "not_supported").put("message", message)
                    ) { result ->
                        if (result != "sent") status = resources.getString(R.string.notify_failed, result)
                    }
                }
            }
    }

    fun startTransfer(source: TextSource, label: String, length: Int? = null, offset: Int = 0, historyId: String? = null, chapter: Int = 0) {
        val chunkSize = preferences.getInt(CHUNK_SIZE_KEY, FileTransfer.DEFAULT_CHUNK_SIZE)
        readerState.value = Reader.of(source, label, chunkSize, offset, chapter)
        activeHistoryId.value = historyId
        activeChapter.value = chapter
        selectedLabel = label
        status = resources.getString(R.string.sending_from, if (length == null) offset else offset.coerceIn(0, length))
        sendNextPart()
    }

    fun openSource(
        source: TextSource,
        label: String,
        offset: Int = 0,
        historyId: String? = null,
        chapter: Int = 0,
        chooseChapter: Boolean = false
    ) {
        if (!source.chaptered) {
            startTransfer(source, label, offset = offset, historyId = historyId)
            return
        }
        status = resources.getString(R.string.preparing_epub)
        ioExecutor.execute {
            runCatching {
                source.chapters()
            }
                .onSuccess { chapters ->
                    mainHandler.post {
                        if (chapters.isNotEmpty() && (historyId == null || chooseChapter)) {
                            chapterDialog = chapters
                            chapterDialogAction = { selected ->
                                chapterDialog = null
                                chapterDialogAction = null
                                status = resources.getString(R.string.preparing_chapter)
                                startTransfer(source, label, offset = 0, historyId = historyId, chapter = selected.index)
                            }
                        } else {
                            startTransfer(source, label, offset = offset, historyId = historyId, chapter = chapter)
                        }
                    }
                }
                .onFailure { error ->
                    mainHandler.post { status = resources.getString(R.string.open_failed, error.message) }
                }
        }
    }

    chapterDialog?.let { chapters ->
        AlertDialog(
            onDismissRequest = {
                chapterDialog = null
                chapterDialogAction = null
            },
            title = { Text(stringResource(R.string.select_chapter)) },
            text = {
                Column(Modifier.verticalScroll(rememberScrollState())) {
                    chapters.forEach { item ->
                        Button(
                            onClick = { chapterDialogAction?.invoke(item) },
                            modifier = Modifier.fillMaxWidth()
                            ) { Text("${item.index + 1}. ${item.title}") }
                    }
                }
            },
            confirmButton = {}
        )
    }

    fun resume(entry: ReadingHistory) {
        runCatching { historyStore.source(entry) }
            .onSuccess { openSource(it, entry.name, offset = entry.offset, historyId = entry.id, chapter = entry.chapter) }
            .onFailure { status = resources.getString(R.string.open_failed, it.message) }
    }

    val filePicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
        if (uri != null) {
            runCatching {
                ioExecutor.execute {
                    runCatching {
                        runCatching {
                            context.contentResolver.takePersistableUriPermission(
                                uri,
                                Intent.FLAG_GRANT_READ_URI_PERMISSION
                            )
                        }
                        val name = historyStore.displayName(uri)
                        val backupOnSend = preferences.getBoolean(BACKUP_ON_SEND_KEY, false)
                        val entry = historyStore.addUri(uri, name, backupOnSend, historyLimit())
                        val source = historyStore.source(entry)
                        mainHandler.post {
                            history = historyStore.load()
                            openSource(source, entry.name, offset = entry.offset, historyId = entry.id, chooseChapter = true)
                        }
                    }.onFailure { error ->
                        mainHandler.post { status = resources.getString(R.string.file_read_failed, error.message) }
                    }
                }
            }
        }
    }

    val editFilePicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
        if (uri != null) {
            runCatching {
                context.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            editSource = uri.toString()
            editName = historyStore.displayName(uri)
            editChapters = runCatching { historyStore.source(uri, editName).chapters() }
                .getOrElse { emptyList() }
        }
    }

    SideEffect {
        controller.registerActionCallback("next") { sendNextPart() }
        controller.registerActionCallback("previous") { sendPreviousPart() }
        controller.registerActionCallback("reset") {
            readerState.value = null
            activeHistoryId.value = null
            selectedLabel = null
            remoteHistoryRequest.value = false
            status = resources.getString(R.string.transfer_reset)
        }
        controller.registerActionCallback("received") {
            status = resources.getString(R.string.watch_displayed)
        }
        controller.registerActionCallback("history_list") { sendHistory() }
        controller.registerActionCallback("history_open") { request ->
            val id = request.optString("id")
            val entry = historyStore.load().firstOrNull { it.id == id }
            if (entry == null) {
                sendHistoryError(resources.getString(R.string.history_missing))
            } else {
                runCatching { historyStore.source(entry) }
                    .onSuccess {
                        remoteHistoryRequest.value = true
                        openSource(it, entry.name, offset = entry.offset, historyId = entry.id, chapter = entry.chapter)
                    }
                    .onFailure { sendHistoryError(resources.getString(R.string.history_open_failed, entry.name, it.message)) }
            }
        }
    }

    DisposableEffect(controller) {
        controller.connect(onStatus = { status = it })
        onDispose {
            controller.close()
            ioExecutor.shutdownNow()
        }
    }

    errorTrace?.let { trace ->
        AlertDialog(
            onDismissRequest = { errorTrace = null },
            title = { Text(stringResource(R.string.data_sync_error)) },
            text = { Text(trace, Modifier.verticalScroll(rememberScrollState())) },
            confirmButton = {
                Button(onClick = {
                    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                    clipboard.setPrimaryClip(ClipData.newPlainText("Data Sync traceback", trace))
                    Toast.makeText(context, resources.getString(R.string.traceback_copied), Toast.LENGTH_SHORT).show()
                }) { Text(stringResource(R.string.copy)) }
            },
            dismissButton = { Button(onClick = { errorTrace = null }) { Text(stringResource(R.string.dismiss)) } }
        )
    }

    deleteEntry?.let { entry ->
        AlertDialog(
            onDismissRequest = { deleteEntry = null },
            title = { Text(stringResource(R.string.delete_history)) },
            text = { Text(entry.name) },
            confirmButton = {
                Button(onClick = {
                    historyStore.delete(entry.id)
                    history = historyStore.load()
                    if (activeHistoryId.value == entry.id) activeHistoryId.value = null
                    editingId = null
                    historyEditMode = false
                    deleteEntry = null
                    status = resources.getString(R.string.history_deleted)
                }) { Text(stringResource(R.string.delete)) }
            },
            dismissButton = { Button(onClick = { deleteEntry = null }) { Text(stringResource(R.string.cancel)) } }
        )
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.app_name)) },
                actions = {
                    IconButton(onClick = {
                        context.startActivity(Intent(context, SettingsActivity::class.java))
                    }) {
                        Icon(painterResource(R.drawable.ic_settings), stringResource(R.string.settings))
                    }
                }
            )
        }
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            PrimaryTabRow(selectedTabIndex = selectedTab) {
                    listOf(stringResource(R.string.tab_text), stringResource(R.string.tab_file)).forEachIndexed { index, label ->
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
                            label = { Text(stringResource(R.string.text_to_send)) },
                            minLines = 7
                        )
                        Button(
                            onClick = { startTransfer(StringTextSource(input), resources.getString(R.string.text_input), input.length) },
                            enabled = input.isNotEmpty(),
                            modifier = Modifier.fillMaxWidth()
                        ) { Text(stringResource(R.string.submit)) }
                    } else {
                        Button(
                            onClick = { filePicker.launch(DOCUMENT_MIME_TYPES) },
                            modifier = Modifier.fillMaxWidth()
                        ) { Text(stringResource(R.string.upload_file)) }
                        selectedLabel?.let { Text(stringResource(R.string.active_document, it)) }
                        readerState.value?.let { Text("Character offset: ${it.currentOffset}") }
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
                                editChapter = entry.chapter
                                editName = entry.name
                                editSource = entry.source
                                editBackup = entry.backedUp
                                editChapters = runCatching { historyStore.source(entry).chapters() }
                                    .getOrElse { emptyList() }
                                editChapterExpanded = false
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
                                            val selectedOffset = editChapters.getOrNull(editChapter)?.startOffset ?: offset
                                            historyStore.update(
                                                entry.copy(offset = selectedOffset, chapter = editChapter),
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
                            editChapter = editChapter,
                            editChapters = editChapters,
                            editChapterExpanded = editChapterExpanded,
                            onChapterChange = { editChapter = it; editChapterExpanded = false },
                            onChapterToggle = { editChapterExpanded = !editChapterExpanded },
                            onNameChange = { editName = it },
                            onBackupChange = { editBackup = it },
                            onChoosePath = { editFilePicker.launch(DOCUMENT_MIME_TYPES) },
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
    editChapter: Int,
    editChapters: List<TextChapter>,
    editChapterExpanded: Boolean,
    editName: String,
    editSource: String,
    editBackup: Boolean,
    onEntryClick: (ReadingHistory) -> Unit,
    onEdit: (ReadingHistory) -> Unit,
    onOffsetChange: (String) -> Unit,
    onChapterChange: (Int) -> Unit,
    onChapterToggle: () -> Unit,
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
                    if (editChapters.isNotEmpty()) {
                        val selectedChapter = editChapters.firstOrNull { it.index == editChapter }
                        Button(onClick = onChapterToggle, modifier = Modifier.fillMaxWidth()) {
                            Text(
                                if (editChapterExpanded) {
                                    "Chapter: ${selectedChapter?.index?.plus(1) ?: editChapter + 1}. " +
                                        "${selectedChapter?.title ?: "Unknown"} ▲"
                                } else {
                                    "Chapter: ${selectedChapter?.index?.plus(1) ?: editChapter + 1}. " +
                                        "${selectedChapter?.title ?: "Unknown"} ▼"
                                }
                            )
                        }
                        if (editChapterExpanded) {
                            editChapters.forEach { chapter ->
                                Button(
                                    onClick = { onChapterChange(chapter.index) },
                                    modifier = Modifier.fillMaxWidth()
                                ) {
                                    Text("${chapter.index + 1}. ${chapter.title}")
                                }
                            }
                        }
                    } else {
                        NumberField("Character offset", editOffset, onOffsetChange)
                    }
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
