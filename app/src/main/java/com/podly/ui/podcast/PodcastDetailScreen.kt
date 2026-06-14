package com.podly.ui.podcast

import android.util.Log
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import coil.compose.AsyncImage
import com.podly.AppGraph
import com.podly.data.CachedEpisodePicks
import com.podly.data.db.EpisodeEntity
import com.podly.network.ai.AiEpisodePick
import com.podly.ui.EpisodeActions
import com.podly.ui.appViewModel
import com.podly.ui.components.AddToPlaylistDialog
import com.podly.ui.components.DescriptionDialog
import com.podly.ui.components.EpisodeRow
import com.podly.ui.util.plainDescription
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/** An AI starter pick, matched against the feed's episode list when possible. */
data class ResolvedStarter(
    val pick: AiEpisodePick,
    val episode: EpisodeEntity?,
)

data class StartersState(
    val picks: List<ResolvedStarter>? = null,
    val loading: Boolean = false,
    val error: String? = null,
)

class PodcastDetailViewModel(
    private val graph: AppGraph,
    private val podcastId: String,
) : ViewModel() {
    val actions = EpisodeActions(graph, viewModelScope)
    val podcast = graph.podcasts.podcast(podcastId)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)
    val episodes = graph.podcasts.episodesForPodcast(podcastId)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())
    val playlists = graph.playlists.playlists()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    private val startersRaw = MutableStateFlow<List<AiEpisodePick>?>(null)
    private val startersLoading = MutableStateFlow(false)
    private val startersError = MutableStateFlow<String?>(null)
    private var startersFetchedAtMs = 0L

    // Re-match picks whenever the episode list changes (it loads after the cache).
    val starters: StateFlow<StartersState> =
        combine(startersRaw, episodes, startersLoading, startersError) { raw, eps, loading, error ->
            StartersState(
                picks = raw?.map { pick -> ResolvedStarter(pick, matchEpisode(pick.episodeTitle, eps)) },
                loading = loading,
                error = error,
            )
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), StartersState())

    init {
        viewModelScope.launch {
            graph.aiPicksCache.loadStarters(podcastId)?.let { cached ->
                startersFetchedAtMs = cached.fetchedAtMs
                if (startersRaw.value == null) startersRaw.value = cached.picks
            }
        }
    }

    fun toggleSubscribed() = viewModelScope.launch {
        podcast.value?.let { graph.podcasts.setSubscribed(it.id, !it.subscribed) }
    }

    fun loadStarters(force: Boolean = false) {
        val cacheFresh = System.currentTimeMillis() - startersFetchedAtMs < STARTERS_MAX_AGE_MS
        if (!force && cacheFresh && startersRaw.value != null) return
        val current = podcast.value ?: return
        viewModelScope.launch {
            startersLoading.value = true
            startersError.value = null
            runCatching {
                graph.aiRecommender.whereToStart(
                    podcastTitle = current.title,
                    author = current.author,
                    episodeTitles = episodes.value.map { it.title },
                )
            }
                .onSuccess { picks ->
                    startersFetchedAtMs = System.currentTimeMillis()
                    graph.aiPicksCache.saveStarters(
                        podcastId,
                        CachedEpisodePicks(picks, startersFetchedAtMs),
                    )
                    startersRaw.value = picks
                    startersLoading.value = false
                }
                .onFailure { e ->
                    Log.e(TAG, "Where-to-start failed", e)
                    startersError.value = listOfNotNull(e.message, e.cause?.message)
                        .distinct().joinToString(": ").ifEmpty { e.toString() }
                    startersLoading.value = false
                }
        }
    }

    private fun matchEpisode(title: String, episodes: List<EpisodeEntity>): EpisodeEntity? {
        val wanted = title.trim().lowercase()
        return episodes.firstOrNull { it.title.trim().lowercase() == wanted }
            ?: episodes.firstOrNull {
                val have = it.title.trim().lowercase()
                have.contains(wanted) || wanted.contains(have)
            }
    }

    private companion object {
        const val TAG = "PodcastDetail"
        const val STARTERS_MAX_AGE_MS = 30L * 24 * 60 * 60 * 1000
    }
}

