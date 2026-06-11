package com.podly.ui.playlists

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Sort
import androidx.compose.material.icons.filled.DragHandle
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.podly.AppGraph
import com.podly.data.db.EpisodeEntity
import com.podly.data.db.SortMode
import com.podly.ui.EpisodeActions
import com.podly.ui.appViewModel
import com.podly.ui.components.AddToPlaylistDialog
import com.podly.ui.components.EpisodeRow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import sh.calvin.reorderable.ReorderableItem
import sh.calvin.reorderable.rememberReorderableLazyListState

class PlaylistDetailViewModel(
    private val graph: AppGraph,
    private val playlistId: Long,
) : ViewModel() {
    val actions = EpisodeActions(graph, viewModelScope)
    val playlist = graph.playlists.playlist(playlistId)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)
    val episodes = graph.playlists.sortedEpisodes(playlistId)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())
    val allPlaylists = graph.playlists.playlists()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    fun setSortMode(sortMode: SortMode) = viewModelScope.launch {
        if (sortMode == SortMode.MANUAL) {
            // Freeze the currently displayed order as the manual order.
            graph.playlists.reorder(playlistId, episodes.value.map { it.id })
        }
        graph.playlists.setSortMode(playlistId, sortMode)
    }

    fun persistOrder(orderedIds: List<String>) = viewModelScope.launch {
        graph.playlists.reorder(playlistId, orderedIds)
    }

    fun remove(episodeId: String) = viewModelScope.launch {
        graph.playlists.removeEpisode(playlistId, episodeId)
    }
}

@Composable
fun PlaylistDetailScreen(playlistId: Long) {
    val viewModel = appViewModel(key = "playlist_$playlistId") {
        PlaylistDetailViewModel(it, playlistId)
    }
    val playlist by viewModel.playlist.collectAsStateWithLifecycle()
    val episodes by viewModel.episodes.collectAsStateWithLifecycle()
    val allPlaylists by viewModel.allPlaylists.collectAsStateWithLifecycle()
    var episodeForPlaylist by remember { mutableStateOf<EpisodeEntity?>(null) }
    var sortMenuOpen by remember { mutableStateOf(false) }

    // Local copy that mutates live during a drag; resyncs whenever the DB emits.
    var localList by remember(episodes) { mutableStateOf(episodes) }
    val isManual = playlist?.sortMode == SortMode.MANUAL

    val lazyListState = rememberLazyListState()
    val reorderableState = rememberReorderableLazyListState(lazyListState) { from, to ->
        localList = localList.toMutableList().apply {
            add(to.index, removeAt(from.index))
        }
    }

    Column(modifier = Modifier.fillMaxSize()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(playlist?.name ?: "", style = MaterialTheme.typography.titleLarge)
                Text(
                    "${localList.size} episodes",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Button(
                enabled = localList.isNotEmpty(),
                onClick = { viewModel.actions.play(localList, 0) },
            ) {
                Icon(Icons.Filled.PlayArrow, null)
                Text("Play")
            }
            OutlinedButton(
                onClick = { sortMenuOpen = true },
                modifier = Modifier.padding(start = 8.dp),
            ) {
                Icon(Icons.AutoMirrored.Filled.Sort, null)
                Text(
                    when (playlist?.sortMode) {
                        SortMode.CHRONO_ASC -> " Oldest"
                        SortMode.CHRONO_DESC -> " Newest"
                        else -> " Manual"
                    }
                )
                DropdownMenu(expanded = sortMenuOpen, onDismissRequest = { sortMenuOpen = false }) {
                    DropdownMenuItem(
                        text = { Text("Manual order") },
                        onClick = { sortMenuOpen = false; viewModel.setSortMode(SortMode.MANUAL) },
                    )
                    DropdownMenuItem(
                        text = { Text("Oldest first") },
                        onClick = { sortMenuOpen = false; viewModel.setSortMode(SortMode.CHRONO_ASC) },
                    )
                    DropdownMenuItem(
                        text = { Text("Newest first") },
                        onClick = { sortMenuOpen = false; viewModel.setSortMode(SortMode.CHRONO_DESC) },
                    )
                }
            }
        }

        LazyColumn(state = lazyListState, modifier = Modifier.fillMaxSize()) {
            items(localList, key = { it.id }) { episode ->
                ReorderableItem(reorderableState, key = episode.id) {
                    EpisodeRow(
                        episode = episode,
                        onPlay = { viewModel.actions.play(localList, localList.indexOf(episode)) },
                        onToggleLibrary = { viewModel.actions.toggleLibrary(episode) },
                        onDownload = { viewModel.actions.download(episode) },
                        onRemoveDownload = { viewModel.actions.removeDownload(episode) },
                        onAddToPlaylist = { episodeForPlaylist = episode },
                        onRemoveFromPlaylist = { viewModel.remove(episode.id) },
                        trailingContent = if (isManual) {
                            {
                                IconButton(
                                    onClick = {},
                                    modifier = Modifier.draggableHandle(
                                        onDragStopped = {
                                            viewModel.persistOrder(localList.map { it.id })
                                        },
                                    ),
                                ) {
                                    Icon(Icons.Filled.DragHandle, "Reorder")
                                }
                            }
                        } else null,
                    )
                }
            }
        }
    }

    episodeForPlaylist?.let { episode ->
        AddToPlaylistDialog(
            playlists = allPlaylists,
            onAdd = { targetId ->
                viewModel.actions.addToPlaylist(targetId, episode.id)
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
