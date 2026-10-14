package com.podly.ui.player

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.DragHandle
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
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
import com.podly.playback.PlayerConnection
import sh.calvin.reorderable.ReorderableItem
import sh.calvin.reorderable.rememberReorderableLazyListState

/**
 * The play queue, editable: drag a row by its handle to reorder, remove anything
 * but the episode playing, or clear everything after it. Tapping a row plays it;
 * tapping its artwork opens the episode page instead.
 *
 * Every edit goes straight to the player, so it reaches Android Auto, the cast
 * receiver and the saved queue the same way a skip does.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun QueueSheet(
    player: PlayerConnection,
    onDismiss: () -> Unit,
    onOpenEpisode: (String) -> Unit,
) {
    val state by player.state.collectAsState()
    // Mutates live during a drag and resyncs whenever the player's queue changes.
    var rows by remember(state.queue) { mutableStateOf(state.queue) }
    val currentKey = state.queue.getOrNull(state.queueIndex)?.key
    val upcoming = state.queue.size - state.queueIndex - 1

    // Indices are looked up by key from the live queue at the moment of the edit:
    // radio trims played items as it refills, which shifts every index under a
    // row that has not been recomposed yet.
    fun liveIndexOf(key: String) = player.state.value.queue.indexOfFirst { it.key == key }

    ModalBottomSheet(onDismissRequest = onDismiss) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 16.dp, end = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                "Up next",
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.weight(1f).padding(vertical = 8.dp),
            )
            if (upcoming > 0) {
                TextButton(onClick = player::clearUpNext) { Text("Clear") }
            }
        }
        val listState = rememberLazyListState(
            initialFirstVisibleItemIndex = state.queueIndex.coerceIn(0, (state.queue.size - 1).coerceAtLeast(0)),
        )
        val reorderState = rememberReorderableLazyListState(listState) { from, to ->
            rows = rows.toMutableList().apply { add(to.index, removeAt(from.index)) }
        }
        LazyColumn(state = listState) {
            items(rows, key = { it.key }) { item ->
                ReorderableItem(reorderState, key = item.key) { isDragging ->
                    val isCurrent = item.key == currentKey
                    Surface(tonalElevation = if (isDragging) 6.dp else 0.dp) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable {
                                    player.playQueueItem(liveIndexOf(item.key))
                                    onDismiss()
                                }
                                .padding(end = 4.dp, top = 4.dp, bottom = 4.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            IconButton(
                                onClick = {},
                                modifier = Modifier.draggableHandle(
                                    onDragStopped = {
                                        player.moveQueueItem(
                                            from = liveIndexOf(item.key),
                                            to = rows.indexOfFirst { it.key == item.key },
                                        )
                                    },
                                ),
                            ) {
                                Icon(Icons.Filled.DragHandle, "Reorder")
                            }
                            // Its own touch target, so the row's tap still plays.
                            AsyncImage(
                                model = item.artworkUri,
                                contentDescription = "Episode details",
                                modifier = Modifier
                                    .size(40.dp)
                                    .clip(RoundedCornerShape(6.dp))
                                    .clickable { onOpenEpisode(item.episodeId) },
                            )
                            Column(
                                modifier = Modifier
                                    .weight(1f)
                                    .padding(horizontal = 12.dp),
                            ) {
                                Text(
                                    item.title ?: "",
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = if (isCurrent) MaterialTheme.colorScheme.primary
                                    else MaterialTheme.colorScheme.onSurface,
                                    maxLines = 2,
                                    overflow = TextOverflow.Ellipsis,
                                )
                                Text(
                                    item.podcastTitle ?: "",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            }
                            if (isCurrent) {
                                // Same footprint as the remove button, so titles line up.
                                Icon(
                                    Icons.Filled.GraphicEq,
                                    "Now playing",
                                    tint = MaterialTheme.colorScheme.primary,
                                    modifier = Modifier.padding(12.dp),
                                )
                            } else {
                                IconButton(onClick = { player.removeQueueItem(liveIndexOf(item.key)) }) {
                                    Icon(Icons.Filled.Close, "Remove from queue")
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}
