package com.podly.ui.settings

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
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.podly.AppGraph
import com.podly.data.AiProvider
import com.podly.data.Settings
import com.podly.ui.appViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

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
}

@Composable
fun SettingsScreen() {
    val viewModel = appViewModel { SettingsViewModel(it) }
    val settings by viewModel.settings.collectAsStateWithLifecycle()

    var anthropicKey by remember { mutableStateOf("") }
    var openAiKey by remember { mutableStateOf("") }
    var piKey by remember { mutableStateOf("") }
    var piSecret by remember { mutableStateOf("") }
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

        Text("Seek buttons", style = MaterialTheme.typography.titleMedium)
        Text(
            "Car and headset previous/next buttons jump by these amounts. Changes apply after playback restarts.",
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
