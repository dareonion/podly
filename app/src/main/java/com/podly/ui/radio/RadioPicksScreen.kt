package com.podly.ui.radio

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil.compose.AsyncImage
import com.podly.AppGraph
import com.podly.data.radio.RadioRepository
import com.podly.radio.RadioCategories
import com.podly.radio.RadioProfile
import com.podly.radio.RadioProfiles
import com.podly.ui.appViewModel
import com.podly.ui.util.plainDescription
import com.podly.ui.util.publishedText
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

class RadioPicksViewModel(private val graph: AppGraph) : ViewModel() {

    private val _picks = MutableStateFlow<List<RadioRepository.Recommendation>?>(null)
    val picks: StateFlow<List<RadioRepository.Recommendation>?> = _picks

    private val _profile = MutableStateFlow(RadioProfiles.YOU)
    val profile: StateFlow<RadioProfile> = _profile

    init {
        refresh()
    }

    fun refresh() = viewModelScope.launch {
        val profile = RadioProfiles.byId(graph.radioProfiles.currentProfileId())
        _profile.value = profile
        _picks.value = graph.radio.recommendations(profile, count = 60)
    }

    fun play(episodeId: String) {
        graph.player.startRadio(_profile.value.id, episodeId)
    }
}

/**
 * What radio would play, laid out so it can be chosen from rather than only
 * accepted or skipped.
 *
 * Browsing this list deliberately does not count as radio having served any of
 * it: marking sixty episodes served because they scrolled past would poison the
 * ranking the list is showing. Picking one starts a normal radio session on it,
 * so Next still works from there.
 */
@Composable
fun RadioPicksScreen(onOpenEpisode: (String) -> Unit, onBack: () -> Unit = {}) {
    val viewModel = appViewModel { RadioPicksViewModel(it) }
    val picks by viewModel.picks.collectAsStateWithLifecycle()
    val profile by viewModel.profile.collectAsStateWithLifecycle()

    val items = picks
    if (items == null) {
        Column(
            modifier = Modifier.fillMaxSize().padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            CircularProgressIndicator()
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
                Column(modifier = Modifier.weight(1f)) {
                    Text("Picks for ${profile.displayName}", style = MaterialTheme.typography.titleMedium)
                    Text(
                        "${items.size} suggestions, best first",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    // Say what is being withheld: a filter you cannot see reads as
                    // a thin catalogue rather than a preference.
                    RadioCategories.label(profile)?.let { skipped ->
                        Text(
                            "No $skipped",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                TextButton(onClick = viewModel::refresh) {
                    Icon(Icons.Filled.Refresh, null)
                    Text(" Reshuffle")
                }
            }
        }
        if (items.isEmpty()) {
            item(key = "empty") {
                Text(
                    "Nothing to suggest right now — everything has been played or was skipped recently.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(16.dp),
                )
            }
        }
        items(items, key = { it.episode.id }) { pick ->
            val episode = pick.episode
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { onOpenEpisode(episode.id) }
                    .padding(horizontal = 16.dp, vertical = 10.dp),
                verticalAlignment = Alignment.Top,
            ) {
                AsyncImage(
                    model = episode.artworkUrl,
                    contentDescription = null,
                    modifier = Modifier.size(48.dp).clip(RoundedCornerShape(8.dp)),
                )
                Column(modifier = Modifier.weight(1f).padding(horizontal = 12.dp)) {
                    Text(
                        episode.title,
                        style = MaterialTheme.typography.bodyLarge,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        episode.podcastTitle,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    // Its own line rather than trailing the show title: when you are
                    // choosing from sixty picks, how old one is decides most of them.
                    publishedText(episode.pubDateMs, episode.durationMs)?.let {
                        Text(
                            it,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                        )
                    }
                    Text(
                        pick.reason ?: if (pick.discovery) "New to you" else "From your backlog",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.primary,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                    // Choosing between sixty episodes on title alone is guesswork;
                    // the show notes are what the titles leave out.
                    plainDescription(episode.description)?.let { blurb ->
                        Text(
                            blurb,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 4,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.padding(top = 4.dp),
                        )
                    }
                }
                IconButton(onClick = { viewModel.play(episode.id) }) {
                    Icon(Icons.Filled.PlayArrow, "Play")
                }
            }
        }
    }
}
