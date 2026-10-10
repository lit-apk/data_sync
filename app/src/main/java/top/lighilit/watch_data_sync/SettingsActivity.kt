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

private data class NumberSetting(
    val key: String,
    val labelRes: Int,
    val rangeRes: Int,
    val default: Int,
    val max: Int,
    val min: Int = 1,
)

private val NUMBER_SETTINGS = listOf(
    NumberSetting(CHUNK_SIZE_KEY, R.string.max_characters, R.string.message_size_range, FileTransfer.DEFAULT_CHUNK_SIZE, 10_000),
    NumberSetting(HISTORY_LIMIT_KEY, R.string.history_entries, R.string.history_count_range, 10, 100),
    NumberSetting(CACHE_SIZE_KEY, R.string.cache_size, R.string.cache_size_range, DEFAULT_CACHE_SIZE_MB, 512),
    NumberSetting(IMAGE_SIZE_KEY, R.string.image_size, R.string.image_size_range, DEFAULT_IMAGE_SIZE_KB, 10_240),
    NumberSetting(IMAGE_REDUCE_KEY, R.string.image_reduce, R.string.image_reduce_range, DEFAULT_IMAGE_REDUCE_PERCENT, 100),
)

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
    var savedValues by rememberSaveable {
        mutableStateOf(NUMBER_SETTINGS.associate { it.key to preferences.getInt(it.key, it.default).toString() })
    }
    var values by rememberSaveable { mutableStateOf(savedValues) }
    var backupOnSend by rememberSaveable {
        mutableStateOf(preferences.getBoolean(BACKUP_ON_SEND_KEY, false))
    }
    var savedBackupOnSend by rememberSaveable { mutableStateOf(backupOnSend) }
    var showLeaveDialog by rememberSaveable { mutableStateOf(false) }
    val controller = remember { DataSyncController(context.applicationContext) }

    val hasUnsavedChanges = values != savedValues || backupOnSend != savedBackupOnSend

    fun saveSettings(): Boolean {
        val prefEdit = preferences.edit()
        for (setting in NUMBER_SETTINGS) {
            val value = values[setting.key]?.toIntOrNull()
            if (value == null || value !in setting.min..setting.max) {
                status = resources.getString(setting.rangeRes, setting.min, setting.max)
                return false
            }
            prefEdit.putInt(setting.key, value)
        }
        prefEdit.putBoolean(BACKUP_ON_SEND_KEY, backupOnSend)
        prefEdit.apply()
        savedValues = values
        savedBackupOnSend = backupOnSend
        historyStore.trim(values[HISTORY_LIMIT_KEY]!!.toInt())
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
            NUMBER_SETTINGS.forEach { setting ->
                SettingsNumberField(stringResource(setting.labelRes), values[setting.key] ?: "") {
                    if (it.all(Char::isDigit)) values = values.toMutableMap().apply { this[setting.key] = it }
                }
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
