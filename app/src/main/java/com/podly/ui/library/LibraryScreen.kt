package com.podly.ui.library

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
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
import com.podly.data.db.EpisodeEntity
import com.podly.ui.EpisodeActions
import com.podly.ui.appViewModel
import com.podly.ui.components.AddToPlaylistDialog
import com.podly.ui.components.EpisodeRow
import com.podly.ui.components.PodcastCard
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.stateIn

class LibraryViewModel(graph: AppGraph) : ViewModel() {
    val actions = EpisodeActions(graph, viewModelScope)
    val podcasts = graph.podcasts.subscribedPodcasts()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())
    val episodes = graph.podcasts.libraryEpisodes()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())
    val playlists = graph.playlists.playlists()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())
}

@Composable
fun LibraryScreen(onOpenPodcast: (String) -> Unit) {
    val viewModel = appViewModel { LibraryViewModel(it) }
    val podcasts by viewModel.podcasts.collectAsStateWithLifecycle()
    val episodes by viewModel.episodes.collectAsStateWithLifecycle()
    val playlists by viewModel.playlists.collectAsStateWithLifecycle()
    var episodeForPlaylist by remember { mutableStateOf<EpisodeEntity?>(null) }

    LazyColumn(modifier = Modifier.fillMaxSize()) {
        item {
            Text(
                "Podcasts",
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.padding(16.dp),
            )
        }
        item {
            if (podcasts.isEmpty()) {
                Text(
                    "No subscriptions yet. Find podcasts in Discover.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 16.dp),
                )
            } else {
                LazyRow(contentPadding = PaddingValues(horizontal = 12.dp)) {
                    items(podcasts, key = { it.id }) { podcast ->
                        PodcastCard(podcast.title, podcast.artworkUrl) { onOpenPodcast(podcast.id) }
                    }
                }
            }
        }
        item {
            Text(
                "Episodes",
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
}
