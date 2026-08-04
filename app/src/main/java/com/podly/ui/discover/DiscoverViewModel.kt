package com.podly.ui.discover

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.podly.AppGraph
import com.podly.data.ArchiveRescuer
import com.podly.data.db.PodcastEntity
import com.podly.data.db.stableId
import com.podly.network.TrendingPeriod
import com.podly.network.TrendingPodcast
import com.podly.network.ai.AiAcclaimedPick
import com.podly.network.ai.AiRecentEpisodePick
import com.podly.network.ai.AiRecommendation
import com.podly.network.ai.RecentEpisodeMatcher
import com.podly.network.ai.RecentEpisodeWindow
import com.podly.ui.util.formatDate
import kotlinx.coroutines.Job
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

/** Outcome of "Save as playlist": how many picks resolved, and which didn't. */
data class RecentPlaylistResult(
    val playlistId: Long,
    val name: String,
    val saved: Int,
    val total: Int,
    val missed: List<String>,
)

data class DiscoverUiState(
    val query: String = "",
    val searching: Boolean = false,
    val searchResults: List<PodcastEntity>? = null,
    val trendingPeriod: TrendingPeriod = TrendingPeriod.NOW,
    val trending: List<TrendingPodcast> = emptyList(),
    val trendingLoading: Boolean = false,
    val hasPodcastIndexCreds: Boolean = false,
    // Any archive provider (Taddy or PodcastIndex) configured — drives the rescue hint.
    val hasArchiveCreds: Boolean = false,
    val recommendations: List<ResolvedRecommendation>? = null,
    val recsLoading: Boolean = false,
    val recsGeneratedAtMs: Long = 0,
    val acclaimed: List<ResolvedAcclaimed>? = null,
    val acclaimedLoading: Boolean = false,
    val recentEpisodeWindow: RecentEpisodeWindow = RecentEpisodeWindow.MONTH,
    val recentEpisodes: List<ResolvedRecentEpisode>? = null,
    val recentEpisodesLoading: Boolean = false,
    val recentCoverageStart: String? = null,
    val recentCoverageEnd: String? = null,
    val recentGeneratedAtMs: Long = 0,
    val acclaimedGeneratedAtMs: Long = 0,
    val savingPlaylist: Boolean = false,
    val recentPlaylistResult: RecentPlaylistResult? = null,
    val error: String? = null,
    val opening: Boolean = false,
)

class DiscoverViewModel(private val graph: AppGraph) : ViewModel() {
    private val _state = MutableStateFlow(DiscoverUiState())
    val state: StateFlow<DiscoverUiState> = _state

    private var acclaimedFetchedAtMs = 0L
    private var recentEpisodesObserveJob: Job? = null

    init {
        viewModelScope.launch {
            graph.settings.settings.collect { settings ->
                val hasPi = settings.podcastIndexKey.isNotBlank() && settings.podcastIndexSecret.isNotBlank()
                val hasTaddy = settings.taddyUserId.isNotBlank() && settings.taddyApiKey.isNotBlank()
                _state.update {
                    it.copy(hasPodcastIndexCreds = hasPi, hasArchiveCreds = hasPi || hasTaddy)
                }
            }
        }
        viewModelScope.launch {
            graph.aiPicksCache.loadAcclaimed()?.let { cached ->
                acclaimedFetchedAtMs = cached.fetchedAtMs
                _state.update { s ->
                    if (s.acclaimed == null) {
                        s.copy(
                            acclaimed = cached.picks.map { ResolvedAcclaimed(it.pick, it.toPodcastOrNull()) },
                            acclaimedGeneratedAtMs = cached.generatedAtMs,
                        )
                    } else {
                        s
                    }
                }
            }
        }
        // Surface the default window's cached picks without kicking off a fetch.
        observeRecentEpisodes(RecentEpisodeWindow.MONTH)
        loadTrending(TrendingPeriod.NOW)
    }

    fun setQuery(query: String) = _state.update { it.copy(query = query) }

