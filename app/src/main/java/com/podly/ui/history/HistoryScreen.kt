package com.podly.ui.history

import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
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
import com.podly.radio.RadioProfiles
import com.podly.ui.appViewModel
import com.podly.ui.components.EpisodeNoteDialog
import com.podly.ui.util.formatDateTime
import com.podly.ui.util.formatDuration
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
    var filter by remember { mutableStateOf<ProfileFilter>(ProfileFilter.All) }

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
        val stats = remember(history, segments, filter) {
            ListeningStatsCalculator.compute(history, segments, System.currentTimeMillis(), filter)
        }
        val byId = remember(history) { history.associateBy { it.id } }
        val shown = remember(stats, byId) { stats.episodeIds.mapNotNull { byId[it] } }
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(12.dp),
        ) {
            item(key = "filter") {
                ProfileFilterRow(stats, filter) { filter = it }
                Spacer(Modifier.height(8.dp))
            }
            item(key = "stats") {
                StatsCard(stats)
                Spacer(Modifier.height(12.dp))
            }
            if (shown.isEmpty()) {
                item(key = "empty") {
                    Text(
                        "No listening recorded for this profile yet.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(16.dp),
                    )
                }
            }
            items(shown, key = { it.id }) { episode ->
                HistoryCard(
                    episode = episode,
                    heardMs = stats.heardMsByEpisode[episode.id] ?: episode.totalListenedMs,
                    segments = segmentsByEpisode[episode.id].orEmpty()
                        .filter { seg ->
                            filter is ProfileFilter.All ||
                                seg.profileId == (filter as ProfileFilter.Only).profileId
                        },
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
private fun StatsCard(stats: ListeningStats) {
    ElevatedCard(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(14.dp)) {
            Text("Listening stats", style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(10.dp))
            Row(modifier = Modifier.fillMaxWidth()) {
                StatTile("7 days", stats.last7DaysMs, Modifier.weight(1f))
                StatTile("30 days", stats.last30DaysMs, Modifier.weight(1f))
                StatTile("All time", stats.totalMs, Modifier.weight(1f))
            }
            Spacer(Modifier.height(8.dp))
            Text(
                "${stats.episodesTouched} episodes played · ${stats.episodesCompleted} finished",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (stats.topShows.isNotEmpty()) {
                Spacer(Modifier.height(12.dp))
                HorizontalDivider()
                Spacer(Modifier.height(8.dp))
                Text("Top shows", style = MaterialTheme.typography.titleSmall)
                Spacer(Modifier.height(6.dp))
                val most = stats.topShows.first().listenedMs.coerceAtLeast(1)
                stats.topShows.take(5).forEach { show ->
                    Row(modifier = Modifier.fillMaxWidth()) {
                        Text(
                            show.podcastTitle,
                            style = MaterialTheme.typography.bodyMedium,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f),
                        )
                        Text(
                            formatDuration(show.listenedMs) ?: "0m",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    LinearProgressIndicator(
                        progress = { show.listenedMs.toFloat() / most },
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 4.dp),
                    )
                }
            }
        }
    }
}

@Composable
private fun StatTile(label: String, ms: Long, modifier: Modifier = Modifier) {
    Column(modifier = modifier) {
        Text(formatDuration(ms) ?: "0m", style = MaterialTheme.typography.titleLarge)
        Text(
            label,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun HistoryCard(
    episode: EpisodeHistorySummary,
    /** Time heard under the active filter; the DAO's own total spans every profile. */
    heardMs: Long,
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
                    label = { Text("Heard ${formatPosition(heardMs)}") },
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

/**
 * All / per-profile chips. Unknown ids render as themselves rather than being
 * relabelled, so listening tagged by a profile that no longer exists stays honest.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ProfileFilterRow(
    stats: ListeningStats,
    selected: ProfileFilter,
    onSelect: (ProfileFilter) -> Unit,
) {
    if (stats.msByProfile.keys.none { it != null }) return
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        FilterChip(
            selected = selected is ProfileFilter.All,
            onClick = { onSelect(ProfileFilter.All) },
            label = { Text("All") },
        )
        stats.msByProfile.entries
            .sortedByDescending { it.value }
            .forEach { (profileId, ms) ->
                FilterChip(
                    selected = selected is ProfileFilter.Only && selected.profileId == profileId,
                    onClick = { onSelect(ProfileFilter.Only(profileId)) },
                    label = {
                        Text("${RadioProfiles.labelFor(profileId)} · ${formatDuration(ms) ?: "0m"}")
                    },
                )
            }
    }
}
