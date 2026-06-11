package com.podly.ui.discover

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
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
import com.podly.data.db.PodcastEntity
import com.podly.data.db.stableId
import com.podly.network.TrendingPeriod
import com.podly.network.TrendingPodcast
import com.podly.network.ai.AiRecommendation
import com.podly.ui.appViewModel
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** An AI pick, matched against the iTunes directory when a match was found. */
data class ResolvedRecommendation(
    val rec: AiRecommendation,
    val podcast: PodcastEntity?,
)

data class DiscoverUiState(
    val query: String = "",
    val searching: Boolean = false,
    val searchResults: List<PodcastEntity>? = null,
    val trendingPeriod: TrendingPeriod = TrendingPeriod.NOW,
    val trending: List<TrendingPodcast> = emptyList(),
    val trendingLoading: Boolean = false,
    val hasPodcastIndexCreds: Boolean = false,
    val recommendations: List<ResolvedRecommendation>? = null,
    val recsLoading: Boolean = false,
    val error: String? = null,
    val opening: Boolean = false,
)

class DiscoverViewModel(private val graph: AppGraph) : ViewModel() {
    private val _state = MutableStateFlow(DiscoverUiState())
    val state: StateFlow<DiscoverUiState> = _state

    init {
        viewModelScope.launch {
            graph.settings.settings.collect { settings ->
                _state.update {
                    it.copy(
                        hasPodcastIndexCreds =
                            settings.podcastIndexKey.isNotBlank() && settings.podcastIndexSecret.isNotBlank()
                    )
                }
            }
        }
        loadTrending(TrendingPeriod.NOW)
    }

    fun setQuery(query: String) = _state.update { it.copy(query = query) }

    fun search() {
        val term = _state.value.query.trim()
        if (term.isEmpty()) {
            _state.update { it.copy(searchResults = null) }
            return
        }
        viewModelScope.launch {
            _state.update { it.copy(searching = true, error = null) }
            runCatching { graph.podcasts.search(term) }
                .onSuccess { results -> _state.update { it.copy(searchResults = results, searching = false) } }
                .onFailure { e -> _state.update { it.copy(error = e.message, searching = false) } }
        }
    }

    fun clearSearch() = _state.update { it.copy(query = "", searchResults = null) }

    fun loadTrending(period: TrendingPeriod) {
        viewModelScope.launch {
            _state.update { it.copy(trendingPeriod = period, trendingLoading = true, error = null) }
            runCatching {
                when (period) {
                    TrendingPeriod.NOW -> graph.appleCharts.topPodcasts()
                    else -> {
                        val settings = graph.settings.current()
                        graph.podcastIndex.trending(
                            settings.podcastIndexKey, settings.podcastIndexSecret, period,
                        )
                    }
                }
            }
                .onSuccess { list -> _state.update { it.copy(trending = list, trendingLoading = false) } }
                .onFailure { e -> _state.update { it.copy(error = e.message, trendingLoading = false) } }
        }
    }

    fun loadRecommendations() {
        viewModelScope.launch {
            _state.update { it.copy(recsLoading = true, error = null) }
            runCatching {
                val recs = graph.aiRecommender.recommend()
                // Match each pick against the directory in parallel so rows get
                // real artwork and open the podcast directly.
                coroutineScope {
                    recs.map { rec ->
                        async { ResolvedRecommendation(rec, resolveAgainstDirectory(rec)) }
                    }.awaitAll()
                }
            }
                .onSuccess { recs -> _state.update { it.copy(recommendations = recs, recsLoading = false) } }
                .onFailure { e -> _state.update { it.copy(error = e.message, recsLoading = false) } }
        }
    }

    private suspend fun resolveAgainstDirectory(rec: AiRecommendation): PodcastEntity? =
        runCatching { graph.podcasts.search(rec.title) }.getOrNull()?.let { results ->
            results.firstOrNull { it.title.equals(rec.title, ignoreCase = true) }
                ?: results.firstOrNull()
        }

    /** Inserts the podcast locally, pulls its feed, then hands back the id for navigation. */
    fun openPodcast(podcast: PodcastEntity, onOpened: (String) -> Unit) {
        viewModelScope.launch {
            _state.update { it.copy(opening = true, error = null) }
            runCatching { graph.podcasts.openPodcast(podcast) }
                .onSuccess {
                    _state.update { s -> s.copy(opening = false) }
                    onOpened(podcast.id)
                }
                .onFailure { e -> _state.update { s -> s.copy(error = e.message, opening = false) } }
        }
    }

    fun openTrending(item: TrendingPodcast, onOpened: (String) -> Unit) {
        viewModelScope.launch {
            _state.update { it.copy(opening = true, error = null) }
            runCatching {
                val podcast = when {
                    item.feedUrl != null -> PodcastEntity(
                        id = stableId(item.feedUrl),
                        title = item.title,
                        author = item.author,
                        feedUrl = item.feedUrl,
                        artworkUrl = item.artworkUrl,
                        description = null,
                    )
                    item.appleId != null -> graph.appleCharts.resolveFeed(item.appleId)
                    else -> null
                } ?: error("Could not resolve this podcast's feed")
                graph.podcasts.openPodcast(podcast)
                podcast.id
            }
                .onSuccess { id ->
                    _state.update { s -> s.copy(opening = false) }
                    onOpened(id)
                }
                .onFailure { e -> _state.update { s -> s.copy(error = e.message, opening = false) } }
        }
    }

    /** AI recommendations come back as names — reuse search to find the real podcast. */
    fun searchRecommendation(rec: AiRecommendation) {
        _state.update { it.copy(query = rec.title) }
        search()
    }
}

