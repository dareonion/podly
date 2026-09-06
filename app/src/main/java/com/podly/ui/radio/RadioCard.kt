package com.podly.ui.radio

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Radio
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.podly.ui.appViewModel

/**
 * "Turn it on and it plays something" — the entry point for radio, at the top of the
 * Library so it sits where the app already opens.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RadioCard(modifier: Modifier = Modifier) {
    val viewModel = appViewModel { RadioViewModel(it) }
    val state by viewModel.state.collectAsStateWithLifecycle()
    var showPicker by remember { mutableStateOf(false) }

    ElevatedCard(modifier = modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Filled.Radio, null)
                Text(
                    "  Radio",
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.weight(1f),
                )
            }

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                state.profiles.forEach { profile ->
                    FilterChip(
                        selected = profile.id == state.profile.id,
                        onClick = { viewModel.selectProfile(profile.id) },
                        label = { Text(profile.displayName) },
                    )
                }
            }

            if (state.isActive) {
                state.nowPlayingTitle?.let { title ->
                    Text(title, style = MaterialTheme.typography.bodyLarge, maxLines = 2, overflow = TextOverflow.Ellipsis)
                }
                state.nowPlayingShow?.let { show ->
                    Text(
                        show,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = viewModel::next) {
                        Icon(Icons.Filled.SkipNext, null)
                        Text("  Next")
                    }
                    TextButton(onClick = viewModel::stop) { Text("Stop") }
                }
            } else {
                Text(
                    state.profile.tagline,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                val reason = state.emptyReason
                if (reason != null) {
                    Text(
                        reason,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.error,
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        TextButton(onClick = { showPicker = true }) { Text("Choose shows") }
                        TextButton(onClick = viewModel::clearCooldowns) { Text("Clear cooldowns") }
                    }
                } else {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Button(onClick = viewModel::start) {
                            Icon(Icons.Filled.PlayArrow, null)
                            Text("  Start radio")
                        }
                        TextButton(onClick = { showPicker = true }) { Text("Choose shows") }
                    }
                    Text(
                        buildString {
                            append("${state.backlogCount} from your backlog")
                            if (state.poolCount > 0) append(" · ${state.poolCount} picks")
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }

    if (showPicker) {
        val shows by viewModel.subscribedShows.collectAsStateWithLifecycle()
        ModalBottomSheet(onDismissRequest = { showPicker = false }) {
            Text(
                "Shows for ${state.profile.displayName}",
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.padding(16.dp),
            )
            Text(
                if (state.profile.restrictBacklogToSelectedShows) {
                    "This profile only plays the shows you tick here."
                } else {
                    "Leave everything unticked to draw on your whole library."
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 16.dp),
            )
            LazyColumn {
                items(shows, key = { it.id }) { show ->
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Checkbox(
                            checked = show.id in state.selectedShows,
                            onCheckedChange = { checked ->
                                val next = state.selectedShows.toMutableSet()
                                if (checked) next += show.id else next -= show.id
                                viewModel.setSelectedShows(state.profile.id, next)
                            },
                        )
                        Text(
                            show.title,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.padding(start = 8.dp),
                        )
                    }
                }
            }
        }
    }
}
