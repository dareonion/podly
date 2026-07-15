package com.podly.ui.settings

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.podly.AppGraph
import com.podly.data.AiProvider
import com.podly.data.OpmlImportResult
import com.podly.data.Settings
import com.podly.ui.appViewModel
import java.io.StringReader
import java.nio.charset.StandardCharsets
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class SettingsViewModel(private val graph: AppGraph) : ViewModel() {
    val settings = graph.settings.settings
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), Settings())

    fun setProvider(provider: AiProvider) = viewModelScope.launch { graph.settings.setAiProvider(provider) }
    fun setAnthropicKey(key: String) = viewModelScope.launch { graph.settings.setAnthropicApiKey(key) }
    fun setOpenAiKey(key: String) = viewModelScope.launch { graph.settings.setOpenAiApiKey(key) }
    fun setPodcastIndexCreds(key: String, secret: String) =
        viewModelScope.launch { graph.settings.setPodcastIndexCreds(key, secret) }
    fun setSeekIncrements(back: Int, forward: Int) =
        viewModelScope.launch { graph.settings.setSeekIncrements(back, forward) }
    fun setDownloadWifiOnly(wifiOnly: Boolean) =
        viewModelScope.launch { graph.settings.setDownloadWifiOnly(wifiOnly) }
    fun setAutoDownloadCount(count: Int) =
        viewModelScope.launch { graph.settings.setAutoDownloadCount(count) }
    fun setAutoDeleteCompleted(enabled: Boolean) =
        viewModelScope.launch { graph.settings.setAutoDeleteCompleted(enabled) }

    suspend fun importOpml(text: String): OpmlImportResult =
        graph.podcasts.importOpml(StringReader(text))

    suspend fun exportOpml(): String =
        graph.podcasts.exportOpml()
}