@Composable
fun PodcastDetailScreen(podcastId: String) {
    val viewModel = appViewModel(key = "podcast_$podcastId") {
        PodcastDetailViewModel(it, podcastId)
    }
    val podcast by viewModel.podcast.collectAsStateWithLifecycle()
    val episodes by viewModel.episodes.collectAsStateWithLifecycle()
    val playlists by viewModel.playlists.collectAsStateWithLifecycle()
    val startersState by viewModel.starters.collectAsStateWithLifecycle()
    var episodeForPlaylist by remember { mutableStateOf<EpisodeEntity?>(null) }
    var episodeForDescription by remember { mutableStateOf<EpisodeEntity?>(null) }
    var showFullPodcastDescription by remember(podcast?.id) { mutableStateOf(false) }

    LazyColumn(modifier = Modifier.fillMaxSize()) {
        item {
            Row(modifier = Modifier.padding(16.dp)) {
                AsyncImage(
                    model = podcast?.artworkUrl,
                    contentDescription = null,
                    modifier = Modifier
                        .size(96.dp)
                        .clip(RoundedCornerShape(12.dp)),
                )
                Column(modifier = Modifier.padding(start = 16.dp)) {
                    Text(
                        podcast?.title ?: "",
                        style = MaterialTheme.typography.titleLarge,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        podcast?.author ?: "",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    if (podcast?.subscribed == true) {
                        OutlinedButton(onClick = viewModel::toggleSubscribed) { Text("Unsubscribe") }
                    } else {
                        Button(onClick = viewModel::toggleSubscribed) { Text("Subscribe") }
                    }
                }
            }
        }
        plainDescription(podcast?.description)?.let { description ->
            item {
                Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
                    Text(
                        description,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = if (showFullPodcastDescription) Int.MAX_VALUE else 4,
                        overflow = if (showFullPodcastDescription) TextOverflow.Clip else TextOverflow.Ellipsis,
                    )
                    if (description.length > 240 || description.count { it == '\n' } >= 4) {
                        TextButton(
                            onClick = { showFullPodcastDescription = !showFullPodcastDescription },
                            modifier = Modifier.padding(top = 4.dp),
                        ) {
                            Text(if (showFullPodcastDescription) "Show less" else "Show full description")
                        }
                    }
                }
            }
        }
        if (episodes.isNotEmpty()) {
            item {
                Button(
                    onClick = { viewModel.loadStarters() },
                    enabled = !startersState.loading,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                ) {
                    Icon(Icons.Filled.AutoAwesome, null)
                    Text("  Where to start")
                }
            }
        }
        if (startersState.loading) {
            item {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp),
                    horizontalArrangement = Arrangement.Center,
                ) { CircularProgressIndicator() }
            }
        }
        startersState.error?.let { error ->
            item {
                Text(
                    error,
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.padding(horizontal = 16.dp),
                )
            }
        }
        startersState.picks?.let { picks ->
            item {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 8.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        "Where to start",
                        style = MaterialTheme.typography.titleMedium,
                        modifier = Modifier.weight(1f),
                    )
                    Text(
                        "Refresh",
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.clickable { viewModel.loadStarters(force = true) },
                    )
                }
            }
            itemsIndexed(picks, key = { index, _ -> "starter_$index" }) { _, resolved ->
                Column {
                    Text(
                        resolved.pick.reason,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.padding(horizontal = 16.dp),
                    )
                    val starter = resolved.episode
                    if (starter != null) {
                        EpisodeRow(
                            episode = starter,
                            onPlay = { viewModel.actions.play(episodes, episodes.indexOf(starter)) },
                            onToggleLibrary = { viewModel.actions.toggleLibrary(starter) },
                            onDownload = { viewModel.actions.download(starter) },
                            onRemoveDownload = { viewModel.actions.removeDownload(starter) },
                            onAddToPlaylist = { episodeForPlaylist = starter },
                            onTogglePlayed = { viewModel.actions.togglePlayed(starter) },
                            onShowDescription = { episodeForDescription = starter },
                        )
                    } else {
                        Text(
                            "${resolved.pick.episodeTitle} — not found in the feed",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                        )
                    }
                }
            }
        }
        item {
            Text(
                "${episodes.size} episodes",
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.padding(16.dp),
            )
        }
        items(episodes, key = { it.id }) { episode ->
            EpisodeRow(
                episode = episode,
                onPlay = { viewModel.actions.play(episodes, episodes.indexOf(episode)) },
                onToggleLibrary = { viewModel.actions.toggleLibrary(episode) },
                onDownload = { viewModel.actions.download(episode) },
                onRemoveDownload = { viewModel.actions.removeDownload(episode) },
                onAddToPlaylist = { episodeForPlaylist = episode },
                onTogglePlayed = { viewModel.actions.togglePlayed(episode) },
                onShowDescription = { episodeForDescription = episode },
            )
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
