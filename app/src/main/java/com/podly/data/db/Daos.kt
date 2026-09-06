package com.podly.data.db

import androidx.room.Dao
import androidx.room.Embedded
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

@Dao
interface PodcastDao {
    @Query("SELECT * FROM podcasts WHERE subscribed = 1 ORDER BY title COLLATE NOCASE")
    fun subscribedPodcasts(): Flow<List<PodcastEntity>>

    @Query("SELECT * FROM podcasts WHERE subscribed = 1")
    suspend fun subscribedPodcastsOnce(): List<PodcastEntity>

    @Query("SELECT * FROM podcasts WHERE id = :id")
    suspend fun byId(id: String): PodcastEntity?

    @Query("SELECT * FROM podcasts WHERE id = :id")
    fun byIdFlow(id: String): Flow<PodcastEntity?>

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertIgnore(podcast: PodcastEntity)

    @Query(
        """UPDATE podcasts
           SET title = :title,
               author = :author,
               artworkUrl = :artworkUrl,
               description = :description
           WHERE id = :id"""
    )
    suspend fun updateMetadata(
        id: String,
        title: String,
        author: String,
        artworkUrl: String?,
        description: String?,
    )

    @Query("UPDATE podcasts SET subscribed = :subscribed WHERE id = :id")
    suspend fun setSubscribed(id: String, subscribed: Boolean)

    @Query("UPDATE podcasts SET etag = :etag, lastModified = :lastModified WHERE id = :id")
    suspend fun updateCacheValidators(id: String, etag: String?, lastModified: String?)

    @Query("UPDATE podcasts SET episodeSortOrder = :sortOrder WHERE id = :id")
    suspend fun setEpisodeSortOrder(id: String, sortOrder: PodcastEpisodeSortOrder)

    /**
     * Radio pool shows are spared too: without that clause, unsubscribing any show
     * deletes every unsubscribed podcast the pool depends on, leaving its episodes
     * playable but stripped of feedUrl, artwork and author.
     */
    @Query(
        """DELETE FROM podcasts WHERE subscribed = 0
           AND id NOT IN (SELECT DISTINCT podcastId FROM episodes
                          WHERE inLibrary = 1 OR downloadStatus != 'NONE' OR playbackPositionMs > 0)
           AND id NOT IN (SELECT DISTINCT e.podcastId FROM episodes e
                          JOIN radio_pool r ON r.episodeId = e.id)"""
    )
    suspend fun pruneOrphans()
}

@Dao
interface EpisodeDao {
    @Query(
        """SELECT e.* FROM episodes e
           JOIN podcasts p ON p.id = e.podcastId
           WHERE e.podcastId = :podcastId
           ORDER BY
               CASE WHEN p.episodeSortOrder = 'OLDEST_FIRST' THEN e.pubDateMs END ASC,
               CASE WHEN p.episodeSortOrder = 'NEWEST_FIRST' THEN e.pubDateMs END DESC,
               e.id"""
    )
    fun episodesForPodcast(podcastId: String): Flow<List<EpisodeEntity>>

    @Query(
        """SELECT e.* FROM episodes e
           JOIN podcasts p ON p.id = e.podcastId
           WHERE e.podcastId = :podcastId
           ORDER BY
               CASE WHEN p.episodeSortOrder = 'OLDEST_FIRST' THEN e.pubDateMs END ASC,
               CASE WHEN p.episodeSortOrder = 'NEWEST_FIRST' THEN e.pubDateMs END DESC,
               e.id"""
    )
    suspend fun episodesForPodcastOnce(podcastId: String): List<EpisodeEntity>

    @Query(
        """SELECT e.* FROM episodes e JOIN podcasts p ON e.podcastId = p.id
           WHERE e.inLibrary = 1 OR p.subscribed = 1 ORDER BY e.pubDateMs DESC"""
    )
    fun libraryEpisodes(): Flow<List<EpisodeEntity>>

    @Query(
        """SELECT e.* FROM episodes e JOIN podcasts p ON e.podcastId = p.id
           WHERE e.inLibrary = 1 OR p.subscribed = 1 ORDER BY e.pubDateMs DESC"""
    )
    suspend fun libraryEpisodesOnce(): List<EpisodeEntity>

    @Query("SELECT * FROM episodes WHERE downloadStatus = 'DONE' ORDER BY pubDateMs DESC")
    fun downloadedEpisodes(): Flow<List<EpisodeEntity>>

