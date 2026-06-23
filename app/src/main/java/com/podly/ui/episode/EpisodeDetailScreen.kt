package com.podly.ui.episode

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.PlaylistAdd
import androidx.compose.material.icons.filled.Bookmark
import androidx.compose.material.icons.filled.BookmarkBorder
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.DownloadDone
import androidx.compose.material.icons.filled.Downloading
import androidx.compose.material.icons.filled.EditNote
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Replay
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import coil.compose.AsyncImage
import com.podly.AppGraph
import com.podly.appGraph
import com.podly.data.db.DownloadStatus
import com.podly.data.db.EpisodeEntity
import com.podly.ui.EpisodeActions
import com.podly.ui.appViewModel
import com.podly.ui.components.AddToPlaylistDialog
import com.podly.ui.components.EpisodeNoteDialog
import com.podly.ui.util.formatDate
import com.podly.ui.util.formatDuration
import com.podly.ui.util.formatPosition
import com.podly.ui.util.plainDescription
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class EpisodeDetailViewModel(
    private val graph: AppGraph,
    private val episodeId: String,
) : ViewModel() {
    val actions = EpisodeActions(graph, viewModelScope)
    val episode = graph.podcasts.episode(episodeId)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)
    val playlists = graph.playlists.playlists()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    /** Queue just this episode; the service resumes from its saved position. */
    fun play(episode: EpisodeEntity) = actions.play(listOf(episode), 0)

    fun updateNoteAndRating(note: String?, rating: Int?) = viewModelScope.launch {
        graph.podcasts.updateEpisodeNoteAndRating(episodeId, note, rating)
    }
}

