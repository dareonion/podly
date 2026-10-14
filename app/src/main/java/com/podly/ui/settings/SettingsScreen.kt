package com.podly.ui.settings

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
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
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
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
import com.podly.BuildConfig
import com.podly.data.AiProvider
import com.podly.network.ai.ApiKeyCheck
import com.podly.network.ai.KeyCheckResult
import com.podly.data.OpmlImportResult
import com.podly.data.Settings
import com.podly.network.TrendingPeriod
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
    /** Saves the creds, then proves them with a live API call; returns a status line. */
    suspend fun saveAndVerifyPodcastIndex(key: String, secret: String): String {
        graph.settings.setPodcastIndexCreds(key, secret)
        if (key.isBlank() || secret.isBlank()) return "Cleared PodcastIndex keys."
        return runCatching {
            graph.podcastIndex.trending(key.trim(), secret.trim(), TrendingPeriod.NOW)
        }.fold(
            { "Saved — PodcastIndex accepted the keys." },
            { "Saved, but the key check failed: ${it.message ?: "unknown error"}" },
        )
    }

    suspend fun saveAndVerifyTaddy(userId: String, apiKey: String): String {
        graph.settings.setTaddyCreds(userId, apiKey)
        if (userId.isBlank() || apiKey.isBlank()) return "Cleared Taddy keys."
        return runCatching {
            graph.taddy.seriesByFeedUrl(userId.trim(), apiKey.trim(), TADDY_TEST_FEED, limit = 1)
        }.fold(
            { "Saved — Taddy accepted the key." },
            {
                val hint = if (!userId.trim().all(Char::isDigit)) {
                    " (The user ID is the number on your taddy.org dashboard, not your email.)"
                } else {
                    ""
                }
                "Saved, but the key check failed: ${it.message ?: "unknown error"}$hint"
            },
        )
    }
    fun setSeekIncrements(back: Int, forward: Int) =
        viewModelScope.launch { graph.settings.setSeekIncrements(back, forward) }
    fun setDownloadWifiOnly(wifiOnly: Boolean) =
        viewModelScope.launch { graph.settings.setDownloadWifiOnly(wifiOnly) }
    fun setAutoDownloadCount(count: Int) =
        viewModelScope.launch { graph.settings.setAutoDownloadCount(count) }
    fun setAutoDeleteCompleted(enabled: Boolean) =
        viewModelScope.launch { graph.settings.setAutoDeleteCompleted(enabled) }
    fun setAutoDownloadStarted(enabled: Boolean) =
        viewModelScope.launch { graph.settings.setAutoDownloadStarted(enabled) }

    suspend fun importOpml(text: String): OpmlImportResult =
        graph.podcasts.importOpml(StringReader(text))

    suspend fun exportOpml(): String =
        graph.podcasts.exportOpml()

    companion object {
        // Any feed Taddy knows works here: bad creds error before the lookup happens.
        const val TADDY_TEST_FEED = "https://feeds.simplecast.com/54nAGcIl" // The Daily
    }
}