    @Query("SELECT * FROM episodes WHERE downloadStatus = 'DONE' ORDER BY pubDateMs DESC")
    suspend fun downloadedEpisodesOnce(): List<EpisodeEntity>

    @Query("SELECT * FROM episodes WHERE downloadStatus = 'DONE' AND completed = 1")
    suspend fun downloadedCompletedOnce(): List<EpisodeEntity>

    @Query("SELECT * FROM episodes WHERE podcastId = :podcastId ORDER BY pubDateMs DESC LIMIT :limit")
    suspend fun newestEpisodesForPodcast(podcastId: String, limit: Int): List<EpisodeEntity>

    @Query("SELECT * FROM episodes WHERE id = :id")
    suspend fun byId(id: String): EpisodeEntity?

    @Query("SELECT * FROM episodes WHERE id = :id")
    fun byIdFlow(id: String): Flow<EpisodeEntity?>

    @Query(
        """SELECT e.id, e.podcastTitle, e.title, e.artworkUrl, e.durationMs, e.completed,
                  e.userNote, e.userRating,
                  COUNT(s.id) AS segmentCount,
                  MIN(s.startedAt) AS firstListenedAt,
                  MAX(s.endedAt) AS lastListenedAt,
                  SUM(CASE
                      WHEN s.endPositionMs > s.startPositionMs
                      THEN s.endPositionMs - s.startPositionMs
                      ELSE 0
                  END) AS totalListenedMs
           FROM episodes e
           JOIN listening_segments s ON s.episodeId = e.id
           GROUP BY e.id
           ORDER BY lastListenedAt DESC"""
    )
    fun listeningHistory(): Flow<List<EpisodeHistorySummary>>

    @Query("SELECT * FROM listening_segments ORDER BY endedAt DESC")
    fun listeningSegments(): Flow<List<ListeningSegmentEntity>>

    @Query("SELECT * FROM listening_segments WHERE episodeId = :episodeId ORDER BY endedAt DESC")
    fun listeningSegmentsForEpisode(episodeId: String): Flow<List<ListeningSegmentEntity>>

    @Query("SELECT * FROM episodes WHERE lastPlayedAt > 0 ORDER BY lastPlayedAt DESC LIMIT :limit")
    suspend fun recentlyPlayed(limit: Int): List<EpisodeEntity>

    @Query("SELECT * FROM episodes WHERE playbackPositionMs > 0 AND completed = 0 ORDER BY lastPlayedAt DESC LIMIT :limit")
    fun continueListening(limit: Int): Flow<List<EpisodeEntity>>

    @Query("SELECT * FROM episodes WHERE playbackPositionMs > 0 AND completed = 0 ORDER BY lastPlayedAt DESC LIMIT :limit")
    suspend fun continueListeningOnce(limit: Int): List<EpisodeEntity>

    @Query(
        """SELECT * FROM episodes
           WHERE playbackPositionMs > 0 AND completed = 0 AND lastPlayedAt >= :since
             AND downloadStatus = 'NONE' AND autoDownloadBlocked = 0
           ORDER BY lastPlayedAt DESC LIMIT :limit"""
    )
    suspend fun startedUndownloadedOnce(since: Long, limit: Int): List<EpisodeEntity>

    /** Refresh inserts must never clobber library/download/progress state. */
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertIgnore(episodes: List<EpisodeEntity>)

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertIgnore(episode: EpisodeEntity)

    /**
     * Refresh path: applies upstream metadata corrections without touching
     * library/download/progress state. durationMs only fills a missing value
     * (playback measures the real one); pubDateMs is deliberately left alone —
     * undated episodes get a first-seen fallback at insert that must not churn.
     */
    @Query(
        """UPDATE episodes SET podcastTitle = :podcastTitle, title = :title,
           description = COALESCE(:description, description),
           audioUrl = :audioUrl,
           durationMs = COALESCE(durationMs, :durationMs),
           artworkUrl = COALESCE(:artworkUrl, artworkUrl)
           WHERE id = :id"""
    )
    suspend fun updateFeedMetadata(
        id: String,
        podcastTitle: String,
        title: String,
        description: String?,
        audioUrl: String,
        durationMs: Long?,
        artworkUrl: String?,
    )

