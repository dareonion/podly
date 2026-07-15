package com.podly.data

import com.podly.data.db.EpisodeEntity
import com.podly.data.db.EpisodeHistorySummary
import com.podly.data.db.EpisodeDao
import com.podly.data.db.ListeningSegmentEntity
import com.podly.data.db.PodcastDao
import com.podly.data.db.PodcastEpisodeSortOrder
import com.podly.data.db.PodcastEntity
import com.podly.data.db.stableId
import com.podly.network.Http
import com.podly.network.ItunesApi
import com.podly.network.RssParser
import com.podly.network.toEpisodeEntities
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import java.io.Reader
import java.io.StringReader

data class RefreshSummary(val total: Int, val failures: Int)

class PodcastRepository(
    private val podcastDao: PodcastDao,
    private val episodeDao: EpisodeDao,
    private val itunesApi: ItunesApi = ItunesApi(),
    private val rssParser: RssParser = RssParser(),
    private val opmlParser: OpmlParser = OpmlParser(),
) {
    fun subscribedPodcasts(): Flow<List<PodcastEntity>> = podcastDao.subscribedPodcasts()
    fun podcast(id: String): Flow<PodcastEntity?> = podcastDao.byIdFlow(id)
    fun episodesForPodcast(podcastId: String): Flow<List<EpisodeEntity>> =
        episodeDao.episodesForPodcast(podcastId)
    suspend fun episodesForPodcastOnce(podcastId: String): List<EpisodeEntity> =
        episodeDao.episodesForPodcastOnce(podcastId)

    fun libraryEpisodes(): Flow<List<EpisodeEntity>> = episodeDao.libraryEpisodes()
    fun downloadedEpisodes(): Flow<List<EpisodeEntity>> = episodeDao.downloadedEpisodes()
    fun episode(id: String): Flow<EpisodeEntity?> = episodeDao.byIdFlow(id)
    fun listeningHistory(): Flow<List<EpisodeHistorySummary>> = episodeDao.listeningHistory()
    fun listeningSegments(): Flow<List<ListeningSegmentEntity>> = episodeDao.listeningSegments()

    suspend fun search(term: String): List<PodcastEntity> = itunesApi.searchPodcasts(term)

    /**
     * Ensures the podcast row exists locally (e.g. after tapping a search result)
     * and pulls its episode list from the feed.
     */
    suspend fun openPodcast(podcast: PodcastEntity): PodcastEntity {
        podcastDao.insertIgnore(podcast)
        refreshEpisodes(podcastDao.byId(podcast.id) ?: podcast)
        return podcastDao.byId(podcast.id) ?: podcast
    }

    suspend fun refreshEpisodes(podcast: PodcastEntity) {
        val response = Http.getConditional(podcast.feedUrl, podcast.etag, podcast.lastModified)
        if (response.notModified) return
        val feed = rssParser.parse(StringReader(response.body!!))
        podcastDao.updateMetadata(
            id = podcast.id,
            title = feed.title ?: podcast.title,
            author = feed.author ?: podcast.author,
            artworkUrl = feed.imageUrl ?: podcast.artworkUrl,
            description = feed.description ?: podcast.description,
        )
        episodeDao.upsertFromFeed(feed.toEpisodeEntities(podcast))
        // Stored last: a failed parse/insert must refetch next time, not 304-skip.
        podcastDao.updateCacheValidators(podcast.id, response.etag, response.lastModified)
    }

    suspend fun refreshAllSubscribed(): RefreshSummary {
        val podcasts = podcastDao.subscribedPodcastsOnce()
        val succeeded = coroutineScope {
            val gate = Semaphore(MAX_CONCURRENT_REFRESHES)
            podcasts.map { podcast ->
                async { gate.withPermit { runCatching { refreshEpisodes(podcast) }.isSuccess } }
            }.awaitAll()
        }
        return RefreshSummary(total = succeeded.size, failures = succeeded.count { !it })
    }

    suspend fun importOpml(reader: Reader): OpmlImportResult {
        val outlines = opmlParser.parse(reader)
        var added = 0
        var alreadySubscribed = 0
        var refreshed = 0
        var failedRefreshes = 0

        outlines.forEach { outline ->
            val podcastId = stableId(outline.feedUrl)
            val existing = podcastDao.byId(podcastId)
            if (existing == null) {
                added += 1
                podcastDao.insertIgnore(
                    PodcastEntity(
                        id = podcastId,
                        title = outline.title,
                        author = "",
                        feedUrl = outline.feedUrl,
                        artworkUrl = null,
                        description = null,
                        subscribed = true,
                    )
                )
            } else {
                if (existing.subscribed) alreadySubscribed += 1
                podcastDao.setSubscribed(podcastId, true)
            }

            val podcast = podcastDao.byId(podcastId) ?: return@forEach
            if (runCatching { refreshEpisodes(podcast) }.isSuccess) {
                refreshed += 1
            } else {
                failedRefreshes += 1
            }
        }

        return OpmlImportResult(
            total = outlines.size,
            added = added,
            alreadySubscribed = alreadySubscribed,
            refreshed = refreshed,
            failedRefreshes = failedRefreshes,
        )
    }

    suspend fun exportOpml(): String =
        OpmlExporter.export(podcastDao.subscribedPodcastsOnce())

    suspend fun setSubscribed(podcastId: String, subscribed: Boolean) {
        podcastDao.setSubscribed(podcastId, subscribed)
        if (!subscribed) podcastDao.pruneOrphans()
    }

    suspend fun setEpisodeSortOrder(podcastId: String, sortOrder: PodcastEpisodeSortOrder) =
        podcastDao.setEpisodeSortOrder(podcastId, sortOrder)

    suspend fun setEpisodeInLibrary(episodeId: String, inLibrary: Boolean) =
        episodeDao.setInLibrary(episodeId, inLibrary)

    fun continueListening(limit: Int = 20): Flow<List<EpisodeEntity>> =
        episodeDao.continueListening(limit)

    suspend fun setEpisodePlayed(episodeId: String, played: Boolean) =
        episodeDao.setPlayed(episodeId, played)

    suspend fun updateEpisodeNoteAndRating(episodeId: String, note: String?, rating: Int?) =
        episodeDao.updateUserNoteAndRating(
            episodeId,
            note?.trim()?.takeIf { it.isNotEmpty() },
            rating?.coerceIn(1, 5),
        )

    suspend fun episodeById(id: String): EpisodeEntity? = episodeDao.byId(id)

    private companion object {
        const val MAX_CONCURRENT_REFRESHES = 4
    }
}
