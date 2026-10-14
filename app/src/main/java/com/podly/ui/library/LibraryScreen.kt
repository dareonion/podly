package com.podly.ui.library

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.DateRange
import androidx.compose.material.icons.filled.EmojiEvents
import androidx.compose.material3.AssistChip
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.podly.AppGraph
import com.podly.data.db.EpisodeEntity
import com.podly.radio.RadioProfiles
import com.podly.ui.radio.RadioCard
import com.podly.ui.EpisodeActions
import com.podly.ui.appViewModel
import com.podly.ui.components.AddToPlaylistDialog
import com.podly.ui.components.DescriptionDialog
import com.podly.ui.components.EpisodeRow
import com.podly.ui.components.PodcastCard
import com.podly.ui.util.plainDescription
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class LibraryViewModel(private val graph: AppGraph) : ViewModel() {
    val actions = EpisodeActions(graph, viewModelScope)
    val podcasts = graph.podcasts.subscribedPodcasts()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())
    val episodes = graph.podcasts.libraryEpisodes()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())
    val continueListening = graph.podcasts.continueListening()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())
    val playlists = graph.playlists.playlists()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())
    val weeklyCount = graph.radio.poolCount(RadioProfiles.WEEKLY_ID)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), 0)
    val notableCount = graph.radio.poolCount(RadioProfiles.NOTABLE_ID)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), 0)

    private val _refreshing = MutableStateFlow(false)
    val refreshing: StateFlow<Boolean> = _refreshing

    fun refresh() {
        if (_refreshing.value) return
        viewModelScope.launch {
            _refreshing.value = true
            try {
                val summary = graph.podcasts.refreshAllSubscribed(force = true)
                if (summary.failures > 0) {
                    graph.messages.post(
                        "${summary.failures} of ${summary.total} feeds failed to refresh"
                    )
                }
                graph.downloader.applyPolicies()
            } finally {
                _refreshing.value = false
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LibraryScreen(
    onOpenPodcast: (String) -> Unit,
    onOpenEpisode: (String) -> Unit,
    onOpenNotable: () -> Unit = {},
    onOpenPicks: () -> Unit = {},
    onOpenWeekly: () -> Unit = {},
) {
    val viewModel = appViewModel { LibraryViewModel(it) }
    val podcasts by viewModel.podcasts.collectAsStateWithLifecycle()
    val episodes by viewModel.episodes.collectAsStateWithLifecycle()
    val continueListening by viewModel.continueListening.collectAsStateWithLifecycle()
    val playlists by viewModel.playlists.collectAsStateWithLifecycle()
    val weeklyCount by viewModel.weeklyCount.collectAsStateWithLifecycle()
    val notableCount by viewModel.notableCount.collectAsStateWithLifecycle()
    val refreshing by viewModel.refreshing.collectAsStateWithLifecycle()
    var episodeForPlaylist by remember { mutableStateOf<EpisodeEntity?>(null) }
    var episodeForDescription by remember { mutableStateOf<EpisodeEntity?>(null) }

    PullToRefreshBox(
        isRefreshing = refreshing,
        onRefresh = viewModel::refresh,
        modifier = Modifier.fillMaxSize(),
    ) {
        LazyColumn(modifier = Modifier.fillMaxSize()) {
            item(key = "radio") { RadioCard(onOpenPicks = onOpenPicks) }
            item(key = "shortcuts") {
                LibraryShortcuts(
                    buildList {
                        if (weeklyCount > 0) add(Shortcut("This week · $weeklyCount", Icons.Filled.DateRange, onOpenWeekly))
                        if (notableCount > 0) add(Shortcut("Notable · $notableCount", Icons.Filled.EmojiEvents, onOpenNotable))
                    },
                )
            }
            if (continueListening.isNotEmpty()) {
                item {
                    Text(
                        "Continue listening",
                        style = MaterialTheme.typography.titleMedium,
                        modifier = Modifier.padding(16.dp),
                    )
                }
                items(continueListening, key = { "continue_${it.id}" }) { episode ->
                    EpisodeRow(
                        episode = episode,
                        onPlay = {
                            viewModel.actions.play(continueListening, continueListening.indexOf(episode))
                        },
                        onToggleLibrary = { viewModel.actions.toggleLibrary(episode) },
                        onDownload = { viewModel.actions.download(episode) },
                        onRemoveDownload = { viewModel.actions.removeDownload(episode) },
                        onAddToPlaylist = { episodeForPlaylist = episode },
                        onTogglePlayed = { viewModel.actions.togglePlayed(episode) },
                        onShowDescription = { episodeForDescription = episode },
                        onOpenDetail = { onOpenEpisode(episode.id) },
                    )
                }
            }
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
                    onTogglePlayed = { viewModel.actions.togglePlayed(episode) },
                    onShowDescription = { episodeForDescription = episode },
                    onOpenDetail = { onOpenEpisode(episode.id) },
                )
            }
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

private data class Shortcut(val label: String, val icon: ImageVector, val onClick: () -> Unit)

/** The lists that live beside radio, one tap from the Library: the digest, Notable. */
@Composable
private fun LibraryShortcuts(shortcuts: List<Shortcut>) {
    if (shortcuts.isEmpty()) return
    LazyRow(
        contentPadding = PaddingValues(horizontal = 16.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        items(shortcuts, key = { it.label.substringBefore(" ·") }) { shortcut ->
            AssistChip(
                onClick = shortcut.onClick,
                label = { Text(shortcut.label) },
                leadingIcon = { Icon(shortcut.icon, null, modifier = Modifier.size(18.dp)) },
            )
        }
    }
}
