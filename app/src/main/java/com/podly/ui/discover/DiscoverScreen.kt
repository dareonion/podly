package com.podly.ui.discover

import android.util.Log
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
import androidx.compose.material.icons.filled.EmojiEvents
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
import com.podly.data.CachedAcclaimed
import com.podly.data.CachedAcclaimedPick
import com.podly.data.CachedRecentEpisodePick
import com.podly.data.CachedRecentEpisodes
import com.podly.data.db.PodcastEntity
import com.podly.data.db.stableId
import com.podly.network.TrendingPeriod
import com.podly.network.TrendingPodcast
import com.podly.network.ai.AiAcclaimedPick
import com.podly.network.ai.AiRecentEpisodePick
import com.podly.network.ai.AiRecommendation
import com.podly.network.ai.RecentEpisodeWindow
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

/** An acclaimed pick (podcast or single episode), matched against the directory. */
data class ResolvedAcclaimed(
    val pick: AiAcclaimedPick,
    val podcast: PodcastEntity?,
)

/** A recent episode pick, matched against the directory at podcast level. */
data class ResolvedRecentEpisode(
    val pick: AiRecentEpisodePick,
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
    val acclaimed: List<ResolvedAcclaimed>? = null,
    val acclaimedLoading: Boolean = false,
    val recentEpisodeWindow: RecentEpisodeWindow = RecentEpisodeWindow.MONTH,
    val recentEpisodes: List<ResolvedRecentEpisode>? = null,
    val recentEpisodesLoading: Boolean = false,
    val error: String? = null,
    val opening: Boolean = false,
)

class DiscoverViewModel(private val graph: AppGraph) : ViewModel() {
    private val _state = MutableStateFlow(DiscoverUiState())
    val state: StateFlow<DiscoverUiState> = _state

