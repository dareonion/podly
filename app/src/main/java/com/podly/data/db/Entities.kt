package com.podly.data.db

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import java.security.MessageDigest

enum class DownloadStatus { NONE, QUEUED, DOWNLOADING, DONE, FAILED }

/** True when the episode has no usable or in-flight download. */
val DownloadStatus.needsDownload: Boolean
    get() = this == DownloadStatus.NONE || this == DownloadStatus.FAILED

enum class SortMode { MANUAL, CHRONO_ASC, CHRONO_DESC }

enum class PodcastEpisodeSortOrder { NEWEST_FIRST, OLDEST_FIRST }

/** Stable, filesystem/mediaId-safe identifier derived from a feed URL or episode guid. */
fun stableId(raw: String): String =
    MessageDigest.getInstance("SHA-256")
        .digest(raw.toByteArray())
        .joinToString("") { "%02x".format(it) }
        .take(32)

@Entity(
    tableName = "podcasts",
    indices = [Index("subscribed")],
)
data class PodcastEntity(
    @PrimaryKey val id: String,
    val title: String,
    val author: String,
    val feedUrl: String,
    val artworkUrl: String?,
    val description: String?,
    val subscribed: Boolean = false,
    val addedAt: Long = System.currentTimeMillis(),
    val episodeSortOrder: PodcastEpisodeSortOrder = PodcastEpisodeSortOrder.NEWEST_FIRST,
    /** HTTP cache validators from the last feed fetch; enable 304 short-circuits. */
    val etag: String? = null,
    val lastModified: String? = null,
)

@Entity(
    tableName = "episodes",
    indices = [
        Index("podcastId"),
        Index("pubDateMs"),
        Index("inLibrary"),
        Index("downloadStatus"),
        Index("lastPlayedAt"),
    ],
)
data class EpisodeEntity(
    @PrimaryKey val id: String,
    val podcastId: String,
    val podcastTitle: String,
    val guid: String?,
    val title: String,
    val description: String?,
    val audioUrl: String,
    val pubDateMs: Long,
    val durationMs: Long?,
    val artworkUrl: String?,
    val inLibrary: Boolean = false,
    val downloadStatus: DownloadStatus = DownloadStatus.NONE,
    val localFilePath: String? = null,
    /** Set when the user removes a download, so auto-download won't re-fetch it. */
    val autoDownloadBlocked: Boolean = false,
    val playbackPositionMs: Long = 0,
    val completed: Boolean = false,
    val lastPlayedAt: Long = 0,
    val userNote: String? = null,
    val userRating: Int? = null,
)

@Entity(
    tableName = "listening_segments",
    indices = [Index("episodeId"), Index("endedAt")],
)
data class ListeningSegmentEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val episodeId: String,
    val startPositionMs: Long,
    val endPositionMs: Long,
    val startedAt: Long,
    val endedAt: Long,
    /**
     * Which radio profile was listening, or null for ordinary listening. Tagged from
     * the session that started playback, never from "whichever profile is selected
     * right now" — otherwise a manually tapped episode would be filed under whoever
     * the chip happened to name.
     */
    val profileId: String? = null,
)

data class EpisodeHistorySummary(
    val id: String,
    val podcastTitle: String,
    val title: String,
    val artworkUrl: String?,
    val durationMs: Long?,
    val completed: Boolean,
    val userNote: String?,
    val userRating: Int?,
    val segmentCount: Int,
    val firstListenedAt: Long,
    val lastListenedAt: Long,
    val totalListenedMs: Long,
)

/** Where a radio candidate came from. */
enum class RadioSource { CATALOG, MANUAL }

/**
 * Discovery candidates for one profile: episodes radio may play that the user has not
 * subscribed to. Backlog candidates deliberately get no rows here — they are queried
 * from [EpisodeEntity] directly, so `completed` has exactly one source of truth.
 */
@Entity(
    tableName = "radio_pool",
    primaryKeys = ["profileId", "episodeId"],
    indices = [Index("episodeId")],
)
data class RadioPoolEntity(
    val profileId: String,
    val episodeId: String,
    val source: RadioSource = RadioSource.CATALOG,
    /** One line on why this was picked, shown while it plays. */
    val reason: String? = null,
    /** BCP-47 primary subtag from the catalog ("en", "zh"); null when unknown. */
    val language: String? = null,
    /** The generator's prior, 0..1. */
    val priority: Float = 0f,
    /** Catalog generation that inserted this row; older generations are pruned. */
    val catalogVersion: Long = 0,
    val addedAt: Long = System.currentTimeMillis(),
    /** Time-limited picks; null means no expiry. */
    val expiresAt: Long? = null,
)

/**
 * Serve/skip bookkeeping, per profile. Covers backlog episodes as well as pool ones,
 * which is why it is a separate table rather than columns on [RadioPoolEntity].
 */
@Entity(
    tableName = "radio_feedback",
    primaryKeys = ["profileId", "episodeId"],
)
data class RadioFeedbackEntity(
    val profileId: String,
    val episodeId: String,
    val lastServedAt: Long = 0,
    val serveCount: Int = 0,
    val lastSkippedAt: Long = 0,
    val skipCount: Int = 0,
    /** Time listened under this profile, for the affinity signal. */
    val listenedMs: Long = 0,
    /** Soft-cooldown expiry, computed at write time. 0 means free to serve. */
    val blockedUntil: Long = 0,
)

@Entity(tableName = "playlists")
data class PlaylistEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val sortMode: SortMode = SortMode.MANUAL,
    val createdAt: Long = System.currentTimeMillis(),
)

@Entity(
    tableName = "playlist_items",
    primaryKeys = ["playlistId", "episodeId"],
    indices = [Index("episodeId")],
)
data class PlaylistItemEntity(
    val playlistId: Long,
    val episodeId: String,
    val manualPosition: Int,
)
