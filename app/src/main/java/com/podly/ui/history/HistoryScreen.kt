package com.podly.ui.history

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.EditNote
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.podly.AppGraph
import com.podly.data.db.EpisodeHistorySummary
import com.podly.data.db.ListeningSegmentEntity
import com.podly.ui.appViewModel
import com.podly.ui.components.EpisodeNoteDialog
import com.podly.ui.util.formatDateTime
import com.podly.ui.util.formatPosition
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class HistoryViewModel(private val graph: AppGraph) : ViewModel() {
    val history = graph.podcasts.listeningHistory()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())
    val segments = graph.podcasts.listeningSegments()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    fun updateNoteAndRating(episodeId: String, note: String?, rating: Int?) {
        viewModelScope.launch {
            graph.podcasts.updateEpisodeNoteAndRating(episodeId, note, rating)
        }
    }
}

@Composable
fun HistoryScreen() {
    val viewModel = appViewModel { HistoryViewModel(it) }
    val history by viewModel.history.collectAsStateWithLifecycle()
    val segments by viewModel.segments.collectAsStateWithLifecycle()
    val segmentsByEpisode = remember(segments) { segments.groupBy { it.episodeId } }
    var editingEpisode by remember { mutableStateOf<EpisodeHistorySummary?>(null) }

    if (history.isEmpty()) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(24.dp),
        ) {
            Text("No listening history yet", style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(8.dp))
            Text(
                "Play an episode and Podly will record the episode ranges you listened to.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    } else {
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(12.dp),
        ) {
            items(history, key = { it.id }) { episode ->
                HistoryCard(
                    episode = episode,
                    segments = segmentsByEpisode[episode.id].orEmpty(),
                    onEdit = { editingEpisode = episode },
                )
                Spacer(Modifier.height(12.dp))
            }
        }
    }

    editingEpisode?.let { episode ->
        EpisodeNoteDialog(
            title = episode.title,
            initialNote = episode.userNote,
            initialRating = episode.userRating,
            onSave = { note, rating ->
                viewModel.updateNoteAndRating(episode.id, note, rating)
                editingEpisode = null
            },
            onDismiss = { editingEpisode = null },
        )
    }
}

@Composable
private fun HistoryCard(
    episode: EpisodeHistorySummary,
    segments: List<ListeningSegmentEntity>,
    onEdit: () -> Unit,
) {
    ElevatedCard(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(14.dp)) {
            Text(
                episode.title,
                style = MaterialTheme.typography.titleMedium,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                episode.podcastTitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(Modifier.height(8.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                AssistChip(
                    onClick = {},
                    label = { Text("Heard ${formatPosition(episode.totalListenedMs)}") },
                )
                Spacer(Modifier.width(8.dp))
                AssistChip(
                    onClick = {},
                    label = { Text("${episode.segmentCount} range${if (episode.segmentCount == 1) "" else "s"}") },
                )
                episode.userRating?.let { rating ->
                    Spacer(Modifier.width(8.dp))
                    AssistChip(
                        onClick = {},
                        label = { Text("$rating/5") },
                        leadingIcon = { Icon(Icons.Filled.Star, null) },
                    )
                }
            }
            formatDateTime(episode.lastListenedAt)?.let {
                Spacer(Modifier.height(4.dp))
                Text(
                    "Last listened $it",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (!episode.userNote.isNullOrBlank()) {
                Spacer(Modifier.height(8.dp))
                Text(episode.userNote, style = MaterialTheme.typography.bodyMedium)
            }
            Spacer(Modifier.height(10.dp))
            Button(onClick = onEdit) {
                Icon(Icons.Filled.EditNote, null)
                Spacer(Modifier.width(6.dp))
                Text(if (episode.userNote.isNullOrBlank() && episode.userRating == null) "Add note" else "Edit note")
            }
            if (segments.isNotEmpty()) {
                Spacer(Modifier.height(12.dp))
                HorizontalDivider()
                Spacer(Modifier.height(8.dp))
                segments.take(10).forEach { segment ->
                    Text(
                        "${formatPosition(segment.startPositionMs)} - ${formatPosition(segment.endPositionMs)}" +
                            (formatDateTime(segment.endedAt)?.let { " · $it" } ?: ""),
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
                if (segments.size > 10) {
                    Text(
                        "+ ${segments.size - 10} more ranges",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}
