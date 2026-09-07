package com.podly.ui.transcript

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.podly.AppGraph
import com.podly.data.TranscriptRepository
import com.podly.ui.appViewModel
import com.podly.ui.util.formatPosition
import com.podly.ui.util.friendlyError
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

class TranscriptViewModel(
    private val graph: AppGraph,
    private val episodeId: String,
) : ViewModel() {

    private val _state = MutableStateFlow<TranscriptRepository.Result?>(null)
    val state: StateFlow<TranscriptRepository.Result?> = _state

    init {
        viewModelScope.launch { _state.value = graph.transcripts.transcript(episodeId) }
    }

    /**
     * Seeks only when this episode is the one playing. Starting playback because
     * somebody tapped a line while reading would be a surprise, not a feature.
     */
    fun seekTo(positionMs: Long) {
        if (isPlayingThis()) graph.player.seekTo(positionMs)
    }

    fun isPlayingThis(): Boolean = graph.player.state.value.episodeId == episodeId
}

@Composable
fun TranscriptScreen(episodeId: String, onBack: () -> Unit) {
    val viewModel = appViewModel { TranscriptViewModel(it, episodeId) }
    val result by viewModel.state.collectAsStateWithLifecycle()

    Column(modifier = Modifier.fillMaxSize()) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(end = 16.dp, top = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") }
            Text("Transcript", style = MaterialTheme.typography.titleMedium)
        }

        when (val state = result) {
            null -> Column(
                modifier = Modifier.fillMaxSize().padding(24.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) { CircularProgressIndicator() }

            is TranscriptRepository.Result.None ->
                Message("This episode's feed doesn't publish a transcript.")

            is TranscriptRepository.Result.Failed ->
                Message("Couldn't load the transcript: ${friendlyError(state.error)}")

            is TranscriptRepository.Result.Ready ->
                if (state.cues.isEmpty()) {
                    Message("The transcript file was empty.")
                } else {
                    // Only offered while this episode is the one playing; seeking
                    // an episode you are merely reading would be a surprise.
                    val seekable = viewModel.isPlayingThis()
                    LazyColumn(
                        modifier = Modifier.fillMaxSize(),
                        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 32.dp),
                    ) {
                        items(state.cues) { cue ->
                            Column(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .then(
                                        if (seekable && cue.startMs != null) {
                                            Modifier.clickable { viewModel.seekTo(cue.startMs) }
                                        } else {
                                            Modifier
                                        },
                                    )
                                    .padding(vertical = 6.dp),
                            ) {
                                val heading = listOfNotNull(
                                    cue.speaker,
                                    cue.startMs?.let { formatPosition(it) },
                                ).joinToString("  ")
                                if (heading.isNotEmpty()) {
                                    Text(
                                        heading,
                                        style = MaterialTheme.typography.labelMedium,
                                        fontWeight = FontWeight.Medium,
                                        color = MaterialTheme.colorScheme.primary,
                                    )
                                }
                                Text(cue.text, style = MaterialTheme.typography.bodyMedium)
                            }
                        }
                    }
                }
        }
    }
}

@Composable
private fun Message(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(16.dp),
    )
}
