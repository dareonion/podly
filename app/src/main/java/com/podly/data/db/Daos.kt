package com.podly.data.db

import androidx.room.Dao
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

    @Query(
        """DELETE FROM podcasts WHERE subscribed = 0
           AND id NOT IN (SELECT DISTINCT podcastId FROM episodes
                          WHERE inLibrary = 1 OR downloadStatus != 'NONE' OR playbackPositionMs > 0)"""
    )
    suspend fun pruneOrphans()
}

@Dao
interface EpisodeDao {
    @Query("SELECT * FROM episodes WHERE podcastId = :podcastId ORDER BY pubDateMs DESC")
    fun episodesForPodcast(podcastId: String): Flow<List<EpisodeEntity>>

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

    @Query("SELECT * FROM episodes WHERE id = :id")
    suspend fun byId(id: String): EpisodeEntity?

    @Query("SELECT * FROM episodes WHERE id = :id")
    fun byIdFlow(id: String): Flow<EpisodeEntity?>

    @Query("SELECT * FROM episodes WHERE playbackPositionMs > 0 AND completed = 0 ORDER BY pubDateMs DESC LIMIT :limit")
    suspend fun recentlyPlayed(limit: Int): List<EpisodeEntity>

    /** Refresh inserts must never clobber library/download/progress state. */
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertIgnore(episodes: List<EpisodeEntity>)

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertIgnore(episode: EpisodeEntity)

    @Query("UPDATE episodes SET inLibrary = :inLibrary WHERE id = :id")
    suspend fun setInLibrary(id: String, inLibrary: Boolean)

    @Query("UPDATE episodes SET playbackPositionMs = :positionMs, completed = :completed WHERE id = :id")
    suspend fun updateProgress(id: String, positionMs: Long, completed: Boolean)

    @Query("UPDATE episodes SET downloadStatus = :status, localFilePath = :localFilePath WHERE id = :id")
    suspend fun updateDownload(id: String, status: DownloadStatus, localFilePath: String?)

    @Query("UPDATE episodes SET durationMs = :durationMs WHERE id = :id")
    suspend fun updateDuration(id: String, durationMs: Long)
}

@Dao
interface PlaylistDao {
    @Query("SELECT * FROM playlists ORDER BY createdAt")
    fun playlists(): Flow<List<PlaylistEntity>>

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
