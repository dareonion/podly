package com.podly.ui.library

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.QueueMusic
import androidx.compose.material.icons.filled.DateRange
import androidx.compose.material.icons.filled.EmojiEvents
import androidx.compose.material3.AssistChip
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
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
    onOpenPlaylists: () -> Unit = {},
    onOpenList: (LibraryList) -> Unit = {},
) {
    val viewModel = appViewModel { LibraryViewModel(it) }
    val podcasts by viewModel.podcasts.collectAsStateWithLifecycle()
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
                        // Playlists left the bottom bar; they are one tap from here.
                        add(Shortcut("Playlists · ${playlists.size}", Icons.AutoMirrored.Filled.QueueMusic, onOpenPlaylists))
                    },
                )
            }
            if (continueListening.isNotEmpty()) {
                item {
                    SectionHeader(
                        "Continue listening",
                        action = "See all".takeIf { continueListening.size > HOME_CONTINUE_ROWS },
                        onAction = { onOpenList(LibraryList.CONTINUE) },
                    )
                }
                // A handful here; the rest is one tap away, so the home tab is not a
                // twenty-row scroll before anything else.
                items(continueListening.take(HOME_CONTINUE_ROWS), key = { "continue_${it.id}" }) { episode ->
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
                SectionHeader("Podcasts", action = "All episodes", onAction = { onOpenList(LibraryList.ALL) })
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

/** The lists that live beside radio, one tap from the Library: the digest, Notable, playlists. */
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

private const val HOME_CONTINUE_ROWS = 5

/** A section title with an optional trailing text action ("See all"). */
@Composable
private fun SectionHeader(title: String, action: String? = null, onAction: () -> Unit = {}) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(start = 16.dp, end = 8.dp, top = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(title, style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f).padding(vertical = 8.dp))
        if (action != null) TextButton(onClick = onAction) { Text(action) }
    }
}
