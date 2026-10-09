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
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import top.lighilit.watch_data_sync.ui.theme.Watch_data_syncTheme

internal const val PREFERENCES = "data_sync_settings"
internal const val CHUNK_SIZE_KEY = "chunk_size"
internal const val HISTORY_LIMIT_KEY = "history_limit"
internal const val BACKUP_ON_SEND_KEY = "backup_on_send"
internal const val MAX_CHUNK_SIZE = 10_000
internal const val MAX_HISTORY_LIMIT = 100
internal const val MAX_CACHE_SIZE_MB = 512
internal const val MAX_IMAGE_SIZE_KB = 10240
internal const val MAX_IMAGE_REDUCE_PERCENT = 100

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
    val resources = LocalResources.current
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
    var savedCacheSize by rememberSaveable {
        mutableStateOf(preferences.getInt(CACHE_SIZE_KEY, DEFAULT_CACHE_SIZE_MB).toString())
    }
    var savedImageSize by rememberSaveable {
        mutableStateOf(preferences.getInt(IMAGE_SIZE_KEY, DEFAULT_IMAGE_SIZE_KB).toString())
    }
    var savedImageReduce by rememberSaveable {
        mutableStateOf(preferences.getInt(IMAGE_REDUCE_KEY, DEFAULT_IMAGE_REDUCE_PERCENT).toString())
    }
    var chunkSizeText by rememberSaveable { mutableStateOf(savedChunkSize) }
    var historyLimitText by rememberSaveable { mutableStateOf(savedHistoryLimit) }
    var backupOnSend by rememberSaveable { mutableStateOf(savedBackupOnSend) }
    var cacheSize by rememberSaveable { mutableStateOf(savedCacheSize) }
    var imageSize by rememberSaveable { mutableStateOf(savedImageSize) }
    var imageReduce by rememberSaveable { mutableStateOf(savedImageReduce) }
    var showLeaveDialog by rememberSaveable { mutableStateOf(false) }
    val controller = remember { DataSyncController(context.applicationContext) }

    val hasUnsavedChanges = chunkSizeText != savedChunkSize ||
        historyLimitText != savedHistoryLimit || backupOnSend != savedBackupOnSend || cacheSize != savedCacheSize || imageSize != savedImageSize || imageReduce != savedImageReduce

    fun saveSettings(): Boolean {
        val chunkSize = chunkSizeText.toIntOrNull()
        val limit = historyLimitText.toIntOrNull()
        val cacheSizeValue = cacheSize.toIntOrNull()
        val imageSizeValue = imageSize.toIntOrNull()
        val imageReduceValue = imageReduce.toIntOrNull()
        if (chunkSize == null || chunkSize !in 1..MAX_CHUNK_SIZE) {
            status = resources.getString(R.string.message_size_range, MAX_CHUNK_SIZE)
            return false
        }
        if (limit == null || limit !in 1..MAX_HISTORY_LIMIT) {
            status = resources.getString(R.string.history_count_range, MAX_HISTORY_LIMIT)
            return false
        }
        if (cacheSizeValue == null || cacheSizeValue !in 1..MAX_CACHE_SIZE_MB) {
            status = resources.getString(R.string.cache_size_range, MAX_CACHE_SIZE_MB)
            return false
        }
        if (imageSizeValue == null || imageSizeValue !in 1..MAX_IMAGE_SIZE_KB) {
            status = resources.getString(R.string.image_size_positive)
            return false
        }
        if (imageReduceValue == null || imageReduceValue !in 1..MAX_IMAGE_REDUCE_PERCENT) {
            status = resources.getString(R.string.image_reduce_range)
            return false
        }
        preferences.edit()
            .putInt(CHUNK_SIZE_KEY, chunkSize)
            .putInt(HISTORY_LIMIT_KEY, limit)
            .putBoolean(BACKUP_ON_SEND_KEY, backupOnSend)
            .putInt(CACHE_SIZE_KEY, cacheSizeValue)
            .putInt(IMAGE_SIZE_KEY, imageSizeValue)
            .putInt(IMAGE_REDUCE_KEY, imageReduceValue)
            .apply()
        historyStore.trim(limit)
        savedChunkSize = chunkSizeText
        savedHistoryLimit = historyLimitText
        savedBackupOnSend = backupOnSend
        savedCacheSize = cacheSize
        savedImageSize = imageSize
        savedImageReduce = imageReduce
        status = resources.getString(R.string.settings_saved)
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
                title = { Text(stringResource(R.string.settings)) },
                navigationIcon = {
                    IconButton(onClick = { requestLeave() }) {
                        Icon(painterResource(R.drawable.ic_back), stringResource(R.string.back))
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
            ) { Text(stringResource(R.string.authorize_watch)) }
            SettingsNumberField(stringResource(R.string.max_characters), chunkSizeText) {
                if (it.all(Char::isDigit)) chunkSizeText = it
            }
            SettingsNumberField(stringResource(R.string.history_entries), historyLimitText) {
                if (it.all(Char::isDigit)) historyLimitText = it
            }
            SettingsNumberField(stringResource(R.string.cache_size), cacheSize) {
                if (it.all(Char::isDigit)) cacheSize = it
            }
            SettingsNumberField(stringResource(R.string.image_size), imageSize) {
                if (it.all(Char::isDigit)) imageSize = it
            }
            SettingsNumberField(stringResource(R.string.image_reduce), imageReduce) {
                if (it.all(Char::isDigit)) imageReduce = it
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text(stringResource(R.string.backup_when_sending))
                Checkbox(backupOnSend, { backupOnSend = it })
            }
            Button(
                onClick = { saveSettings() },
                modifier = Modifier.fillMaxWidth()
            ) { Text(stringResource(R.string.save_settings)) }
        }
    }

    if (showLeaveDialog) {
        AlertDialog(
            onDismissRequest = { showLeaveDialog = false },
            title = { Text(stringResource(R.string.save_changes)) },
            text = { Text(stringResource(R.string.settings_modified)) },
            confirmButton = {
                Button(onClick = {
                    if (saveSettings()) onBack()
                }) { Text(stringResource(R.string.save)) }
            },
            dismissButton = {
                Button(onClick = onBack) { Text(stringResource(R.string.discard)) }
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
