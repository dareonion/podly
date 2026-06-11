package com.podly.data

import com.podly.data.db.EpisodeEntity
import com.podly.data.db.EpisodeDao
import com.podly.data.db.PodcastDao
import com.podly.data.db.PodcastEntity
import com.podly.network.Http
import com.podly.network.ItunesApi
import com.podly.network.RssParser
import com.podly.network.toEpisodeEntities
import kotlinx.coroutines.flow.Flow
import java.io.StringReader

class PodcastRepository(
    private val podcastDao: PodcastDao,
    private val episodeDao: EpisodeDao,
    private val itunesApi: ItunesApi = ItunesApi(),
    private val rssParser: RssParser = RssParser(),
) {
    fun subscribedPodcasts(): Flow<List<PodcastEntity>> = podcastDao.subscribedPodcasts()
    fun podcast(id: String): Flow<PodcastEntity?> = podcastDao.byIdFlow(id)
    fun episodesForPodcast(podcastId: String): Flow<List<EpisodeEntity>> =
        episodeDao.episodesForPodcast(podcastId)

    fun libraryEpisodes(): Flow<List<EpisodeEntity>> = episodeDao.libraryEpisodes()
    fun downloadedEpisodes(): Flow<List<EpisodeEntity>> = episodeDao.downloadedEpisodes()
    fun episode(id: String): Flow<EpisodeEntity?> = episodeDao.byIdFlow(id)

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
        val xml = Http.get(podcast.feedUrl)
        val feed = rssParser.parse(StringReader(xml))
        podcastDao.updateMetadata(
            id = podcast.id,
            title = feed.title ?: podcast.title,
            author = feed.author ?: podcast.author,
            artworkUrl = feed.imageUrl ?: podcast.artworkUrl,
            description = feed.description ?: podcast.description,
        )
        episodeDao.insertIgnore(feed.toEpisodeEntities(podcast))
    }

    suspend fun refreshAllSubscribed() {
        podcastDao.subscribedPodcastsOnce().forEach { podcast ->
            runCatching { refreshEpisodes(podcast) }
        }
    }

    suspend fun setSubscribed(podcastId: String, subscribed: Boolean) {
        podcastDao.setSubscribed(podcastId, subscribed)
        if (!subscribed) podcastDao.pruneOrphans()
    }

    suspend fun setEpisodeInLibrary(episodeId: String, inLibrary: Boolean) =
        episodeDao.setInLibrary(episodeId, inLibrary)

    suspend fun episodeById(id: String): EpisodeEntity? = episodeDao.byId(id)
}
