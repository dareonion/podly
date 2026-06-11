package com.podly.data.db

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import java.security.MessageDigest

enum class DownloadStatus { NONE, QUEUED, DOWNLOADING, DONE, FAILED }

enum class SortMode { MANUAL, CHRONO_ASC, CHRONO_DESC }

/** Stable, filesystem/mediaId-safe identifier derived from a feed URL or episode guid. */
fun stableId(raw: String): String =
    MessageDigest.getInstance("SHA-256")
        .digest(raw.toByteArray())
        .joinToString("") { "%02x".format(it) }
        .take(32)

@Entity(tableName = "podcasts")
data class PodcastEntity(
    @PrimaryKey val id: String,
    val title: String,
    val author: String,
    val feedUrl: String,
    val artworkUrl: String?,
    val description: String?,
    val subscribed: Boolean = false,
    val addedAt: Long = System.currentTimeMillis(),
)

@Entity(
    tableName = "episodes",
    indices = [Index("podcastId"), Index("pubDateMs")],
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
    val playbackPositionMs: Long = 0,
    val completed: Boolean = false,
    val lastPlayedAt: Long = 0,
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
