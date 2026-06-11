package com.podly.ui.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.PlaylistAdd
import androidx.compose.material.icons.filled.Bookmark
import androidx.compose.material.icons.filled.BookmarkBorder
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.DownloadDone
import androidx.compose.material.icons.filled.Downloading
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.foundation.layout.height
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import com.podly.data.db.DownloadStatus
import com.podly.data.db.EpisodeEntity
import com.podly.ui.util.formatDate
import com.podly.ui.util.formatDuration
import com.podly.ui.util.plainDescription

@Composable
fun EpisodeRow(
    episode: EpisodeEntity,
    onPlay: () -> Unit,
    onToggleLibrary: () -> Unit,
    onDownload: () -> Unit,
    onRemoveDownload: () -> Unit,
    onAddToPlaylist: () -> Unit,
    onTogglePlayed: (() -> Unit)? = null,
    onRemoveFromPlaylist: (() -> Unit)? = null,
    onShowDescription: (() -> Unit)? = null,
    trailingContent: (@Composable () -> Unit)? = null,
) {
    var menuOpen by remember { mutableStateOf(false) }
    val hasDescription = plainDescription(episode.description) != null

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onPlay)
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        AsyncImage(
            model = episode.artworkUrl,
            contentDescription = null,
            modifier = Modifier
                .size(56.dp)
                .clip(RoundedCornerShape(8.dp)),
        )
        Column(
            modifier = Modifier
                .weight(1f)
                .padding(horizontal = 12.dp),
        ) {
            Text(
                episode.title,
                style = MaterialTheme.typography.bodyLarge,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            val subtitle = listOfNotNull(
                episode.podcastTitle.ifBlank { null },
                formatDate(episode.pubDateMs),
                formatDuration(episode.durationMs),
            ).joinToString(" · ")
            Text(
                subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            val duration = episode.durationMs ?: 0
            if (!episode.completed && episode.playbackPositionMs > 0 && duration > 0) {
                LinearProgressIndicator(
                    progress = {
                        (episode.playbackPositionMs.toFloat() / duration).coerceIn(0f, 1f)
                    },
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 6.dp)
                        .height(3.dp),
                )
            }
        }
        if (episode.completed) {
            Icon(
                Icons.Filled.CheckCircle, "Played",
                modifier = Modifier.size(18.dp),
                tint = MaterialTheme.colorScheme.primary,
            )
        }
        when (episode.downloadStatus) {
            DownloadStatus.DONE -> Icon(
                Icons.Filled.DownloadDone, "Downloaded",
                modifier = Modifier.size(18.dp),
                tint = MaterialTheme.colorScheme.primary,
            )
            DownloadStatus.DOWNLOADING, DownloadStatus.QUEUED -> Icon(
                Icons.Filled.Downloading, "Downloading",
                modifier = Modifier.size(18.dp),
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            else -> {}
        }
        IconButton(onClick = onPlay) {
            Icon(Icons.Filled.PlayArrow, "Play")
        }
        IconButton(onClick = { menuOpen = true }) {
            Icon(Icons.Filled.MoreVert, "More")
            DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                DropdownMenuItem(
                    text = { Text(if (episode.inLibrary) "Remove from library" else "Save to library") },
                    leadingIcon = {
                        Icon(
                            if (episode.inLibrary) Icons.Filled.Bookmark else Icons.Filled.BookmarkBorder,
                            null,
                        )
                    },
                    onClick = { menuOpen = false; onToggleLibrary() },
                )
                if (episode.downloadStatus == DownloadStatus.NONE || episode.downloadStatus == DownloadStatus.FAILED) {
                    DropdownMenuItem(
                        text = { Text("Download") },
                        leadingIcon = { Icon(Icons.Filled.Download, null) },
                        onClick = { menuOpen = false; onDownload() },
                    )
                } else {
                    DropdownMenuItem(
                        text = { Text("Remove download") },
                        leadingIcon = { Icon(Icons.Filled.Delete, null) },
                        onClick = { menuOpen = false; onRemoveDownload() },
                    )
                }
                DropdownMenuItem(
                    text = { Text("Add to playlist") },
                    leadingIcon = { Icon(Icons.AutoMirrored.Filled.PlaylistAdd, null) },
                    onClick = { menuOpen = false; onAddToPlaylist() },
                )
                if (onTogglePlayed != null) {
                    DropdownMenuItem(
                        text = { Text(if (episode.completed) "Mark as unplayed" else "Mark as played") },
                        leadingIcon = { Icon(Icons.Filled.Check, null) },
                        onClick = { menuOpen = false; onTogglePlayed() },
                    )
                }
                if (hasDescription && onShowDescription != null) {
                    DropdownMenuItem(
                        text = { Text("Description") },
                        leadingIcon = { Icon(Icons.Filled.Description, null) },
                        onClick = { menuOpen = false; onShowDescription() },
                    )
                }
                if (onRemoveFromPlaylist != null) {
                    DropdownMenuItem(
                        text = { Text("Remove from this playlist") },
                        leadingIcon = { Icon(Icons.Filled.Delete, null) },
                        onClick = { menuOpen = false; onRemoveFromPlaylist() },
                    )
                }
            }
        }
        trailingContent?.invoke()
    }
}
