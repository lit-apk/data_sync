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
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import top.lighilit.watch_data_sync.ui.theme.Watch_data_syncTheme

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
    var input by remember { mutableStateOf("") }
    var status by remember { mutableStateOf("Connecting...") }
    var transfer by remember { mutableStateOf<FileTransfer?>(null) }
    var errorTrace by rememberSaveable { mutableStateOf(CrashReporter.consumeLastCrash(context)) }
    val controller = remember {
        DataSyncController(context.applicationContext) { source, throwable ->
            errorTrace = CrashReporter.format(source, throwable)
        }
    }

    fun showAction(action: String) {
        Toast.makeText(context, "$action pressed on watch", Toast.LENGTH_SHORT).show()
        status = "$action pressed on watch"
    }

    fun sendNextPart() {
        val current = transfer ?: return
        val part = current.pendingPart() ?: run {
            status = "File transfer complete"
            transfer = null
            return
        }
        controller.sendText(part.text) { result ->
            if (result == "sent") {
                current.markSent()
                if (current.isComplete) {
                    transfer = null
                    status = "Sent ${part.number}/${part.total}; file transfer complete"
                } else {
                    status = "Sent ${part.number}/${part.total}; confirm on watch for next"
                }
            } else {
                status = result
            }
        }
    }

    val filePicker = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri: Uri? ->
        if (uri != null) {
            runCatching {
                context.contentResolver.openInputStream(uri)?.bufferedReader()?.use { it.readText() }
                    ?: error("Unable to open file")
            }.onSuccess { content ->
                transfer = FileTransfer(content)
                status = "File selected"
                sendNextPart()
            }.onFailure {
                status = "File read failed: ${it.message ?: "unknown error"}"
            }
        }
    }

    SideEffect {
        controller.registerActionCallback("confirm") {
            if (transfer != null) sendNextPart() else showAction("confirm")
        }
        controller.registerActionCallback("cancel") {
            transfer = null
            showAction("cancel")
        }
        controller.registerActionCallback("received") {
            status = "Watch received and displayed the text"
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
                Text(
                    text = trace,
                    modifier = Modifier.verticalScroll(rememberScrollState())
                )
            },
            confirmButton = {
                Button(onClick = {
                    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE)
                        as ClipboardManager
                    clipboard.setPrimaryClip(ClipData.newPlainText("Data Sync traceback", trace))
                    Toast.makeText(context, "Traceback copied", Toast.LENGTH_SHORT).show()
                }) {
                    Text("Copy")
                }
            },
            dismissButton = {
                Button(onClick = { errorTrace = null }) {
                    Text("Dismiss")
                }
            }
        )
    }

    Scaffold(
        topBar = { TopAppBar(title = { Text("Data Sync") }) }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = 20.dp, vertical = 12.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Text(status, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Button(
                onClick = { controller.requestPermission { status = it } },
                modifier = Modifier.fillMaxWidth()
            ) {
                Text("Authorize watch")
            }
            Button(
                onClick = { filePicker.launch(arrayOf("text/*", "application/json")) },
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(if (transfer == null) "Upload file" else "Choose another file")
            }
            transfer?.let {
                Text("File mode: ${it.sentParts}/${it.totalParts} parts sent")
            }
            OutlinedTextField(
                value = input,
                onValueChange = { input = it },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(220.dp),
                label = { Text("Text to send") },
                minLines = 6
            )
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End
            ) {
                Button(
                    onClick = { controller.sendText(input) { status = it } },
                    enabled = input.isNotEmpty()
                ) {
                    Text("Submit")
                }
            }
        }
    }
}