@Composable
fun DiscoverScreen(onOpenPodcast: (String) -> Unit) {
    val viewModel = appViewModel { DiscoverViewModel(it) }
    val state by viewModel.state.collectAsStateWithLifecycle()

    Box(modifier = Modifier.fillMaxSize()) {
        LazyColumn(modifier = Modifier.fillMaxSize()) {
            item {
                OutlinedTextField(
                    value = state.query,
                    onValueChange = viewModel::setQuery,
                    label = { Text("Search podcasts") },
                    singleLine = true,
                    trailingIcon = {
                        IconButton(onClick = viewModel::search) {
                            Icon(Icons.Filled.Search, "Search")
                        }
                    },
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp),
                )
            }

            state.error?.let { error ->
                item {
                    Text(
                        error,
                        color = MaterialTheme.colorScheme.error,
                        modifier = Modifier.padding(horizontal = 16.dp),
                    )
                }
            }

            if (state.searching) {
                item {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(16.dp),
                        horizontalArrangement = Arrangement.Center,
                    ) { CircularProgressIndicator() }
                }
            }

            val results = state.searchResults
            if (results != null) {
                item {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text("Results", style = MaterialTheme.typography.titleMedium)
                        Text(
                            "Clear",
                            color = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.clickable { viewModel.clearSearch() },
                        )
                    }
                }
                items(results, key = { it.id }) { podcast ->
                    PodcastListRow(
                        title = podcast.title,
                        subtitle = podcast.author,
                        artworkUrl = podcast.artworkUrl,
                        onClick = { viewModel.openPodcast(podcast) { id -> onOpenPodcast(id) } },
                    )
                }
            } else {
                item {
                    Text(
                        "Popular podcasts",
                        style = MaterialTheme.typography.titleMedium,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                    )
                }
                item {
                    Row(
                        modifier = Modifier.padding(horizontal = 16.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        FilterChip(
                            selected = state.trendingPeriod == TrendingPeriod.NOW,
                            onClick = { viewModel.loadTrending(TrendingPeriod.NOW) },
                            label = { Text("Now") },
                        )
                        FilterChip(
                            selected = state.trendingPeriod == TrendingPeriod.WEEK,
                            onClick = { viewModel.loadTrending(TrendingPeriod.WEEK) },
                            label = { Text("Week") },
                            enabled = state.hasPodcastIndexCreds,
                        )
                        FilterChip(
                            selected = state.trendingPeriod == TrendingPeriod.MONTH,
                            onClick = { viewModel.loadTrending(TrendingPeriod.MONTH) },
                            label = { Text("Month") },
                            enabled = state.hasPodcastIndexCreds,
                        )
                    }
                }
                if (!state.hasPodcastIndexCreds) {
                    item {
                        Text(
                            "Add free PodcastIndex API keys in Settings to unlock Week/Month trending.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                        )
                    }
                }
                if (state.trendingLoading) {
                    item {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(16.dp),
                            horizontalArrangement = Arrangement.Center,
                        ) { CircularProgressIndicator() }
                    }
                }
                itemsIndexed(state.trending) { index, item ->
                    PodcastListRow(
                        title = "${index + 1}. ${item.title}",
                        subtitle = item.author,
                        artworkUrl = item.artworkUrl,
                        onClick = { viewModel.openTrending(item) { id -> onOpenPodcast(id) } },
                    )
                }

                item {
                    Button(
                        onClick = viewModel::loadRecommendations,
                        enabled = !state.recsLoading,
                        modifier = Modifier.padding(16.dp),
                    ) {
                        Icon(Icons.Filled.AutoAwesome, null)
                        Text("  Ask AI for picks")
                    }
                }
                if (state.recsLoading) {
                    item {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(16.dp),
                            horizontalArrangement = Arrangement.Center,
                        ) { CircularProgressIndicator() }
                    }
                }
                state.recommendations?.let { recs ->
                    items(recs) { resolved ->
                        val podcast = resolved.podcast
                        val rec = resolved.rec
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable {
                                    if (podcast != null) {
                                        viewModel.openPodcast(podcast) { id -> onOpenPodcast(id) }
                                    } else {
                                        viewModel.searchRecommendation(rec)
                                    }
                                }
                                .padding(horizontal = 16.dp, vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            AsyncImage(
                                model = podcast?.artworkUrl,
                                contentDescription = null,
                                modifier = Modifier
                                    .size(56.dp)
                                    .clip(RoundedCornerShape(8.dp)),
                            )
                            Column(modifier = Modifier.padding(start = 12.dp)) {
                                Text(
                                    (podcast?.title ?: rec.title) +
                                        ((podcast?.author ?: rec.author)?.let { " — $it" } ?: ""),
                                    style = MaterialTheme.typography.bodyLarge,
                                    maxLines = 2,
                                    overflow = TextOverflow.Ellipsis,
                                )
                                Text(
                                    rec.reason,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                    }
                }
            }
        }

        if (state.opening) {
            CircularProgressIndicator(modifier = Modifier.align(Alignment.Center))
        }
    }
}

@Composable
private fun PodcastListRow(
    title: String,
    subtitle: String,
    artworkUrl: String?,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        AsyncImage(
            model = artworkUrl,
            contentDescription = null,
            modifier = Modifier
                .size(56.dp)
                .clip(RoundedCornerShape(8.dp)),
        )
        Column(modifier = Modifier.padding(start = 12.dp)) {
            Text(title, style = MaterialTheme.typography.bodyLarge, maxLines = 2, overflow = TextOverflow.Ellipsis)
            Text(
                subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}