    private var acclaimedFetchedAtMs = 0L
    private val recentEpisodeFetchedAtMs = mutableMapOf<RecentEpisodeWindow, Long>()

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
        viewModelScope.launch {
            graph.aiPicksCache.loadAcclaimed()?.let { cached ->
                acclaimedFetchedAtMs = cached.fetchedAtMs
                _state.update { s ->
                    if (s.acclaimed == null) {
                        s.copy(acclaimed = cached.picks.map { ResolvedAcclaimed(it.pick, it.toPodcastOrNull()) })
                    } else {
                        s
                    }
                }
            }
        }
        viewModelScope.launch {
            graph.aiPicksCache.loadRecentEpisodes(RecentEpisodeWindow.MONTH)?.let { cached ->
                recentEpisodeFetchedAtMs[RecentEpisodeWindow.MONTH] = cached.fetchedAtMs
                _state.update { s ->
                    if (s.recentEpisodes == null) {
                        s.copy(
                            recentEpisodeWindow = RecentEpisodeWindow.MONTH,
                            recentEpisodes = cached.picks.map {
                                ResolvedRecentEpisode(it.pick, it.toPodcastOrNull())
                            },
                        )
                    } else {
                        s
                    }
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
                        async { ResolvedRecommendation(rec, resolveAgainstDirectory(rec.title)) }
                    }.awaitAll()
                }
            }
                .onSuccess { recs -> _state.update { it.copy(recommendations = recs, recsLoading = false) } }
                .onFailure { e ->
                    Log.e(TAG, "AI picks failed", e)
                    _state.update { it.copy(error = describe(e), recsLoading = false) }
                }
        }
    }

    fun loadAcclaimed(force: Boolean = false) {
        // Awards and best-of lists barely move week to week — serve the cached
        // result unless it's stale or the user explicitly refreshes.
        val cacheFresh = System.currentTimeMillis() - acclaimedFetchedAtMs < ACCLAIMED_MAX_AGE_MS
        if (!force && cacheFresh && _state.value.acclaimed != null) return
        viewModelScope.launch {
            _state.update { it.copy(acclaimedLoading = true, error = null) }
            runCatching {
                val picks = graph.aiRecommender.acclaimed()
                coroutineScope {
                    picks.map { pick ->
                        async { ResolvedAcclaimed(pick, resolveAgainstDirectory(pick.podcastTitle)) }
                    }.awaitAll()
                }
            }
                .onSuccess { picks ->
                    acclaimedFetchedAtMs = System.currentTimeMillis()
                    graph.aiPicksCache.saveAcclaimed(
                        CachedAcclaimed(
                            picks = picks.map { CachedAcclaimedPick.of(it.pick, it.podcast) },
                            fetchedAtMs = acclaimedFetchedAtMs,
                        )
                    )
                    _state.update { it.copy(acclaimed = picks, acclaimedLoading = false) }
                }
                .onFailure { e ->
                    Log.e(TAG, "Acclaimed picks failed", e)
                    _state.update { it.copy(error = describe(e), acclaimedLoading = false) }
                }
        }
    }

    fun loadRecentEpisodes(window: RecentEpisodeWindow = _state.value.recentEpisodeWindow, force: Boolean = false) {
        val fetchedAt = recentEpisodeFetchedAtMs[window] ?: 0L
        val cacheFresh = System.currentTimeMillis() - fetchedAt < RECENT_EPISODES_MAX_AGE_MS
        val sameWindow = _state.value.recentEpisodeWindow == window
        if (!force && sameWindow && cacheFresh && _state.value.recentEpisodes != null) return
        viewModelScope.launch {
            _state.update {
                it.copy(
                    recentEpisodeWindow = window,
                    recentEpisodesLoading = true,
                    recentEpisodes = if (sameWindow) it.recentEpisodes else null,
                    error = null,
                )
            }
            runCatching {
                val cached = if (!force) graph.aiPicksCache.loadRecentEpisodes(window) else null
                if (cached != null && System.currentTimeMillis() - cached.fetchedAtMs < RECENT_EPISODES_MAX_AGE_MS) {
                    recentEpisodeFetchedAtMs[window] = cached.fetchedAtMs
                    cached.picks.map { ResolvedRecentEpisode(it.pick, it.toPodcastOrNull()) }
                } else {
                    val picks = graph.aiRecommender.recentEpisodes(window)
                    coroutineScope {
                        picks.map { pick ->
                            async { ResolvedRecentEpisode(pick, resolveAgainstDirectory(pick.podcastTitle)) }
                        }.awaitAll()
                    }.also { resolved ->
                        val fetchedAtNow = System.currentTimeMillis()
                        recentEpisodeFetchedAtMs[window] = fetchedAtNow
                        graph.aiPicksCache.saveRecentEpisodes(
                            window,
                            CachedRecentEpisodes(
                                picks = resolved.map { CachedRecentEpisodePick.of(it.pick, it.podcast) },
                                fetchedAtMs = fetchedAtNow,
                            )
                        )
                    }
                }
            }
                .onSuccess { picks ->
                    _state.update {
                        it.copy(
                            recentEpisodeWindow = window,
                            recentEpisodes = picks,
                            recentEpisodesLoading = false,
                        )
                    }
                }
                .onFailure { e ->
                    Log.e(TAG, "Recent episode picks failed", e)
                    _state.update { it.copy(error = describe(e), recentEpisodesLoading = false) }
                }
        }
    }

    private suspend fun resolveAgainstDirectory(title: String): PodcastEntity? =
        runCatching { graph.podcasts.search(title) }.getOrNull()?.let { results ->
            results.firstOrNull { it.title.equals(title, ignoreCase = true) }
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
    fun searchByTitle(title: String) {
        _state.update { it.copy(query = title) }
        search()
    }

    /** SDK errors like "Request failed" are useless alone — append the cause. */
    private fun describe(e: Throwable): String =
        listOfNotNull(e.message, e.cause?.message).distinct().joinToString(": ").ifEmpty { e.toString() }

    private companion object {
        const val TAG = "DiscoverViewModel"
        const val ACCLAIMED_MAX_AGE_MS = 7L * 24 * 60 * 60 * 1000
        const val RECENT_EPISODES_MAX_AGE_MS = 24L * 60 * 60 * 1000
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
                    Column(
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Button(
                                onClick = viewModel::loadRecommendations,
                                enabled = !state.recsLoading,
                            ) {
                                Icon(Icons.Filled.AutoAwesome, null)
                                Text("  AI picks")
                            }
                            Button(
                                onClick = viewModel::loadAcclaimed,
                                enabled = !state.acclaimedLoading,
                            ) {
                                Icon(Icons.Filled.EmojiEvents, null)
                                Text("  Acclaimed")
                            }
                        }
                        Button(
                            onClick = { viewModel.loadRecentEpisodes() },
                            enabled = !state.recentEpisodesLoading,
                        ) {
                            Icon(Icons.Filled.AutoAwesome, null)
                            Text("  Best recent episodes")
                        }
                    }
                }
                if (state.recsLoading || state.acclaimedLoading || state.recentEpisodesLoading) {
                    item {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(16.dp),
                            horizontalArrangement = Arrangement.Center,
                        ) { CircularProgressIndicator() }
                    }
                }
                item {
                    Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
                        Text(
                            "Best individual episodes",
                            style = MaterialTheme.typography.titleMedium,
                        )
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            RecentEpisodeWindow.entries.forEach { window ->
                                FilterChip(
                                    selected = state.recentEpisodeWindow == window,
                                    onClick = { viewModel.loadRecentEpisodes(window) },
                                    label = { Text(window.label) },
                                )
                            }
                        }
                    }
                }
                state.recentEpisodes?.let { picks ->
                    item {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 16.dp, vertical = 8.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(
                                "Worthwhile episodes from the past ${state.recentEpisodeWindow.label.lowercase()}",
                                style = MaterialTheme.typography.titleMedium,
                                modifier = Modifier.weight(1f),
                            )
                            Text(
                                "Refresh",
                                color = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.clickable {
                                    viewModel.loadRecentEpisodes(state.recentEpisodeWindow, force = true)
                                },
                            )
                        }
                    }
                    items(picks) { resolved ->
                        val pick = resolved.pick
                        AiPickRow(
                            podcast = resolved.podcast,
                            title = pick.episodeTitle,
                            subtitle = resolved.podcast?.title ?: pick.podcastTitle,
                            detail = pick.reason + (pick.publishedApprox?.let { " Published around $it." } ?: ""),
                            fallbackQuery = pick.podcastTitle,
                            viewModel = viewModel,
                            onOpenPodcast = onOpenPodcast,
                        )
                    }
                }
                state.acclaimed?.let { picks ->
                    item {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 16.dp, vertical = 8.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(
                                "Award winners & critics' picks from the last year",
                                style = MaterialTheme.typography.titleMedium,
                                modifier = Modifier.weight(1f),
                            )
                            Text(
                                "Refresh",
                                color = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.clickable { viewModel.loadAcclaimed(force = true) },
                            )
                        }
                    }
                    items(picks) { resolved ->
                        val pick = resolved.pick
                        AiPickRow(
                            podcast = resolved.podcast,
                            title = pick.episodeTitle ?: (resolved.podcast?.title ?: pick.podcastTitle),
                            subtitle = if (pick.episodeTitle != null) {
                                "Episode of ${resolved.podcast?.title ?: pick.podcastTitle}"
                            } else {
                                resolved.podcast?.author ?: pick.author
                            },
                            detail = pick.accolade,
                            fallbackQuery = pick.podcastTitle,
                            viewModel = viewModel,
                            onOpenPodcast = onOpenPodcast,
                        )
                    }
                }
                state.recommendations?.let { recs ->
                    item {
                        Text(
                            "AI picks for you",
                            style = MaterialTheme.typography.titleMedium,
                            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                        )
                    }
                    items(recs) { resolved ->
                        val rec = resolved.rec
                        AiPickRow(
                            podcast = resolved.podcast,
                            title = resolved.podcast?.title ?: rec.title,
                            subtitle = resolved.podcast?.author ?: rec.author,
                            detail = rec.reason,
                            fallbackQuery = rec.title,
                            viewModel = viewModel,
                            onOpenPodcast = onOpenPodcast,
                        )
                    }
                }
            }
        }

        if (state.opening) {
            CircularProgressIndicator(modifier = Modifier.align(Alignment.Center))
        }
    }
}

/**
 * A row for an AI-sourced pick: opens the matched podcast directly, or falls
 * back to searching [fallbackQuery] when no directory match was found.
 */
@Composable
private fun AiPickRow(
    podcast: PodcastEntity?,
    title: String,
    subtitle: String?,
    detail: String,
    fallbackQuery: String,
    viewModel: DiscoverViewModel,
    onOpenPodcast: (String) -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable {
                if (podcast != null) {
                    viewModel.openPodcast(podcast) { id -> onOpenPodcast(id) }
                } else {
                    viewModel.searchByTitle(fallbackQuery)
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
                title + (subtitle?.let { " — $it" } ?: ""),
                style = MaterialTheme.typography.bodyLarge,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                detail,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
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
