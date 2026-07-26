package com.podly.data

import android.util.Log
import com.podly.network.PodcastIndexApi
import com.podly.network.TaddyApi
import com.podly.network.TaddySeries
import kotlinx.coroutines.flow.first

private const val TAG = "EpisodeArchive"

/** A podcast episode from an archive provider, normalized across providers. Times in ms. */
data class ArchiveEpisode(
    val title: String?,
    val description: String?,
    val audioUrl: String?,
    val guid: String?,
    val datePublishedMs: Long,
    val durationMs: Long?,
    val imageUrl: String?,
)

/**
 * A source that retains a show's episodes after they roll off its RSS feed. The
 * rescuer tries each configured provider in turn, so implementations return `null`
 * when they can't answer (no creds configured, a network error, or a rate limit) —
 * that signals "skip me, try the next one" rather than "this show has no episodes".
 */
interface EpisodeArchive {
    val name: String

    /** [title] is the show's display title, for providers that can fall back to a name lookup. */
    suspend fun episodesFor(feedUrl: String, title: String): List<ArchiveEpisode>?
}

/** Taddy provider (GraphQL). Configured via a user id + API key from taddy.org. */
class TaddyArchive(
    private val api: TaddyApi,
    private val settings: SettingsRepository,
) : EpisodeArchive {
    override val name = "Taddy"

    override suspend fun episodesFor(feedUrl: String, title: String): List<ArchiveEpisode>? {
        val s = settings.settings.first()
        if (s.taddyUserId.isBlank() || s.taddyApiKey.isBlank()) {
            Log.i(TAG, "Taddy skipped for $feedUrl: no creds configured")
            return null
        }
        return runCatching {
            val series = api.seriesByFeedUrl(s.taddyUserId, s.taddyApiKey, feedUrl)
                ?.also { Log.i(TAG, "Taddy knows $feedUrl as \"${it.name}\" (${it.episodes.size} episodes)") }
                ?: findByName(s.taddyUserId, s.taddyApiKey, title, feedUrl)
            series?.episodes.orEmpty()
        }
            .onFailure { Log.w(TAG, "Taddy lookup failed for $feedUrl: ${it.message}") }
            .getOrNull()
            ?.map {
                ArchiveEpisode(
                    title = it.name,
                    description = it.description,
                    audioUrl = it.audioUrl,
                    guid = it.guid,
                    datePublishedMs = it.datePublished * 1000,
                    durationMs = it.duration?.times(1000),
                    imageUrl = it.imageUrl,
                )
            }
    }

    /**
     * Taddy's rssUrl match is exact-string against the URL it crawled, so a show it
     * knows under a slightly different URL looks missing; retry by name, accepting
     * only a same-titled series so a popular unrelated show can't stand in.
     */
    private suspend fun findByName(
        userId: String,
        apiKey: String,
        title: String,
        feedUrl: String,
    ): TaddySeries? {
        val series = api.seriesByName(userId, apiKey, title)
        if (series == null || !sameTitle(series.name, title)) {
            Log.i(
                TAG,
                "Taddy doesn't know $feedUrl; name lookup for \"$title\" got " +
                    (series?.name?.let { "\"$it\"" } ?: "nothing"),
            )
            return null
        }
        Log.i(
            TAG,
            "Taddy found \"$title\" by name with ${series.episodes.size} episodes " +
                "(its rssUrl=${series.rssUrl})",
        )
        return series
    }

    private fun sameTitle(a: String?, b: String): Boolean {
        fun norm(s: String) = s.lowercase().replace(Regex("[^a-z0-9]"), "")
        return a != null && norm(a) == norm(b)
    }
}

/** PodcastIndex provider. Configured via a key + secret from api.podcastindex.org. */
class PodcastIndexArchive(
    private val api: PodcastIndexApi,
    private val settings: SettingsRepository,
) : EpisodeArchive {
    override val name = "PodcastIndex"

    override suspend fun episodesFor(feedUrl: String, title: String): List<ArchiveEpisode>? {
        val s = settings.settings.first()
        if (s.podcastIndexKey.isBlank() || s.podcastIndexSecret.isBlank()) {
            Log.i(TAG, "PodcastIndex skipped for $feedUrl: no creds configured")
            return null
        }
        return runCatching { api.episodesByFeedUrl(s.podcastIndexKey, s.podcastIndexSecret, feedUrl) }
            .onSuccess { Log.i(TAG, "PodcastIndex returned ${it.size} episodes for $feedUrl") }
            .onFailure { Log.w(TAG, "PodcastIndex lookup failed for $feedUrl: ${it.message}") }
            .getOrNull()
            ?.map {
                ArchiveEpisode(
                    title = it.title,
                    description = it.description,
                    audioUrl = it.enclosureUrl,
                    guid = it.guid,
                    datePublishedMs = it.datePublished * 1000,
                    durationMs = it.duration?.times(1000),
                    imageUrl = it.image,
                )
            }
    }
}