@Composable
fun SettingsScreen() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val viewModel = appViewModel { SettingsViewModel(it) }
    val settings by viewModel.settings.collectAsStateWithLifecycle()

    // Each draft reloads only when its own stored value changes. Keyed on the whole
    // Settings object, as these once were, any toggle or chip tap reset every field and
    // threw away a key typed but not yet saved.
    var anthropicKey by remember(settings.anthropicApiKey) { mutableStateOf(settings.anthropicApiKey) }
    var openAiKey by remember(settings.openAiApiKey) { mutableStateOf(settings.openAiApiKey) }
    var piKey by remember(settings.podcastIndexKey) { mutableStateOf(settings.podcastIndexKey) }
    var piSecret by remember(settings.podcastIndexSecret) { mutableStateOf(settings.podcastIndexSecret) }
    var taddyUserId by remember(settings.taddyUserId) { mutableStateOf(settings.taddyUserId) }
    var taddyApiKey by remember(settings.taddyApiKey) { mutableStateOf(settings.taddyApiKey) }
    var aiKeysStatus by remember { mutableStateOf<String?>(null) }
    var piStatus by remember { mutableStateOf<String?>(null) }
    var taddyStatus by remember { mutableStateOf<String?>(null) }
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
    var keyCheck by remember { mutableStateOf<KeyCheckResult?>(null) }
    var keyChecking by remember { mutableStateOf(false) }

    // Credentials matter only to the features that use them, and every visit used to
    // open on four sections of them; they stay folded away until asked for.
    var servicesOpen by rememberSaveable { mutableStateOf(false) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text("Settings", style = MaterialTheme.typography.titleLarge)

        Text("Seek buttons", style = MaterialTheme.typography.titleMedium)
        Text(
            "How far the player's skip buttons jump, and car and headset previous/next buttons too.",
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
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text("Download episodes you've started", style = MaterialTheme.typography.bodyMedium)
                Text(
                    "Partially played episodes download over Wi-Fi so they keep playing away from it.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Switch(
                checked = settings.autoDownloadStarted,
                onCheckedChange = viewModel::setAutoDownloadStarted,
            )
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

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable { servicesOpen = !servicesOpen },
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text("Services & keys", style = MaterialTheme.typography.titleMedium)
                Text(
                    "AI recommendations, PodcastIndex and Taddy. Only the features that use them need them.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Icon(
                if (servicesOpen) Icons.Filled.ExpandLess else Icons.Filled.ExpandMore,
                if (servicesOpen) "Collapse" else "Expand",
            )
        }
        if (servicesOpen) {
            Text("AI recommendations", style = MaterialTheme.typography.titleSmall)
            Text(
                "Used by AI picks on Discover and by Where to start on a podcast's page.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
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
            KeyField(anthropicKey, { anthropicKey = it }, "Anthropic API key")
            KeyField(openAiKey, { openAiKey = it }, "OpenAI API key")
            // Save and verify in one press, like the directory keys below: a bad or
            // unfunded key otherwise only shows up when a feature fails, weeks later.
            Button(
                enabled = !keyChecking,
                onClick = {
                    viewModel.setAnthropicKey(anthropicKey)
                    viewModel.setOpenAiKey(openAiKey)
                    val provider = settings.aiProvider
                    val key = if (provider == AiProvider.CLAUDE) anthropicKey else openAiKey
                    keyCheck = null
                    if (key.isBlank()) {
                        aiKeysStatus = "Saved."
                    } else {
                        aiKeysStatus = null
                        keyChecking = true
                        scope.launch {
                            keyCheck = ApiKeyCheck.validate(provider, key)
                            keyChecking = false
                        }
                    }
                },
            ) { Text(if (keyChecking) "Checking…" else "Save AI keys") }
            aiKeysStatus?.let {
                Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            keyCheck?.let { result ->
                Text(
                    result.message,
                    style = MaterialTheme.typography.bodySmall,
                    color = if (result.ok) MaterialTheme.colorScheme.onSurfaceVariant
                    else MaterialTheme.colorScheme.error,
                )
                // Only inference touches the balance, so the credit state costs a
                // (tiny) call — kept behind its own tap rather than run for you.
                if (result.ok && settings.aiProvider == AiProvider.CLAUDE) {
                    TextButton(
                        enabled = !keyChecking,
                        onClick = {
                            keyChecking = true
                            scope.launch {
                                keyCheck = ApiKeyCheck.checkCredits(anthropicKey)
                                keyChecking = false
                            }
                        },
                    ) { Text("Also check credits (sends 1 token)") }
                }
            }

            Text("Podcast directories", style = MaterialTheme.typography.titleSmall)
            Text(
                "Free with an account. PodcastIndex adds Week and Month to Popular podcasts. Both " +
                    "recover episodes a show has dropped from its feed, and look up feeds Apple " +
                    "doesn't share. PodcastIndex: a key and secret from api.podcastindex.org. Taddy: " +
                    "the user ID is the number on your taddy.org dashboard, not your email.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            KeyField(piKey, { piKey = it }, "PodcastIndex API key")
            KeyField(piSecret, { piSecret = it }, "PodcastIndex API secret")
            Button(onClick = {
                scope.launch {
                    piStatus = "Checking keys with PodcastIndex…"
                    piStatus = viewModel.saveAndVerifyPodcastIndex(piKey, piSecret)
                }
            }) {
                Text("Save PodcastIndex keys")
            }
            piStatus?.let {
                Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            KeyField(taddyUserId, { taddyUserId = it }, "Taddy user ID (numeric)", secret = false)
            KeyField(taddyApiKey, { taddyApiKey = it }, "Taddy API key")
            Button(onClick = {
                scope.launch {
                    taddyStatus = "Checking key with Taddy…"
                    taddyStatus = viewModel.saveAndVerifyTaddy(taddyUserId, taddyApiKey)
                }
            }) {
                Text("Save Taddy keys")
            }
            taddyStatus?.let {
                Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }

        HorizontalDivider()

        Text("About", style = MaterialTheme.typography.titleMedium)
        Text(
            "Podly ${BuildConfig.VERSION_NAME} · build ${BuildConfig.GIT_DESCRIBE} · ${BuildConfig.GIT_COMMIT_DATE}",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/**
 * A credential field, masked until the eye is tapped. The screen used to show every
 * key in clear, so a glance, a screenshot or a screen-text dump carried them all.
 */
@Composable
private fun KeyField(value: String, onValueChange: (String) -> Unit, label: String, secret: Boolean = true) {
    var shown by remember { mutableStateOf(false) }
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        label = { Text(label) },
        singleLine = true,
        modifier = Modifier.fillMaxWidth(),
        visualTransformation = if (secret && !shown) PasswordVisualTransformation() else VisualTransformation.None,
        keyboardOptions = KeyboardOptions(keyboardType = if (secret) KeyboardType.Password else KeyboardType.Number),
        trailingIcon = if (secret) {
            {
                IconButton(onClick = { shown = !shown }) {
                    Icon(
                        if (shown) Icons.Filled.VisibilityOff else Icons.Filled.Visibility,
                        if (shown) "Hide key" else "Show key",
                    )
                }
            }
        } else {
            null
        },
    )
}
