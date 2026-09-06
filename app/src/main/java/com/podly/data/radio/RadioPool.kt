package com.podly.data.radio

import kotlinx.serialization.Serializable

/**
 * The wire format of the generated pools published to GitHub Pages by
 * `tools/radio`.
 *
 * Entries are fully resolved on purpose: every one carries enough to insert a
 * podcast row and an episode row with no network call, so starting radio never
 * waits on a feed fetch or a fuzzy title match. `docs/radio-pool.schema.json`
 * is the normative definition and `RadioPoolContractTest` guards this against
 * a real generated file.
 */
@Serializable
data class RadioPoolFile(
    val profileId: String,
    val profileLabel: String = "",
    val generatedAtMs: Long = 0,
    val entries: List<RadioPoolEntry> = emptyList(),
    val version: Int = 1,
    val sources: List<String> = emptyList(),
)

@Serializable
data class RadioPoolEntry(
    val id: String,
    val podcast: RadioPoolPodcast,
    val episode: RadioPoolEpisode,
    val rank: Int = 0,
    val score: Float = 0f,
    val language: String? = null,
    val why: String? = null,
    val tags: List<String> = emptyList(),
)

@Serializable
data class RadioPoolPodcast(
    val id: String,
    val title: String,
    val author: String = "",
    val feedUrl: String,
    val artworkUrl: String? = null,
    val description: String? = null,
    val language: String? = null,
    val appleId: String? = null,
)

@Serializable
data class RadioPoolEpisode(
    val id: String,
    val title: String,
    val audioUrl: String,
    val pubDateMs: Long,
    val guid: String? = null,
    val description: String? = null,
    val audioMimeType: String? = null,
    val durationMs: Long? = null,
    val artworkUrl: String? = null,
)

/** The list of pools available, so a new profile needs no app release. */
@Serializable
data class RadioIndexFile(
    val version: Int = 1,
    val profiles: List<RadioIndexProfile> = emptyList(),
)

@Serializable
data class RadioIndexProfile(
    val id: String,
    val label: String = "",
    val file: String = "",
    val schemaVersion: Int = 1,
    val entryCount: Int = 0,
    val generatedAtMs: Long = 0,
)