@Composable
fun EpisodeDetailScreen(
    episodeId: String,
    onOpenPodcast: (String) -> Unit,
) {
    val viewModel = appViewModel(key = "episode_$episodeId") {
        EpisodeDetailViewModel(it, episodeId)
    }
    val episode by viewModel.episode.collectAsStateWithLifecycle()
    val playlists by viewModel.playlists.collectAsStateWithLifecycle()
    val player = LocalContext.current.appGraph.player
    val playerState by player.state.collectAsState()

    var showPlaylistDialog by remember { mutableStateOf(false) }
    var showNoteDialog by remember { mutableStateOf(false) }

    val ep = episode
    if (ep == null) {
        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            CircularProgressIndicator()
        }
        return
    }

    val isCurrent = playerState.episodeId == ep.id
    val isPlaying = isCurrent && playerState.isPlaying
    val hasProgress = ep.playbackPositionMs > 0 && !ep.completed

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(bottom = 24.dp),
    ) {
        item {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                AsyncImage(
                    model = ep.artworkUrl,
                    contentDescription = null,
                    modifier = Modifier
                        .fillMaxWidth(0.6f)
                        .aspectRatio(1f)
                        .clip(RoundedCornerShape(16.dp)),
                )
                Spacer(Modifier.height(16.dp))
                Text(
                    ep.title,
                    style = MaterialTheme.typography.titleLarge,
                    textAlign = TextAlign.Center,
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    ep.podcastTitle,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.clickable { onOpenPodcast(ep.podcastId) },
                )
                val meta = listOfNotNull(
                    formatDate(ep.pubDateMs),
                    formatDuration(ep.durationMs),
                ).joinToString(" · ")
                if (meta.isNotEmpty()) {
                    Spacer(Modifier.height(4.dp))
                    Text(
                        meta,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }

        val duration = ep.durationMs ?: 0
        if (hasProgress && duration > 0) {
            item {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp),
                ) {
                    LinearProgressIndicator(
                        progress = { (ep.playbackPositionMs.toFloat() / duration).coerceIn(0f, 1f) },
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Spacer(Modifier.height(4.dp))
                    val remaining = (duration - ep.playbackPositionMs).coerceAtLeast(0)
                    Text(
                        listOfNotNull(
                            formatPosition(ep.playbackPositionMs),
                            formatDuration(remaining)?.let { "$it left" },
                        ).joinToString(" · "),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        } else if (ep.completed) {
            item {
                Row(
                    modifier = Modifier.padding(horizontal = 16.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(
                        Icons.Filled.CheckCircle,
                        null,
                        modifier = Modifier.size(18.dp),
                        tint = MaterialTheme.colorScheme.primary,
                    )
                    Text(
                        "  Played",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }

        item {
            Button(
                onClick = { if (isCurrent) player.togglePlayPause() else viewModel.play(ep) },
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 12.dp),
            ) {
                Icon(if (isPlaying) Icons.Filled.Pause else Icons.Filled.PlayArrow, null)
                Spacer(Modifier.width(8.dp))
                Text(
                    when {
                        isPlaying -> "Pause"
                        isCurrent || hasProgress -> "Resume"
                        else -> "Play"
                    }
                )
            }
        }

        item {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 8.dp),
                horizontalArrangement = Arrangement.SpaceEvenly,
            ) {
                ActionButton(
                    icon = if (ep.inLibrary) Icons.Filled.Bookmark else Icons.Filled.BookmarkBorder,
                    label = if (ep.inLibrary) "Saved" else "Save",
                    onClick = { viewModel.actions.toggleLibrary(ep) },
                )
                when (ep.downloadStatus) {
                    DownloadStatus.DONE -> ActionButton(
                        icon = Icons.Filled.DownloadDone,
                        label = "Downloaded",
                        onClick = { viewModel.actions.removeDownload(ep) },
                    )
                    DownloadStatus.DOWNLOADING, DownloadStatus.QUEUED -> ActionButton(
                        icon = Icons.Filled.Downloading,
                        label = "Cancel",
                        onClick = { viewModel.actions.removeDownload(ep) },
                    )
                    else -> ActionButton(
                        icon = Icons.Filled.Download,
                        label = "Download",
                        onClick = { viewModel.actions.download(ep) },
                    )
                }
                ActionButton(
                    icon = Icons.AutoMirrored.Filled.PlaylistAdd,
                    label = "Playlist",
                    onClick = { showPlaylistDialog = true },
                )
                ActionButton(
                    icon = if (ep.completed) Icons.Filled.Replay else Icons.Filled.Check,
                    label = if (ep.completed) "Unplay" else "Played",
                    onClick = { viewModel.actions.togglePlayed(ep) },
                )
                ActionButton(
                    icon = Icons.Filled.EditNote,
                    label = "Notes",
                    onClick = { showNoteDialog = true },
                )
            }
        }

        if (ep.userRating != null || !ep.userNote.isNullOrBlank()) {
            item {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 8.dp),
                ) {
                    ep.userRating?.let { rating ->
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            repeat(5) { i ->
                                Icon(
                                    Icons.Filled.Star,
                                    null,
                                    modifier = Modifier.size(18.dp),
                                    tint = if (i < rating) MaterialTheme.colorScheme.primary
                                    else MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                    }
                    ep.userNote?.takeIf { it.isNotBlank() }?.let { note ->
                        Spacer(Modifier.height(4.dp))
                        Text(note, style = MaterialTheme.typography.bodyMedium)
                    }
                }
            }
        }

        plainDescription(ep.description)?.let { description ->
            item {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp),
                ) {
                    Text("Description", style = MaterialTheme.typography.titleMedium)
                    Spacer(Modifier.height(8.dp))
                    Text(
                        description,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }

    if (showPlaylistDialog) {
        AddToPlaylistDialog(
            playlists = playlists,
            onAdd = { playlistId ->
                viewModel.actions.addToPlaylist(playlistId, ep.id)
                showPlaylistDialog = false
            },
            onCreateAndAdd = { name ->
                viewModel.actions.createPlaylistAndAdd(name, ep.id)
                showPlaylistDialog = false
            },
            onDismiss = { showPlaylistDialog = false },
        )
    }

    if (showNoteDialog) {
        EpisodeNoteDialog(
            title = ep.title,
            initialNote = ep.userNote,
            initialRating = ep.userRating,
            onSave = { note, rating ->
                viewModel.updateNoteAndRating(note, rating)
                showNoteDialog = false
            },
            onDismiss = { showNoteDialog = false },
        )
    }
}

@Composable
private fun ActionButton(icon: ImageVector, label: String, onClick: () -> Unit) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        IconButton(onClick = onClick) { Icon(icon, label) }
        Text(label, style = MaterialTheme.typography.labelSmall)
    }
}
