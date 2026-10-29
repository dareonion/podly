package com.podly.ui.playlists

import com.podly.data.db.PlaylistShow
import coil.compose.AsyncImage
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.draw.clip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.clickable
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
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.DragHandle
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
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
import com.podly.data.db.needsDownload
import com.podly.ui.EpisodeActions
import com.podly.ui.appViewModel
import com.podly.ui.components.AddToPlaylistDialog
import com.podly.ui.components.DescriptionDialog
import com.podly.ui.components.EpisodeRow
import com.podly.ui.util.plainDescription
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
    val notes = graph.playlists.notes(playlistId)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyMap())
    val shows = graph.playlists.shows(playlistId)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    fun subscribe(podcastId: String) = viewModelScope.launch {
        graph.podcasts.setSubscribed(podcastId, true)
    }

    // Only sets the mode: chrono sorts are applied at read time, so the stored manual
    // order (insertion/import order until the user drags) survives round trips through
    // "Oldest/Newest first" and switching back restores it. Only dragging rewrites it.
    fun setSortMode(sortMode: SortMode) = viewModelScope.launch {
        graph.playlists.setSortMode(playlistId, sortMode)
    }

    fun persistOrder(orderedIds: List<String>) = viewModelScope.launch {
        graph.playlists.reorder(playlistId, orderedIds)
    }

    /** Queues a download for every episode that doesn't already have one. */
    fun downloadAll() = viewModelScope.launch {
        val pending = episodes.value.filter { it.downloadStatus.needsDownload }
        pending.forEach { actions.download(it) }
        graph.messages.post("Queued ${pending.size} downloads")
    }

    fun remove(episodeId: String) = viewModelScope.launch {
        graph.playlists.removeEpisode(playlistId, episodeId)
    }
}

@Composable
fun PlaylistDetailScreen(playlistId: Long, onOpenEpisode: (String) -> Unit, onOpenPodcast: (String) -> Unit = {}) {
    val viewModel = appViewModel(key = "playlist_$playlistId") {
        PlaylistDetailViewModel(it, playlistId)
    }
    val playlist by viewModel.playlist.collectAsStateWithLifecycle()
    val episodes by viewModel.episodes.collectAsStateWithLifecycle()
    val allPlaylists by viewModel.allPlaylists.collectAsStateWithLifecycle()
    val notes by viewModel.notes.collectAsStateWithLifecycle()
    val shows by viewModel.shows.collectAsStateWithLifecycle()
    var showsOpen by rememberSaveable { mutableStateOf(false) }
    var episodeForPlaylist by remember { mutableStateOf<EpisodeEntity?>(null) }
    var episodeForDescription by remember { mutableStateOf<EpisodeEntity?>(null) }
    var sortMenuOpen by remember { mutableStateOf(false) }
    var confirmDownloadAll by remember { mutableStateOf(false) }

    // Local copy that mutates live during a drag; resyncs whenever the DB emits.
    var localList by remember(episodes) { mutableStateOf(episodes) }
    val isManual = playlist?.sortMode == SortMode.MANUAL

    val lazyListState = rememberLazyListState()
    // By key, not list position: the shows section above the episodes is a list item too.
    val reorderableState = rememberReorderableLazyListState(lazyListState) { from, to ->
        val fromIndex = localList.indexOfFirst { it.id == from.key }
        val toIndex = localList.indexOfFirst { it.id == to.key }
        if (fromIndex >= 0 && toIndex >= 0) {
            localList = localList.toMutableList().apply { add(toIndex, removeAt(fromIndex)) }
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
                    "${localList.size} episodes" + if (shows.isNotEmpty()) " · ${shows.size} shows" else "",
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
            val pendingDownloads = localList.count { it.downloadStatus.needsDownload }
            IconButton(
                enabled = pendingDownloads > 0,
                onClick = { confirmDownloadAll = true },
            ) {
                Icon(Icons.Filled.Download, contentDescription = "Download all")
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
            if (shows.isNotEmpty()) {
                item(key = "shows") {
                    PlaylistShowsSection(
                        shows = shows,
                        open = showsOpen,
                        onToggle = { showsOpen = !showsOpen },
                        onOpen = onOpenPodcast,
                        onSubscribe = viewModel::subscribe,
                    )
                }
            }
            items(localList, key = { it.id }) { episode ->
                ReorderableItem(reorderableState, key = episode.id) {
                    EpisodeRow(
                        episode = episode,
                        onPlay = { viewModel.actions.play(localList, localList.indexOf(episode)) },
                        onToggleLibrary = { viewModel.actions.toggleLibrary(episode) },
                        onDownload = { viewModel.actions.download(episode) },
                        onRemoveDownload = { viewModel.actions.removeDownload(episode) },
                        onAddToPlaylist = { episodeForPlaylist = episode },
                        onTogglePlayed = { viewModel.actions.togglePlayed(episode) },
                        onRemoveFromPlaylist = { viewModel.remove(episode.id) },
                        onShowDescription = { episodeForDescription = episode },
                        onOpenDetail = { onOpenEpisode(episode.id) },
                        note = notes[episode.id],
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

    if (confirmDownloadAll) {
        val pending = localList.count { it.downloadStatus.needsDownload }
        AlertDialog(
            onDismissRequest = { confirmDownloadAll = false },
            title = { Text("Download all") },
            text = { Text("Queue downloads for $pending episodes? The Wi-Fi-only setting still applies.") },
            confirmButton = {
                TextButton(onClick = {
                    confirmDownloadAll = false
                    viewModel.downloadAll()
                }) { Text("Download") }
            },
            dismissButton = {
                TextButton(onClick = { confirmDownloadAll = false }) { Text("Cancel") }
            },
        )
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

/**
 * The shows a chat recommends as a whole, folded away above its episodes: a header
 * with the count, and when open a row per show to open it or subscribe from here.
 */
@Composable
private fun PlaylistShowsSection(
    shows: List<PlaylistShow>,
    open: Boolean,
    onToggle: () -> Unit,
    onOpen: (String) -> Unit,
    onSubscribe: (String) -> Unit,
) {
    Column {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable(onClick = onToggle)
                .padding(horizontal = 16.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                "Shows from this chat · ${shows.size}",
                style = MaterialTheme.typography.titleSmall,
                modifier = Modifier.weight(1f),
            )
            Icon(
                if (open) Icons.Filled.ExpandLess else Icons.Filled.ExpandMore,
                if (open) "Hide shows" else "Show shows",
            )
        }
        if (open) {
            shows.forEach { show ->
                val podcast = show.podcast
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { onOpen(podcast.id) }
                        .padding(horizontal = 16.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    AsyncImage(
                        model = podcast.artworkUrl,
                        contentDescription = null,
                        modifier = Modifier.size(48.dp).clip(RoundedCornerShape(8.dp)),
                    )
                    Column(modifier = Modifier.weight(1f).padding(horizontal = 12.dp)) {
                        Text(podcast.title, style = MaterialTheme.typography.bodyLarge, maxLines = 2, overflow = TextOverflow.Ellipsis)
                        show.note?.let {
                            Text(
                                it,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 3,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }
                    if (podcast.subscribed) {
                        Text(
                            "Subscribed",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    } else {
                        OutlinedButton(onClick = { onSubscribe(podcast.id) }) { Text("Subscribe") }
                    }
                }
            }
            HorizontalDivider(modifier = Modifier.padding(top = 4.dp))
        }
    }
}