    /** Inserts new episodes and refreshes feed metadata on existing ones. */
    @Transaction
    suspend fun upsertFromFeed(episodes: List<EpisodeEntity>) {
        insertIgnore(episodes)
        episodes.forEach {
            updateFeedMetadata(
                id = it.id,
                podcastTitle = it.podcastTitle,
                title = it.title,
                description = it.description,
                audioUrl = it.audioUrl,
                durationMs = it.durationMs,
                artworkUrl = it.artworkUrl,
            )
        }
    }

    @Query("UPDATE episodes SET inLibrary = :inLibrary WHERE id = :id")
    suspend fun setInLibrary(id: String, inLibrary: Boolean)

    @Query(
        """UPDATE episodes SET playbackPositionMs = :positionMs, completed = :completed,
           lastPlayedAt = :lastPlayedAt WHERE id = :id"""
    )
    suspend fun updateProgress(id: String, positionMs: Long, completed: Boolean, lastPlayedAt: Long)

    @Query("UPDATE episodes SET completed = :played, playbackPositionMs = 0 WHERE id = :id")
    suspend fun setPlayed(id: String, played: Boolean)

    @Query("UPDATE episodes SET downloadStatus = :status, localFilePath = :localFilePath WHERE id = :id")
    suspend fun updateDownload(id: String, status: DownloadStatus, localFilePath: String?)

    @Query("UPDATE episodes SET autoDownloadBlocked = :blocked WHERE id = :id")
    suspend fun setAutoDownloadBlocked(id: String, blocked: Boolean)

    @Query("UPDATE episodes SET durationMs = :durationMs WHERE id = :id")
    suspend fun updateDuration(id: String, durationMs: Long)

    @Query("UPDATE episodes SET userNote = :note, userRating = :rating WHERE id = :id")
    suspend fun updateUserNoteAndRating(id: String, note: String?, rating: Int?)

    @Insert
    suspend fun insertListeningSegment(segment: ListeningSegmentEntity)
}

/** A playlist plus how many episodes are in it, for the list screen. */
data class PlaylistSummary(
    @Embedded val playlist: PlaylistEntity,
    val episodeCount: Int,
)

@Dao
interface PlaylistDao {
    @Query("SELECT * FROM playlists ORDER BY createdAt")
    fun playlists(): Flow<List<PlaylistEntity>>

    @Query(
        """SELECT p.*, (SELECT COUNT(*) FROM playlist_items i WHERE i.playlistId = p.id)
                  AS episodeCount
           FROM playlists p ORDER BY p.createdAt"""
    )
    fun playlistSummaries(): Flow<List<PlaylistSummary>>

    @Query("SELECT * FROM playlists ORDER BY createdAt")
    suspend fun playlistsOnce(): List<PlaylistEntity>

    @Query("SELECT * FROM playlists WHERE id = :id")
    fun byIdFlow(id: Long): Flow<PlaylistEntity?>

    @Query("SELECT * FROM playlists WHERE id = :id")
    suspend fun byId(id: Long): PlaylistEntity?

    @Insert
    suspend fun insert(playlist: PlaylistEntity): Long

    @Update
    suspend fun update(playlist: PlaylistEntity)

    @Query("DELETE FROM playlists WHERE id = :id")
    suspend fun delete(id: Long)

    @Query("DELETE FROM playlist_items WHERE playlistId = :playlistId")
    suspend fun deleteItems(playlistId: Long)

    @Query(
        """SELECT e.* FROM episodes e JOIN playlist_items pi ON e.id = pi.episodeId
           WHERE pi.playlistId = :playlistId ORDER BY pi.manualPosition"""
    )
    fun episodesInPlaylist(playlistId: Long): Flow<List<EpisodeEntity>>

    @Query(
        """SELECT e.* FROM episodes e JOIN playlist_items pi ON e.id = pi.episodeId
           WHERE pi.playlistId = :playlistId ORDER BY pi.manualPosition"""
    )
    suspend fun episodesInPlaylistOnce(playlistId: Long): List<EpisodeEntity>

    @Query("SELECT * FROM playlist_items WHERE playlistId = :playlistId ORDER BY manualPosition")
    suspend fun items(playlistId: Long): List<PlaylistItemEntity>

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertItem(item: PlaylistItemEntity)

    @Query("DELETE FROM playlist_items WHERE playlistId = :playlistId AND episodeId = :episodeId")
    suspend fun removeItem(playlistId: Long, episodeId: String)

    @Update
    suspend fun updateItems(items: List<PlaylistItemEntity>)

