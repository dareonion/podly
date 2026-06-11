package com.podly.ui.podcast

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import coil.compose.AsyncImage
import com.podly.AppGraph
import com.podly.data.db.EpisodeEntity
import com.podly.ui.EpisodeActions
import com.podly.ui.appViewModel
import com.podly.ui.components.AddToPlaylistDialog
import com.podly.ui.components.DescriptionDialog
import com.podly.ui.components.EpisodeRow
import com.podly.ui.util.plainDescription
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class PodcastDetailViewModel(
    private val graph: AppGraph,
    private val podcastId: String,
) : ViewModel() {
    val actions = EpisodeActions(graph, viewModelScope)
    val podcast = graph.podcasts.podcast(podcastId)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)
    val episodes = graph.podcasts.episodesForPodcast(podcastId)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())
    val playlists = graph.playlists.playlists()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    fun toggleSubscribed() = viewModelScope.launch {
        podcast.value?.let { graph.podcasts.setSubscribed(it.id, !it.subscribed) }
    }
}

@Composable
fun PodcastDetailScreen(podcastId: String) {
    val viewModel = appViewModel(key = "podcast_$podcastId") {
        PodcastDetailViewModel(it, podcastId)
    }
    val podcast by viewModel.podcast.collectAsStateWithLifecycle()
    val episodes by viewModel.episodes.collectAsStateWithLifecycle()
    val playlists by viewModel.playlists.collectAsStateWithLifecycle()
    var episodeForPlaylist by remember { mutableStateOf<EpisodeEntity?>(null) }
    var episodeForDescription by remember { mutableStateOf<EpisodeEntity?>(null) }
    var showFullPodcastDescription by remember(podcast?.id) { mutableStateOf(false) }

    LazyColumn(modifier = Modifier.fillMaxSize()) {
        item {
            Row(modifier = Modifier.padding(16.dp)) {
                AsyncImage(
                    model = podcast?.artworkUrl,
                    contentDescription = null,
                    modifier = Modifier
                        .size(96.dp)
                        .clip(RoundedCornerShape(12.dp)),
                )
                Column(modifier = Modifier.padding(start = 16.dp)) {
                    Text(
                        podcast?.title ?: "",
                        style = MaterialTheme.typography.titleLarge,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        podcast?.author ?: "",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    if (podcast?.subscribed == true) {
                        OutlinedButton(onClick = viewModel::toggleSubscribed) { Text("Unsubscribe") }
                    } else {
                        Button(onClick = viewModel::toggleSubscribed) { Text("Subscribe") }
                    }
                }
            }
        }
        plainDescription(podcast?.description)?.let { description ->
            item {
                Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
                    Text(
                        description,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = if (showFullPodcastDescription) Int.MAX_VALUE else 4,
                        overflow = if (showFullPodcastDescription) TextOverflow.Clip else TextOverflow.Ellipsis,
                    )
                    if (description.length > 240 || description.count { it == '\n' } >= 4) {
                        TextButton(
                            onClick = { showFullPodcastDescription = !showFullPodcastDescription },
                            modifier = Modifier.padding(top = 4.dp),
                        ) {
                            Text(if (showFullPodcastDescription) "Show less" else "Show full description")
                        }
                    }
                }
            }
        }
        item {
            Text(
                "${episodes.size} episodes",
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.padding(16.dp),
            )
        }
        items(episodes, key = { it.id }) { episode ->
            EpisodeRow(
                episode = episode,
                onPlay = { viewModel.actions.play(episodes, episodes.indexOf(episode)) },
                onToggleLibrary = { viewModel.actions.toggleLibrary(episode) },
                onDownload = { viewModel.actions.download(episode) },
                onRemoveDownload = { viewModel.actions.removeDownload(episode) },
                onAddToPlaylist = { episodeForPlaylist = episode },
                onShowDescription = { episodeForDescription = episode },
            )
        }
    }

    episodeForPlaylist?.let { episode ->
        AddToPlaylistDialog(
            playlists = playlists,
            onAdd = { playlistId ->
                viewModel.actions.addToPlaylist(playlistId, episode.id)
                episodeForPlaylist = null
            },
            onCreateAndAdd = { name ->
                viewModel.actions.createPlaylistAndAdd(name, episode.id)
                episodeForPlaylist = null
            },
            onDismiss = { episodeForPlaylist = null },
        )
    }

    episodeForDescription?.let { episode ->
        plainDescription(episode.description)?.let { description ->
            DescriptionDialog(
                title = episode.title,
                description = description,
                onDismiss = { episodeForDescription = null },
            )
        }
    }
}
