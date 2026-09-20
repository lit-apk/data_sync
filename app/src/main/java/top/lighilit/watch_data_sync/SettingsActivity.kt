package top.lighilit.watch_data_sync

import android.content.Context
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import top.lighilit.watch_data_sync.ui.theme.Watch_data_syncTheme

internal const val PREFERENCES = "data_sync_settings"
internal const val CHUNK_SIZE_KEY = "chunk_size"
internal const val HISTORY_LIMIT_KEY = "history_limit"
internal const val BACKUP_ON_SEND_KEY = "backup_on_send"

class SettingsActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            Watch_data_syncTheme {
                SettingsScreen(onBack = { finish() })
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SettingsScreen(onBack: () -> Unit) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val preferences = remember { context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE) }
    val historyStore = remember { ReadingHistoryStore(context.applicationContext) }
    var status by remember { mutableStateOf("") }
    var savedChunkSize by rememberSaveable {
        mutableStateOf(preferences.getInt(CHUNK_SIZE_KEY, FileTransfer.DEFAULT_CHUNK_SIZE).toString())
    }
    var savedHistoryLimit by rememberSaveable {
        mutableStateOf(preferences.getInt(HISTORY_LIMIT_KEY, 10).toString())
    }
    var savedBackupOnSend by rememberSaveable {
        mutableStateOf(preferences.getBoolean(BACKUP_ON_SEND_KEY, false))
    }
    var chunkSizeText by rememberSaveable { mutableStateOf(savedChunkSize) }
    var historyLimitText by rememberSaveable { mutableStateOf(savedHistoryLimit) }
    var backupOnSend by rememberSaveable { mutableStateOf(savedBackupOnSend) }
    var showLeaveDialog by rememberSaveable { mutableStateOf(false) }
    val controller = remember { DataSyncController(context.applicationContext) }

    val hasUnsavedChanges = chunkSizeText != savedChunkSize ||
        historyLimitText != savedHistoryLimit || backupOnSend != savedBackupOnSend

    fun saveSettings(): Boolean {
        val chunkSize = chunkSizeText.toIntOrNull()
        val limit = historyLimitText.toIntOrNull()
        if (chunkSize == null || chunkSize !in 1..10_000) {
            status = "Message size must be 1 to 10000"
            return false
        }
        if (limit == null || limit !in 1..100) {
            status = "History count must be 1 to 100"
            return false
        }
        preferences.edit()
            .putInt(CHUNK_SIZE_KEY, chunkSize)
            .putInt(HISTORY_LIMIT_KEY, limit)
            .putBoolean(BACKUP_ON_SEND_KEY, backupOnSend)
            .apply()
        historyStore.trim(limit)
        savedChunkSize = chunkSizeText
        savedHistoryLimit = historyLimitText
        savedBackupOnSend = backupOnSend
        status = "Settings saved"
        return true
    }

    fun requestLeave() {
        if (hasUnsavedChanges) showLeaveDialog = true else onBack()
    }

    BackHandler { requestLeave() }

    DisposableEffect(controller) {
        controller.connect({ status = it }, registerMessages = false)
        onDispose { controller.close() }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Settings") },
                navigationIcon = {
                    IconButton(onClick = { requestLeave() }) {
                        Icon(painterResource(R.drawable.ic_back), "Back")
                    }
                }
            )
        }
    ) { padding ->
        Column(
            Modifier.fillMaxSize().padding(padding).padding(20.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            if (status.isNotEmpty()) Text(status, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Button(
                onClick = {
                    controller.requestPermission({ status = it }, registerMessages = false)
                },
                modifier = Modifier.fillMaxWidth()
            ) { Text("Authorize watch") }
            SettingsNumberField("Maximum characters per message", chunkSizeText) {
                if (it.all(Char::isDigit)) chunkSizeText = it
            }
            SettingsNumberField("Reading history entries", historyLimitText) {
                if (it.all(Char::isDigit)) historyLimitText = it
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text("Back up file when sending")
                Checkbox(backupOnSend, { backupOnSend = it })
            }
            Button(
                onClick = { saveSettings() },
                modifier = Modifier.fillMaxWidth()
            ) { Text("Save settings") }
        }
    }

    if (showLeaveDialog) {
        AlertDialog(
            onDismissRequest = { showLeaveDialog = false },
            title = { Text("Save changes?") },
            text = { Text("Settings have been modified.") },
            confirmButton = {
                Button(onClick = {
                    if (saveSettings()) onBack()
                }) { Text("Save") }
            },
            dismissButton = {
                Button(onClick = onBack) { Text("Discard") }
            }
        )
    }
}

@Composable
private fun SettingsNumberField(label: String, value: String, onChange: (String) -> Unit) {
    OutlinedTextField(
        value = value,
        onValueChange = onChange,
        modifier = Modifier.fillMaxWidth(),
        label = { Text(label) },
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
        singleLine = true
    )
}
