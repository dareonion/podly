package com.podly.network

import com.podly.data.db.PodcastEntity
import com.podly.data.db.stableId
import kotlinx.serialization.Serializable
import java.net.URLEncoder
import java.security.MessageDigest

enum class TrendingPeriod { NOW, WEEK, MONTH }

/** A chart entry; [feedUrl] may need resolving via iTunes lookup for Apple charts. */
data class TrendingPodcast(
    val title: String,
    val author: String,
    val artworkUrl: String?,
    val feedUrl: String?,
    val appleId: String?,
)

// --- Apple Top Charts (keyless; "current" period only) ---

@Serializable
private data class AppleChartsResponse(val feed: AppleChartsFeed = AppleChartsFeed())

@Serializable
private data class AppleChartsFeed(val results: List<AppleChartEntry> = emptyList())

@Serializable
private data class AppleChartEntry(
    val id: String? = null,
    val name: String? = null,
    val artistName: String? = null,
    val artworkUrl100: String? = null,
)

@Serializable
private data class ItunesLookupResponse(val results: List<ItunesLookupResult> = emptyList())

@Serializable
private data class ItunesLookupResult(
    val feedUrl: String? = null,
    val collectionName: String? = null,
    val artistName: String? = null,
    val artworkUrl600: String? = null,
)

class AppleChartsApi {
    suspend fun topPodcasts(limit: Int = 50): List<TrendingPodcast> {
        val body = Http.get("https://rss.applemarketingtools.com/api/v2/us/podcasts/top/$limit/podcasts.json")
        return Http.json.decodeFromString<AppleChartsResponse>(body).feed.results.mapNotNull { entry ->
            TrendingPodcast(
                title = entry.name ?: return@mapNotNull null,
                author = entry.artistName ?: "",
                artworkUrl = entry.artworkUrl100,
                feedUrl = null,
                appleId = entry.id,
            )
        }
    }

    /** Apple charts don't carry feed URLs; resolve via the iTunes lookup API on tap. */
    suspend fun resolveFeed(appleId: String): PodcastEntity? {
        val body = Http.get("https://itunes.apple.com/lookup?id=$appleId&entity=podcast")
        val result = Http.json.decodeFromString<ItunesLookupResponse>(body).results.firstOrNull()
        val feedUrl = result?.feedUrl ?: return null
        return PodcastEntity(
            id = stableId(feedUrl),
            title = result.collectionName ?: return null,
            author = result.artistName ?: "",
            feedUrl = feedUrl,
            artworkUrl = result.artworkUrl600,
            description = null,
        )
    }
}

// --- PodcastIndex (free key; real week/month trending windows) ---

@Serializable
private data class PodcastIndexTrendingResponse(val feeds: List<PodcastIndexFeed> = emptyList())

@Serializable
internal data class PodcastIndexEpisodesResponse(val items: List<PodcastIndexEpisode> = emptyList())

/** An episode as PodcastIndex indexed it; retained even after it rolls off the feed. */
@Serializable
data class PodcastIndexEpisode(
    val title: String? = null,
    val description: String? = null,
    val guid: String? = null,
    val datePublished: Long = 0, // unix seconds
    val enclosureUrl: String? = null,
    val duration: Long? = null, // seconds
    val image: String? = null,
)

@Serializable
private data class PodcastIndexFeed(
    val url: String? = null,
    val title: String? = null,
    val author: String? = null,
    val image: String? = null,
    val artwork: String? = null,
    val description: String? = null,
)

class PodcastIndexApi {
    suspend fun trending(key: String, secret: String, period: TrendingPeriod): List<TrendingPodcast> {
        val seconds = when (period) {
            TrendingPeriod.WEEK -> 7L * 24 * 3600
            TrendingPeriod.MONTH -> 30L * 24 * 3600
            TrendingPeriod.NOW -> 24L * 3600
        }
        val since = System.currentTimeMillis() / 1000 - seconds
        val timestamp = System.currentTimeMillis() / 1000
        val body = Http.get(
            "https://api.podcastindex.org/api/1.0/podcasts/trending?max=50&since=$since",
            headers = mapOf(
                "X-Auth-Key" to key,
                "X-Auth-Date" to timestamp.toString(),
                "Authorization" to authHash(key, secret, timestamp),
            ),
        )
        return Http.json.decodeFromString<PodcastIndexTrendingResponse>(body).feeds.mapNotNull { feed ->
            TrendingPodcast(
                title = feed.title ?: return@mapNotNull null,
                author = feed.author ?: "",
                artworkUrl = feed.artwork ?: feed.image,
                feedUrl = feed.url ?: return@mapNotNull null,
                appleId = null,
            )
        }
    }

    /**
     * Episodes PodcastIndex has indexed for a feed — unlike the live RSS, this
     * includes episodes that have rolled off a short feed window.
     */
    suspend fun episodesByFeedUrl(
        key: String,
        secret: String,
        feedUrl: String,
        max: Int = 1000,
    ): List<PodcastIndexEpisode> {
        val timestamp = System.currentTimeMillis() / 1000
        val encoded = URLEncoder.encode(feedUrl, "UTF-8")
        val body = Http.get(
            "https://api.podcastindex.org/api/1.0/episodes/byfeedurl?url=$encoded&max=$max&fulltext",
            headers = mapOf(
                "X-Auth-Key" to key,
                "X-Auth-Date" to timestamp.toString(),
                "Authorization" to authHash(key, secret, timestamp),
            ),
        )
        return Http.json.decodeFromString<PodcastIndexEpisodesResponse>(body).items
    }

    companion object {
        /** PodcastIndex auth: sha1(key + secret + unixTime) as lowercase hex. */
        fun authHash(key: String, secret: String, unixTime: Long): String =
            MessageDigest.getInstance("SHA-1")
                .digest((key + secret + unixTime).toByteArray())
                .joinToString("") { "%02x".format(it) }
    }
}
