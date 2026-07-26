package com.podly.data

import android.util.Log
import com.podly.network.PodcastIndexApi
import com.podly.network.TaddyApi
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
    suspend fun episodesByFeedUrl(feedUrl: String): List<ArchiveEpisode>?
}

/** Taddy provider (GraphQL). Configured via a user id + API key from taddy.org. */
class TaddyArchive(
    private val api: TaddyApi,
    private val settings: SettingsRepository,
) : EpisodeArchive {
    override val name = "Taddy"

    override suspend fun episodesByFeedUrl(feedUrl: String): List<ArchiveEpisode>? {
        val s = settings.settings.first()
        if (s.taddyUserId.isBlank() || s.taddyApiKey.isBlank()) {
            Log.i(TAG, "Taddy skipped for $feedUrl: no creds configured")
            return null
        }
        return runCatching { api.episodesByFeedUrl(s.taddyUserId, s.taddyApiKey, feedUrl) }
            .onSuccess { Log.i(TAG, "Taddy returned ${it.size} episodes for $feedUrl") }
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
}

/** PodcastIndex provider. Configured via a key + secret from api.podcastindex.org. */
class PodcastIndexArchive(
    private val api: PodcastIndexApi,
    private val settings: SettingsRepository,
) : EpisodeArchive {
    override val name = "PodcastIndex"

    override suspend fun episodesByFeedUrl(feedUrl: String): List<ArchiveEpisode>? {
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