    @Query("SELECT COALESCE(MAX(manualPosition), -1) FROM playlist_items WHERE playlistId = :playlistId")
    suspend fun maxPosition(playlistId: Long): Int

    @Transaction
    suspend fun addToEnd(playlistId: Long, episodeId: String) {
        insertItem(PlaylistItemEntity(playlistId, episodeId, maxPosition(playlistId) + 1))
    }

    @Transaction
    suspend fun reorder(playlistId: Long, orderedEpisodeIds: List<String>) {
        updateItems(orderedEpisodeIds.mapIndexed { index, episodeId ->
            PlaylistItemEntity(playlistId, episodeId, index)
        })
    }

    @Transaction
    suspend fun deletePlaylist(id: Long) {
        deleteItems(id)
        delete(id)
    }
}

/** One radio candidate, joined against its episode row so it is always playable. */
data class RadioCandidateRow(
    val episodeId: String,
    val podcastId: String,
    val podcastTitle: String,
    val title: String,
    val durationMs: Long?,
    val pubDateMs: Long,
    val playbackPositionMs: Long,
    val lastPlayedAt: Long,
    val userRating: Int?,
    /** Null for backlog rows. */
    val language: String?,
    /** 0 for backlog rows. */
    val priority: Float,
    val reason: String?,
    val lastServedAt: Long,
    val serveCount: Int,
    val lastSkippedAt: Long,
    val skipCount: Int,
)

@Dao
interface RadioDao {

    /**
     * Unfinished episodes from shows the user follows, newest first.
     *
     * `:maxDurationMs` / `:minDurationMs` use 0 as "unset" because Room binds
     * non-null primitives; `:restrictShows` is 0/1 for the same reason.
     */
    @Query(
        """SELECT e.id AS episodeId, e.podcastId, e.podcastTitle, e.title, e.durationMs,
                  e.pubDateMs, e.playbackPositionMs, e.lastPlayedAt, e.userRating,
                  NULL AS language, 0.0 AS priority, NULL AS reason,
                  COALESCE(f.lastServedAt, 0) AS lastServedAt,
                  COALESCE(f.serveCount, 0) AS serveCount,
                  COALESCE(f.lastSkippedAt, 0) AS lastSkippedAt,
                  COALESCE(f.skipCount, 0) AS skipCount
           FROM episodes e
           JOIN podcasts p ON p.id = e.podcastId
           LEFT JOIN radio_feedback f ON f.episodeId = e.id AND f.profileId = :profileId
           WHERE e.completed = 0
             AND (:includeUnsubscribed = 1 OR p.subscribed = 1 OR e.inLibrary = 1)
             AND (:restrictShows = 0 OR e.podcastId IN (:allowedPodcastIds))
             AND (:maxDurationMs = 0 OR e.durationMs IS NULL OR e.durationMs <= :maxDurationMs)
             AND (:minDurationMs = 0 OR e.durationMs IS NULL OR e.durationMs >= :minDurationMs)
             AND COALESCE(f.blockedUntil, 0) <= :nowMs
           ORDER BY e.pubDateMs DESC LIMIT :limit"""
    )
    suspend fun backlogRecent(
        profileId: String,
        nowMs: Long,
        includeUnsubscribed: Int,
        restrictShows: Int,
        allowedPodcastIds: List<String>,
        maxDurationMs: Long,
        minDurationMs: Long,
        limit: Int,
    ): List<RadioCandidateRow>

    /** The same slice ordered randomly, so the old backlog is not starved by recency. */
    @Query(
        """SELECT e.id AS episodeId, e.podcastId, e.podcastTitle, e.title, e.durationMs,
                  e.pubDateMs, e.playbackPositionMs, e.lastPlayedAt, e.userRating,
                  NULL AS language, 0.0 AS priority, NULL AS reason,
                  COALESCE(f.lastServedAt, 0) AS lastServedAt,
                  COALESCE(f.serveCount, 0) AS serveCount,
                  COALESCE(f.lastSkippedAt, 0) AS lastSkippedAt,
                  COALESCE(f.skipCount, 0) AS skipCount
           FROM episodes e
           JOIN podcasts p ON p.id = e.podcastId
           LEFT JOIN radio_feedback f ON f.episodeId = e.id AND f.profileId = :profileId
           WHERE e.completed = 0
             AND (:includeUnsubscribed = 1 OR p.subscribed = 1 OR e.inLibrary = 1)
             AND (:restrictShows = 0 OR e.podcastId IN (:allowedPodcastIds))
             AND (:maxDurationMs = 0 OR e.durationMs IS NULL OR e.durationMs <= :maxDurationMs)
             AND (:minDurationMs = 0 OR e.durationMs IS NULL OR e.durationMs >= :minDurationMs)
             AND COALESCE(f.blockedUntil, 0) <= :nowMs
           ORDER BY RANDOM() LIMIT :limit"""
    )
    suspend fun backlogRandom(
        profileId: String,
        nowMs: Long,
        includeUnsubscribed: Int,
        restrictShows: Int,
        allowedPodcastIds: List<String>,
        maxDurationMs: Long,
        minDurationMs: Long,
        limit: Int,
    ): List<RadioCandidateRow>

