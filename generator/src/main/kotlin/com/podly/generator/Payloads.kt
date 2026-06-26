package com.podly.generator

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * The wire contract for the static JSON files published to GitHub Pages.
 *
 * These classes MUST stay field-for-field compatible with the app's
 * `CachedRecentEpisodes` / `CachedAcclaimed` (and the picks they nest):
 * the app deserializes these files straight into those classes. The app has a
 * round-trip fixture test that fails loudly if the field names drift.
 */

/** Mirrors the app's `AiRecentEpisodePick`. */
@Serializable
data class RecentEpisodePick(
    val podcastTitle: String,
    val episodeTitle: String,
    val author: String? = null,
    val reason: String,
    val publishedApprox: String? = null,
)

/** Mirrors the app's `AiAcclaimedPick`. */
@Serializable
data class AcclaimedItem(
    val podcastTitle: String,
    val episodeTitle: String? = null,
    val author: String? = null,
    val accolade: String,
)

/** Mirrors the app's `CachedRecentEpisodePick` (pick + the iTunes-resolved podcast). */
@Serializable
data class ResolvedRecentPick(
    val pick: RecentEpisodePick,
    val podcastId: String? = null,
    val podcastTitle: String? = null,
    val podcastAuthor: String? = null,
    val feedUrl: String? = null,
    val artworkUrl: String? = null,
    val podcastDescription: String? = null,
)

/** Mirrors the app's `CachedAcclaimedPick`. */
@Serializable
data class ResolvedAcclaimedPick(
    val pick: AcclaimedItem,
    val podcastId: String? = null,
    val podcastTitle: String? = null,
    val podcastAuthor: String? = null,
    val feedUrl: String? = null,
    val artworkUrl: String? = null,
    val podcastDescription: String? = null,
)

/** Mirrors the app's `CachedRecentEpisodes` (minus the app-only `fetchedAtMs`). */
@Serializable
data class RecentFile(
    val version: Int = 1,
    val generatedAtMs: Long,
    val window: String,
    val coverageStart: String,
    val coverageEnd: String,
    val picks: List<ResolvedRecentPick>,
)

/** Mirrors the app's `CachedAcclaimed` (minus the app-only `fetchedAtMs`). */
@Serializable
data class AcclaimedFile(
    val version: Int = 1,
    val generatedAtMs: Long,
    val coverageLabel: String,
    val picks: List<ResolvedAcclaimedPick>,
)

/** One JSON instance for both reading existing files and writing new ones. */
val json: Json = Json {
    ignoreUnknownKeys = true
    isLenient = true
    explicitNulls = false
    prettyPrint = true
}
