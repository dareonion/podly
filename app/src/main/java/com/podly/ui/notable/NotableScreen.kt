package com.podly.ui.notable

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.podly.AppGraph
import com.podly.data.db.RadioCandidateRow
import com.podly.radio.RadioProfiles
import com.podly.ui.appViewModel
import com.podly.ui.util.publishedText
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn

class NotableViewModel(private val graph: AppGraph) : ViewModel() {

    val entries: StateFlow<List<RadioCandidateRow>> =
        graph.database.radioDao().poolEntries(RadioProfiles.NOTABLE_ID)
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    fun play(episodeId: String) = graph.player.playSingle(episodeId)
}

/**
 * Episodes worth singling out — award winners, critics' picks, and the ones a
 * lot of people actually heard — each with the citation that earned its place.
 * Acclaim and popularity are different signals, and the citation says which
 * this is. Every entry was verified against a real feed before publication, so
 * anything listed is genuinely playable.
 */
@Composable
fun NotableScreen(onOpenEpisode: (String) -> Unit, onBack: () -> Unit = {}) {
    val viewModel = appViewModel { NotableViewModel(it) }
    val entries by viewModel.entries.collectAsStateWithLifecycle()

    if (entries.isEmpty()) {
        Column(modifier = Modifier.fillMaxSize().padding(24.dp)) {
            Text("Nothing notable yet", style = MaterialTheme.typography.titleMedium)
            Text(
                "Acclaimed and widely-heard episodes appear here after the next refresh.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 8.dp),
            )
        }
        return
    }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(bottom = 24.dp),
    ) {
        item(key = "header") {
            Row(
                modifier = Modifier.fillMaxWidth().padding(end = 16.dp, top = 4.dp, bottom = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                // The bottom bar can't get you out of here: Compose restores the
                // Library tab's saved back stack, which now has this screen on top.
                IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") }
                Column {
                    Text("Notable episodes", style = MaterialTheme.typography.titleMedium)
                    Text(
                        "${entries.size} acclaimed and widely-heard episodes",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
        items(entries, key = { it.episodeId }) { entry ->
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { onOpenEpisode(entry.episodeId) }
                    .padding(horizontal = 16.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        entry.title,
                        style = MaterialTheme.typography.bodyLarge,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        entry.podcastTitle,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    publishedText(entry.pubDateMs, entry.durationMs)?.let {
                        Text(
                            it,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                        )
                    }
                    entry.reason?.let { accolade ->
                        Text(
                            accolade,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.padding(top = 2.dp),
                        )
                    }
                }
                IconButton(onClick = { viewModel.play(entry.episodeId) }) {
                    Icon(Icons.Filled.PlayArrow, "Play")
                }
            }
        }
    }
}