    /** Pool candidates. The JOIN is the guarantee that every id is playable. */
    @Query(
        """SELECT e.id AS episodeId, e.podcastId, e.podcastTitle, e.title, e.durationMs,
                  e.pubDateMs, e.playbackPositionMs, e.lastPlayedAt, e.userRating,
                  r.language, r.priority, r.reason,
                  COALESCE(f.lastServedAt, 0) AS lastServedAt,
                  COALESCE(f.serveCount, 0) AS serveCount,
                  COALESCE(f.lastSkippedAt, 0) AS lastSkippedAt,
                  COALESCE(f.skipCount, 0) AS skipCount
           FROM radio_pool r
           JOIN episodes e ON e.id = r.episodeId
           LEFT JOIN radio_feedback f ON f.episodeId = r.episodeId AND f.profileId = r.profileId
           WHERE r.profileId = :profileId
             AND e.completed = 0
             AND (r.expiresAt IS NULL OR r.expiresAt > :nowMs)
             AND (:maxDurationMs = 0 OR e.durationMs IS NULL OR e.durationMs <= :maxDurationMs)
             AND COALESCE(f.blockedUntil, 0) <= :nowMs
           ORDER BY r.priority DESC, r.addedAt DESC LIMIT :limit"""
    )
    suspend fun discovery(
        profileId: String,
        nowMs: Long,
        maxDurationMs: Long,
        limit: Int,
    ): List<RadioCandidateRow>

    @Query(
        """SELECT COUNT(*) FROM episodes e
           JOIN podcasts p ON p.id = e.podcastId
           LEFT JOIN radio_feedback f ON f.episodeId = e.id AND f.profileId = :profileId
           WHERE e.completed = 0
             AND (:includeUnsubscribed = 1 OR p.subscribed = 1 OR e.inLibrary = 1)
             AND (:restrictShows = 0 OR e.podcastId IN (:allowedPodcastIds))
             AND (:maxDurationMs = 0 OR e.durationMs IS NULL OR e.durationMs <= :maxDurationMs)
             AND COALESCE(f.blockedUntil, 0) <= :nowMs"""
    )
    fun backlogCount(
        profileId: String,
        nowMs: Long,
        includeUnsubscribed: Int,
        restrictShows: Int,
        allowedPodcastIds: List<String>,
        maxDurationMs: Long,
    ): Flow<Int>

    @Query(
        """SELECT COUNT(*) FROM radio_pool r JOIN episodes e ON e.id = r.episodeId
           WHERE r.profileId = :profileId AND e.completed = 0
             AND (r.expiresAt IS NULL OR r.expiresAt > :nowMs)"""
    )
    fun poolCount(profileId: String, nowMs: Long): Flow<Int>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertPool(rows: List<RadioPoolEntity>)

    @Query("DELETE FROM radio_pool WHERE profileId = :profileId AND catalogVersion < :keepFrom")
    suspend fun pruneOldCatalog(profileId: String, keepFrom: Long)

    /** Drops pool rows whose episode row has gone; they could never be played. */
    @Query("DELETE FROM radio_pool WHERE episodeId NOT IN (SELECT id FROM episodes)")
    suspend fun pruneUnplayablePool()

    @Query("SELECT * FROM radio_feedback WHERE profileId = :profileId AND episodeId = :episodeId")
    suspend fun feedback(profileId: String, episodeId: String): RadioFeedbackEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun putFeedback(row: RadioFeedbackEntity)

    @Query("UPDATE radio_feedback SET blockedUntil = 0, skipCount = 0 WHERE profileId = :profileId")
    suspend fun clearCooldowns(profileId: String)
}
