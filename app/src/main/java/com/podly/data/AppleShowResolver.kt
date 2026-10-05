package com.podly.data

import android.util.Log
import com.podly.data.db.PodcastEntity
import com.podly.data.db.stableId
import com.podly.network.AppleListing
import com.podly.network.DirectoryFeed
import com.podly.network.PodcastIndexApi
import com.podly.network.TaddyApi
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.first

private const val TAG = "AppleShowResolver"

/** What looking up an Apple Podcasts id came to. */
sealed interface AppleShow {
    data class Found(val podcast: PodcastEntity) : AppleShow

    /**
     * Apple lists the show but publishes no feed for it, and none of the
     * directories in [asked] had one either (empty when none is configured).
     */
    data class FeedHidden(val title: String, val asked: List<String>) : AppleShow

    data object NotFound : AppleShow
}

/** The sentence to show when a lookup found nothing to subscribe to; null when it did. */
fun AppleShow.problem(): String? = when (this) {
    is AppleShow.Found -> null
    AppleShow.NotFound -> "Apple doesn't list this podcast."
    is AppleShow.FeedHidden -> if (asked.isEmpty()) {
        "Apple lists “$title” but doesn't share its feed. Paste the show's RSS feed link " +
            "instead, or add a PodcastIndex or Taddy key in Settings so Podly can look it up there."
    } else {
        "Apple lists “$title” but doesn't share its feed, and ${asked.joinToString(" and ")} " +
            "couldn't find it either. Paste the show's RSS feed link instead."
    }
}

/** Another directory that may have the feed for a show Apple knows by id. */
interface FeedDirectory {
    val name: String

    /** False when the user has not entered credentials for it. */
    suspend fun isConfigured(): Boolean

    /** The feed, or null when it has none or the lookup failed (logged). */
    suspend fun feedForAppleId(appleId: String): DirectoryFeed?
}

/**
 * Turns an Apple Podcasts id (a pasted link, an Apple chart entry) into something
 * to subscribe to.
 *
 * Apple's lookup comes first, but some publishers hide their feed from Apple's
 * directory while still listing the show there, and the lookup then returns
 * everything except the feed. Only then are the configured directories asked, by
 * the same id. Not for an id Apple doesn't list: asked for id 1, which is no
 * Apple show, PodcastIndex and Taddy both answered with "Closing the Loop", a
 * feed that claims that id for itself (checked 2026-10-04).
 */
class AppleShowResolver(
    private val lookUpApple: suspend (String) -> AppleListing?,
    private val directories: List<FeedDirectory>,
    private val log: (String) -> Unit = { Log.i(TAG, it) },
) {
    suspend fun resolve(appleId: String): AppleShow {
        val listing = lookUpApple(appleId) ?: return AppleShow.NotFound
        listing.feedUrl?.let { return AppleShow.Found(podcast(it, listing)) }
        val asked = mutableListOf<String>()
        for (directory in directories) {
            if (!directory.isConfigured()) continue
            asked += directory.name
            val found = directory.feedForAppleId(appleId) ?: continue
            log(
                "${directory.name} has the feed Apple withholds for $appleId " +
                    "(\"${listing.title}\", it says \"${found.title}\"): ${found.feedUrl}",
            )
            return AppleShow.Found(podcast(found.feedUrl, listing))
        }
        return AppleShow.FeedHidden(listing.title, asked)
    }

    /** Apple's title and artwork; only the feed comes from elsewhere. */
    private fun podcast(feedUrl: String, listing: AppleListing) = PodcastEntity(
        id = stableId(feedUrl),
        title = listing.title,
        author = listing.author,
        feedUrl = feedUrl,
        artworkUrl = listing.artworkUrl,
        description = null,
    )
}

/** PodcastIndex, which has an endpoint for exactly this lookup; asked first. */
class PodcastIndexFeedDirectory(
    private val api: PodcastIndexApi,
    private val settings: SettingsRepository,
) : FeedDirectory {
    override val name = "PodcastIndex"

    override suspend fun isConfigured(): Boolean = settings.settings.first().let {
        it.podcastIndexKey.isNotBlank() && it.podcastIndexSecret.isNotBlank()
    }

    override suspend fun feedForAppleId(appleId: String): DirectoryFeed? {
        val s = settings.settings.first()
        return logFailure(name, appleId) {
            api.feedByItunesId(s.podcastIndexKey.trim(), s.podcastIndexSecret.trim(), appleId)
        }
    }
}

class TaddyFeedDirectory(
    private val api: TaddyApi,
    private val settings: SettingsRepository,
) : FeedDirectory {
    override val name = "Taddy"

    override suspend fun isConfigured(): Boolean = settings.settings.first().let {
        it.taddyUserId.isNotBlank() && it.taddyApiKey.isNotBlank()
    }

    override suspend fun feedForAppleId(appleId: String): DirectoryFeed? {
        val s = settings.settings.first()
        val id = appleId.toLongOrNull() ?: return null
        return logFailure(name, appleId) {
            api.feedByItunesId(s.taddyUserId.trim(), s.taddyApiKey.trim(), id)
        }
    }
}

/** A failed directory is a miss, not an error: there may be another to ask. */
private suspend fun logFailure(
    directory: String,
    appleId: String,
    block: suspend () -> DirectoryFeed?,
): DirectoryFeed? = try {
    block().also { if (it == null) Log.i(TAG, "$directory has no feed for $appleId") }
} catch (e: CancellationException) {
    throw e
} catch (e: Exception) {
    Log.w(TAG, "$directory lookup failed for $appleId: ${e.message}")
    null
}