@Composable
fun SettingsScreen() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val viewModel = appViewModel { SettingsViewModel(it) }
    val settings by viewModel.settings.collectAsStateWithLifecycle()

    var anthropicKey by remember { mutableStateOf("") }
    var openAiKey by remember { mutableStateOf("") }
    var piKey by remember { mutableStateOf("") }
    var piSecret by remember { mutableStateOf("") }
    var opmlStatus by remember { mutableStateOf<String?>(null) }
    var pendingOpmlExport by remember { mutableStateOf<String?>(null) }
    val exportOpmlLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("text/x-opml"),
    ) { uri ->
        val opml = pendingOpmlExport ?: return@rememberLauncherForActivityResult
        pendingOpmlExport = null
        if (uri == null) {
            opmlStatus = "Export canceled"
            return@rememberLauncherForActivityResult
        }
        scope.launch {
            runCatching {
                withContext(Dispatchers.IO) {
                    context.contentResolver.openOutputStream(uri)?.use { output ->
                        output.write(opml.toByteArray(StandardCharsets.UTF_8))
                    } ?: error("Could not open export file")
                }
            }.onSuccess {
                opmlStatus = "Exported OPML"
            }.onFailure {
                opmlStatus = "Export failed: ${it.message ?: "unknown error"}"
            }
        }
    }
    val importOpmlLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument(),
    ) { uri ->
        if (uri == null) {
            opmlStatus = "Import canceled"
            return@rememberLauncherForActivityResult
        }
        scope.launch {
            opmlStatus = "Importing OPML..."
            runCatching {
                val text = withContext(Dispatchers.IO) {
                    context.contentResolver.openInputStream(uri)?.bufferedReader()?.use { it.readText() }
                        ?: error("Could not open OPML file")
                }
                withContext(Dispatchers.IO) { viewModel.importOpml(text) }
            }.onSuccess { result ->
                opmlStatus = "Imported ${result.added} new, refreshed ${result.refreshed}/${result.total}" +
                    if (result.failedRefreshes > 0) ", ${result.failedRefreshes} refresh failed" else ""
            }.onFailure {
                opmlStatus = "Import failed: ${it.message ?: "unknown error"}"
            }
        }
    }
    LaunchedEffect(settings) {
        anthropicKey = settings.anthropicApiKey
        openAiKey = settings.openAiApiKey
        piKey = settings.podcastIndexKey
        piSecret = settings.podcastIndexSecret
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text("Settings", style = MaterialTheme.typography.titleLarge)

        Text("AI recommendations", style = MaterialTheme.typography.titleMedium)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilterChip(
                selected = settings.aiProvider == AiProvider.CLAUDE,
                onClick = { viewModel.setProvider(AiProvider.CLAUDE) },
                label = { Text("Claude") },
            )
            FilterChip(
                selected = settings.aiProvider == AiProvider.OPENAI,
                onClick = { viewModel.setProvider(AiProvider.OPENAI) },
                label = { Text("OpenAI") },
            )
        }
        OutlinedTextField(
            value = anthropicKey,
            onValueChange = { anthropicKey = it },
            label = { Text("Anthropic API key") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        OutlinedTextField(
            value = openAiKey,
            onValueChange = { openAiKey = it },
            label = { Text("OpenAI API key") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        Button(onClick = {
            viewModel.setAnthropicKey(anthropicKey)
            viewModel.setOpenAiKey(openAiKey)
        }) { Text("Save AI keys") }

        HorizontalDivider()

        Text("PodcastIndex (Week/Month trending)", style = MaterialTheme.typography.titleMedium)
        Text(
            "Sign up free at api.podcastindex.org to get a key and secret.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        OutlinedTextField(
            value = piKey,
            onValueChange = { piKey = it },
            label = { Text("API key") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        OutlinedTextField(
            value = piSecret,
            onValueChange = { piSecret = it },
            label = { Text("API secret") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        Button(onClick = { viewModel.setPodcastIndexCreds(piKey, piSecret) }) {
            Text("Save PodcastIndex keys")
        }

        HorizontalDivider()

        Text("Subscriptions", style = MaterialTheme.typography.titleMedium)
        Text(
            "Import or export subscribed podcast feeds as OPML.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = { importOpmlLauncher.launch(arrayOf("text/*", "application/xml", "*/*")) }) {
                Text("Import OPML")
            }
            Button(onClick = {
                scope.launch {
                    opmlStatus = "Preparing OPML..."
                    runCatching { withContext(Dispatchers.IO) { viewModel.exportOpml() } }
                        .onSuccess { opml ->
                            pendingOpmlExport = opml
                            exportOpmlLauncher.launch("podly-subscriptions.opml")
                        }
                        .onFailure {
                            opmlStatus = "Export failed: ${it.message ?: "unknown error"}"
                        }
                }
            }) {
                Text("Export OPML")
            }
        }
        opmlStatus?.let {
            Text(
                it,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        HorizontalDivider()

        Text("Downloads", style = MaterialTheme.typography.titleMedium)
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text("Download on Wi-Fi only", style = MaterialTheme.typography.bodyMedium)
                Text(
                    "Applies to downloads started after the change.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Switch(
                checked = settings.downloadWifiOnly,
                onCheckedChange = viewModel::setDownloadWifiOnly,
            )
        }
        Text(
            "Auto-download newest episodes per subscribed show",
            style = MaterialTheme.typography.bodyMedium,
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf(0, 1, 2, 3, 5).forEach { count ->
                FilterChip(
                    selected = settings.autoDownloadCount == count,
                    onClick = { viewModel.setAutoDownloadCount(count) },
                    label = { Text(if (count == 0) "Off" else "$count") },
                )
            }
        }
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text("Delete downloads when played", style = MaterialTheme.typography.bodyMedium)
                Text(
                    "Frees space after an episode finishes or is marked played.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Switch(
                checked = settings.autoDeleteCompleted,
                onCheckedChange = viewModel::setAutoDeleteCompleted,
            )
        }

        HorizontalDivider()

        Text("Seek buttons", style = MaterialTheme.typography.titleMedium)
        Text(
            "Car and headset previous/next buttons jump by these amounts.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text("Back: ${settings.seekBackSeconds}s", style = MaterialTheme.typography.bodyMedium)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf(5, 10, 15, 30).forEach { seconds ->
                FilterChip(
                    selected = settings.seekBackSeconds == seconds,
                    onClick = { viewModel.setSeekIncrements(seconds, settings.seekForwardSeconds) },
                    label = { Text("${seconds}s") },
                )
            }
        }
        Text("Forward: ${settings.seekForwardSeconds}s", style = MaterialTheme.typography.bodyMedium)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf(10, 15, 30, 45, 60).forEach { seconds ->
                FilterChip(
                    selected = settings.seekForwardSeconds == seconds,
                    onClick = { viewModel.setSeekIncrements(settings.seekBackSeconds, seconds) },
                    label = { Text("${seconds}s") },
                )
            }
        }
    }
}
