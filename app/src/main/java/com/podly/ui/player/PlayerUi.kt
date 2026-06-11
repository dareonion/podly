package com.podly.ui.player

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.FastForward
import androidx.compose.material.icons.filled.FastRewind
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Speed
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.collectAsState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import com.podly.appGraph
import com.podly.ui.util.formatPosition
import kotlinx.coroutines.delay

private val SPEEDS = listOf(0.8f, 1.0f, 1.2f, 1.5f, 1.8f, 2.0f)

@Composable
fun MiniPlayer(onOpenPlayer: () -> Unit) {
    val player = LocalContext.current.appGraph.player
    val state by player.state.collectAsState()
    if (state.episodeId == null) return

    Surface(tonalElevation = 4.dp) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable(onClick = onOpenPlayer)
                .padding(horizontal = 12.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            AsyncImage(
                model = state.artworkUri,
                contentDescription = null,
                modifier = Modifier
                    .size(40.dp)
                    .clip(RoundedCornerShape(6.dp)),
            )
            Column(
                modifier = Modifier
                    .weight(1f)
                    .padding(horizontal = 10.dp),
            ) {
                Text(
                    state.title ?: "",
                    style = MaterialTheme.typography.bodyMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    state.podcastTitle ?: "",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            IconButton(onClick = player::seekBack) {
                Icon(Icons.Filled.FastRewind, "Seek back")
            }
            IconButton(onClick = player::togglePlayPause) {
                if (state.isBuffering) {
                    CircularProgressIndicator(modifier = Modifier.size(24.dp))
                } else {
                    Icon(
                        if (state.isPlaying) Icons.Filled.Pause else Icons.Filled.PlayArrow,
                        "Play/Pause",
                    )
                }
            }
            IconButton(onClick = player::seekForward) {
                Icon(Icons.Filled.FastForward, "Seek forward")
            }
        }
    }
}

@Composable
fun PlayerScreen() {
    val player = LocalContext.current.appGraph.player
    val state by player.state.collectAsState()
    var position by remember { mutableLongStateOf(0L) }
    var scrubbing by remember { mutableStateOf(false) }
    var speedMenuOpen by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        while (true) {
            if (!scrubbing) position = player.positionMs()
            delay(500)
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        AsyncImage(
            model = state.artworkUri,
            contentDescription = null,
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(1f)
                .clip(RoundedCornerShape(20.dp)),
        )
        Spacer(Modifier.height(24.dp))
        Text(
            state.title ?: "Nothing playing",
            style = MaterialTheme.typography.titleLarge,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
        Text(
            state.podcastTitle ?: "",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(16.dp))

        val duration = state.durationMs.coerceAtLeast(1)
        Slider(
            value = position.coerceIn(0, duration).toFloat(),
            valueRange = 0f..duration.toFloat(),
            onValueChange = { scrubbing = true; position = it.toLong() },
            onValueChangeFinished = {
                player.seekTo(position)
                scrubbing = false
            },
        )
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text(formatPosition(position), style = MaterialTheme.typography.bodySmall)
            Text(formatPosition(state.durationMs), style = MaterialTheme.typography.bodySmall)
        }
        Spacer(Modifier.height(8.dp))

        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = player::seekBack, modifier = Modifier.size(64.dp)) {
                Icon(Icons.Filled.FastRewind, "Seek back", modifier = Modifier.size(36.dp))
            }
            IconButton(onClick = player::togglePlayPause, modifier = Modifier.size(80.dp)) {
                if (state.isBuffering) {
                    CircularProgressIndicator(modifier = Modifier.size(40.dp))
                } else {
                    Icon(
                        if (state.isPlaying) Icons.Filled.Pause else Icons.Filled.PlayArrow,
                        "Play/Pause",
                        modifier = Modifier.size(48.dp),
                    )
                }
            }
            IconButton(onClick = player::seekForward, modifier = Modifier.size(64.dp)) {
                Icon(Icons.Filled.FastForward, "Seek forward", modifier = Modifier.size(36.dp))
            }
        }
        Spacer(Modifier.height(8.dp))
        IconButton(onClick = { speedMenuOpen = true }) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Filled.Speed, "Speed")
                Text(" ${state.speed}x", style = MaterialTheme.typography.bodyMedium)
            }
            DropdownMenu(expanded = speedMenuOpen, onDismissRequest = { speedMenuOpen = false }) {
                SPEEDS.forEach { speed ->
                    DropdownMenuItem(
                        text = { Text("${speed}x") },
                        onClick = { speedMenuOpen = false; player.setSpeed(speed) },
                    )
                }
            }
        }
    }
}
