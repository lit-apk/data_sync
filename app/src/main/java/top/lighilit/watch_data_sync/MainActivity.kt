package top.lighilit.watch_data_sync

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.net.Uri
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Tab
import androidx.compose.material3.PrimaryTabRow
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
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import top.lighilit.watch_data_sync.ui.theme.Watch_data_syncTheme

private const val PREFERENCES = "data_sync_settings"
private const val CHUNK_SIZE_KEY = "chunk_size"

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        CrashReporter.install(applicationContext)
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            Watch_data_syncTheme {
                DataSyncScreen()
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun DataSyncScreen() {
    val context = LocalContext.current
    val preferences = remember {
        context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)
    }
    var selectedTab by rememberSaveable { mutableStateOf(0) }
    var input by rememberSaveable { mutableStateOf("") }
    var status by remember { mutableStateOf("Connecting...") }
    val transferState = remember { mutableStateOf<FileTransfer?>(null) }
    val transfer = transferState.value
    var selectedFile by remember { mutableStateOf<String?>(null) }
    var chunkSizeText by rememberSaveable {
        mutableStateOf(
            preferences.getInt(CHUNK_SIZE_KEY, FileTransfer.DEFAULT_CHUNK_SIZE).toString()
        )
    }
    var errorTrace by rememberSaveable { mutableStateOf(CrashReporter.consumeLastCrash(context)) }
    val controller = remember {
        DataSyncController(context.applicationContext) { source, throwable ->
            errorTrace = CrashReporter.format(source, throwable)
        }
    }

    fun sendNextPart() {
        val current = transferState.value ?: run {
            status = "No file transfer; choose a file in the File tab"
            return
        }
        val part = current.pendingPart() ?: run {
            status = "No unsent parts; choose another file or tap Reset"
            return
        }
        controller.sendText(part.text) { result ->
            if (result == "sent") {
                current.markSent()
                status = if (current.isComplete) {
                    "Sent ${part.number}/${part.total}; all parts sent"
                } else {
                    "Sent ${part.number}/${part.total}; tap Next on watch"
                }
            } else {
                status = result
            }
        }
    }

    fun startTransfer(content: String, label: String) {
        val chunkSize = preferences.getInt(
            CHUNK_SIZE_KEY,
            FileTransfer.DEFAULT_CHUNK_SIZE
        )
        transferState.value = FileTransfer(content, chunkSize)
        selectedFile = label
        status = "Sending part 1"
        sendNextPart()
    }

    val filePicker = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri: Uri? ->
        if (uri != null) {
            runCatching {
                context.contentResolver.openInputStream(uri)?.bufferedReader()?.use { it.readText() }
                    ?: error("Unable to open file")
            }.onSuccess { content ->
                startTransfer(content, uri.lastPathSegment ?: "Selected file")
            }.onFailure {
                status = "File read failed: ${it.message ?: "unknown error"}"
            }
        }
    }

    SideEffect {
        controller.registerActionCallback("next") {
            sendNextPart()
        }
        controller.registerActionCallback("reset") {
            transferState.value = null
            selectedFile = null
            status = "File transfer reset by watch"
        }
        controller.registerActionCallback("received") {
            status = "Watch displayed the latest message"
        }
    }

    DisposableEffect(controller) {
        controller.connect { status = it }
        onDispose { controller.close() }
    }

    errorTrace?.let { trace ->
        AlertDialog(
            onDismissRequest = { errorTrace = null },
            title = { Text("Data Sync error") },
            text = {
                Text(trace, modifier = Modifier.verticalScroll(rememberScrollState()))
            },
            confirmButton = {
                Button(onClick = {
                    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE)
                        as ClipboardManager
                    clipboard.setPrimaryClip(ClipData.newPlainText("Data Sync traceback", trace))
                    Toast.makeText(context, "Traceback copied", Toast.LENGTH_SHORT).show()
                }) { Text("Copy") }
            },
            dismissButton = {
                Button(onClick = { errorTrace = null }) { Text("Dismiss") }
            }
        )
    }

    Scaffold(topBar = { TopAppBar(title = { Text("Data Sync") }) }) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
        ) {
            PrimaryTabRow(selectedTabIndex = selectedTab) {
                listOf("Text", "File", "Settings").forEachIndexed { index, label ->
                    Tab(
                        selected = selectedTab == index,
                        onClick = { selectedTab = index },
                        text = { Text(label) }
                    )
                }
            }
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(horizontal = 20.dp, vertical = 12.dp)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                Text(status, color = MaterialTheme.colorScheme.onSurfaceVariant)
                when (selectedTab) {
                    0 -> {
                        Text("Long text is split using the maximum size configured in Settings.")
                        OutlinedTextField(
                            value = input,
                            onValueChange = { input = it },
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(260.dp),
                            label = { Text("Text to send") },
                            minLines = 7
                        )
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.End
                        ) {
                            Button(
                                onClick = { startTransfer(input, "Text input") },
                                enabled = input.isNotEmpty()
                            ) { Text("Submit") }
                        }
                    }

                    1 -> {
                        Text("Each file message uses the maximum size configured in Settings.")
                        Button(
                            onClick = {
                                filePicker.launch(arrayOf("text/*", "application/json"))
                            },
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text(if (transfer == null) "Upload file" else "Choose another file")
                        }
                        selectedFile?.let { Text(it) }
                        transfer?.let {
                            Text("Parts sent: ${it.sentParts}/${it.totalParts}")
                            Text("Use Next on the watch for the next part, or Reset to discard the rest.")
                        }
                    }

                    else -> {
                        Text("Watch authorization")
                        Button(
                            onClick = { controller.requestPermission { status = it } },
                            modifier = Modifier.fillMaxWidth()
                        ) { Text("Authorize watch") }
                        Text("Maximum characters per message")
                        OutlinedTextField(
                            value = chunkSizeText,
                            onValueChange = { value ->
                                if (value.all(Char::isDigit)) chunkSizeText = value
                            },
                            modifier = Modifier.fillMaxWidth(),
                            label = { Text("Characters") },
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                            singleLine = true
                        )
                        Button(
                            onClick = {
                                val value = chunkSizeText.toIntOrNull()
                                if (value == null || value !in 1..10_000) {
                                    status = "Chunk size must be between 1 and 10000"
                                } else {
                                    preferences.edit().putInt(CHUNK_SIZE_KEY, value).apply()
                                    status = "Chunk size saved: $value characters"
                                }
                            },
                            modifier = Modifier.fillMaxWidth()
                        ) { Text("Save settings") }
                    }
                }
            }
        }
    }
}