    fun search() {
        val term = _state.value.query.trim()
        if (term.isEmpty()) {
            _state.update { it.copy(searchResults = null) }
            return
        }
        // A pasted feed URL: offer it as a direct result — opening it pulls the
        // feed and fills in the real title/artwork.
        if (term.startsWith("http://", ignoreCase = true) ||
            term.startsWith("https://", ignoreCase = true)
        ) {
            val podcast = PodcastEntity(
                id = stableId(term),
                title = term.substringAfter("://"),
                author = "RSS feed",
                feedUrl = term,
                artworkUrl = null,
                description = null,
            )
            _state.update { it.copy(searchResults = listOf(podcast), searching = false) }
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
                        async { ResolvedRecommendation(rec, graph.podcasts.resolveByTitle(rec.title)) }
                    }.awaitAll()
                }
            }
                .onSuccess { recs ->
                    _state.update {
                        it.copy(
                            recommendations = recs,
                            recsLoading = false,
                            recsGeneratedAtMs = System.currentTimeMillis(),
                        )
                    }
                }
                .onFailure { e ->
                    Log.e(TAG, "AI picks failed", e)
                    _state.update { it.copy(error = describe(e), recsLoading = false) }
                }
        }
    }

    fun loadAcclaimed(force: Boolean = false) {
        // Awards and best-of lists barely move — serve the cached result unless it's
        // stale or the user explicitly refreshes, then re-fetch the static file.
        val cacheFresh = System.currentTimeMillis() - acclaimedFetchedAtMs < ACCLAIMED_MAX_AGE_MS
        if (!force && cacheFresh && _state.value.acclaimed != null) return
        viewModelScope.launch {
            _state.update { it.copy(acclaimedLoading = true, error = null) }
            runCatching { graph.remoteRecs.acclaimed() }
                .onSuccess { payload ->
                    acclaimedFetchedAtMs = System.currentTimeMillis()
                    val stamped = payload.copy(fetchedAtMs = acclaimedFetchedAtMs)
                    graph.aiPicksCache.saveAcclaimed(stamped)
                    _state.update {
                        it.copy(
                            acclaimed = stamped.picks.map { p -> ResolvedAcclaimed(p.pick, p.toPodcastOrNull()) },
                            acclaimedGeneratedAtMs = stamped.generatedAtMs,
                            acclaimedLoading = false,
                        )
                    }
                }
                .onFailure { e ->
                    Log.e(TAG, "Acclaimed fetch failed", e)
                    // Keep showing the cached list rather than replacing it with an
                    // error, but say the refresh itself failed — a silently vanishing
                    // spinner reads as a no-op.
                    if (_state.value.acclaimed != null) {
                        graph.messages.post("Refresh failed: ${describe(e)}")
                    }
                    _state.update {
                        it.copy(
                            error = if (it.acclaimed == null) describe(e) else it.error,
                            acclaimedLoading = false,
                        )
                    }
                }
        }
    }

    /**
     * Selects [window], surfaces its cached picks, and — when the cache is stale or
     * [force] is set — fetches the pre-generated static file for [window] (produced
     * server-side by the recommendations GitHub Action) and caches it. The slow
     * web-search work now happens off-device, so this is just a quick download.
     */
    fun loadRecentEpisodes(window: RecentEpisodeWindow = _state.value.recentEpisodeWindow, force: Boolean = false) {
        val sameWindow = _state.value.recentEpisodeWindow == window
        _state.update {
            it.copy(
                recentEpisodeWindow = window,
                recentEpisodes = if (sameWindow) it.recentEpisodes else null,
                recentCoverageStart = if (sameWindow) it.recentCoverageStart else null,
                recentCoverageEnd = if (sameWindow) it.recentCoverageEnd else null,
                recentGeneratedAtMs = if (sameWindow) it.recentGeneratedAtMs else 0,
                recentPlaylistResult = null,
                error = null,
            )
        }
        observeRecentEpisodes(window)
        viewModelScope.launch {
            val cached = graph.aiPicksCache.loadRecentEpisodes(window)
            val fresh = cached != null &&
                System.currentTimeMillis() - cached.fetchedAtMs < RECENT_EPISODES_MAX_AGE_MS
            if (!force && fresh) return@launch
            _state.update { it.copy(recentEpisodesLoading = true) }
            runCatching { graph.remoteRecs.recentEpisodes(window) }
                .onSuccess { payload ->
                    // Persist; observeRecentEpisodes's flow pushes picks + coverage into UI state.
                    graph.aiPicksCache.saveRecentEpisodes(
                        window, payload.copy(fetchedAtMs = System.currentTimeMillis()),
                    )
                    _state.update { it.copy(recentEpisodesLoading = false) }
                }
                .onFailure { e ->
                    Log.e(TAG, "Recent episodes fetch failed", e)
                    // Keep showing the cached list rather than replacing it with an
                    // error, but say the refresh itself failed — a silently vanishing
                    // spinner reads as a no-op.
                    if (_state.value.recentEpisodes != null) {
                        graph.messages.post("Refresh failed: ${describe(e)}")
                    }
                    _state.update {
                        it.copy(
                            recentEpisodesLoading = false,
                            error = if (it.recentEpisodes == null) describe(e) else it.error,
                        )
                    }
                }
        }
    }

    /** Streams the cached picks + coverage metadata for [window] into UI state. */
    private fun observeRecentEpisodes(window: RecentEpisodeWindow) {
        recentEpisodesObserveJob?.cancel()
        recentEpisodesObserveJob = viewModelScope.launch {
            graph.aiPicksCache.recentEpisodesFlow(window).collect { cached ->
                if (_state.value.recentEpisodeWindow != window || cached == null) return@collect
                _state.update {
                    it.copy(
                        recentEpisodes = cached.picks.map { p ->
                            ResolvedRecentEpisode(p.pick, p.toPodcastOrNull())
                        },
                        recentCoverageStart = cached.coverageStart,
                        recentCoverageEnd = cached.coverageEnd,
                        recentGeneratedAtMs = cached.generatedAtMs,
                    )
                }
            }
        }
    }

    /**
     * Turns the recommended episodes into a real, playable playlist: loads each
     * podcast's feed, matches the recommended episode by title, then creates a
     * playlist from the matches. Picks whose episode can't be found are skipped.
     * Hands the new playlist's id back via [onCreated].
     */
    fun saveRecentEpisodesAsPlaylist() {
        val picks = _state.value.recentEpisodes
        if (picks.isNullOrEmpty() || _state.value.savingPlaylist) return
        val window = _state.value.recentEpisodeWindow
        viewModelScope.launch {
            _state.update { it.copy(savingPlaylist = true, error = null, recentPlaylistResult = null) }
            runCatching {
                val resolved = coroutineScope {
                    picks.map { pick -> async { pick to resolveEpisodeId(pick) } }.awaitAll()
                }
                val matched = resolved.mapNotNull { (_, id) -> id }.distinct()
                val missed = resolved.filter { (_, id) -> id == null }.map { (p, _) -> p.pick.episodeTitle }
                require(matched.isNotEmpty()) {
                    "Couldn't match any of these episodes to a podcast feed."
                }
                // Stamp with the picks' generation date so repeat saves stay distinguishable.
                val generatedAtMs = _state.value.recentGeneratedAtMs.takeIf { it > 0 }
                    ?: System.currentTimeMillis()
                val name = listOfNotNull("Recent picks", window.label, formatDate(generatedAtMs))
                    .joinToString(" · ")
                val playlistId = graph.playlists.create(name)
                matched.forEach { graph.playlists.addEpisode(playlistId, it) }
                RecentPlaylistResult(playlistId, name, matched.size, picks.size, missed)
            }.onSuccess { result ->
                _state.update { it.copy(savingPlaylist = false, recentPlaylistResult = result) }
            }.onFailure { e ->
                _state.update { it.copy(savingPlaylist = false, error = describe(e)) }
            }
        }
    }

    /**
     * Resolves one pick to a local episode id: load the podcast feed, then match the
     * recommended (often paraphrased) title + date against the feed via
     * [RecentEpisodeMatcher].
     */
    private suspend fun resolveEpisodeId(resolved: ResolvedRecentEpisode): String? {
        val podcast = resolved.podcast
            ?: graph.podcasts.resolveByTitle(resolved.pick.podcastTitle)
            ?: run {
                Log.w(TAG, "Couldn't resolve show \"${resolved.pick.podcastTitle}\" via iTunes")
                return null
            }
        // A failed feed pull (offline, or an http-only host like feeds.pbs.org that
        // Android's cleartext block makes unfetchable) mustn't skip the archive
        // rescue below — the archives can still supply playable episodes.
        val loaded = runCatching { graph.podcasts.openPodcast(podcast) }
            .onFailure { Log.w(TAG, "Feed pull failed for ${podcast.title}: ${it.message}") }
            .getOrNull() ?: podcast
        val episodes = graph.podcasts.episodesForPodcastOnce(loaded.id)
        val idx = RecentEpisodeMatcher.bestMatch(
            title = resolved.pick.episodeTitle,
            publishedApprox = resolved.pick.publishedApprox,
            candidates = episodes.map {
                RecentEpisodeMatcher.Candidate(it.title, it.description, it.pubDateMs)
            },
        )
        if (idx != null) return episodes[idx].id
        // Not in the feed — e.g. a short window like "The Interview" that exposes only its
        // newest episodes. Fall back to PodcastIndex's archive (needs the user's creds).
        val rescued = graph.picksRescuer.rescue(
            loaded,
            listOf(Unit to ArchiveRescuer.Query(resolved.pick.episodeTitle, resolved.pick.publishedApprox)),
        )[Unit]
        if (rescued == null) {
            Log.w(TAG, "No feed or archive match for \"${resolved.pick.episodeTitle}\" in ${loaded.title}")
        }
        return rescued
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
