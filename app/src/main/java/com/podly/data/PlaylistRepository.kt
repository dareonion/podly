package com.podly.data

import com.podly.data.db.EpisodeEntity
import com.podly.data.db.PlaylistDao
import com.podly.data.db.PlaylistEntity
import com.podly.data.db.SortMode
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine

class PlaylistRepository(private val playlistDao: PlaylistDao) {

    fun playlists(): Flow<List<PlaylistEntity>> = playlistDao.playlists()
    fun playlist(id: Long): Flow<PlaylistEntity?> = playlistDao.byIdFlow(id)

    /** Episodes ordered according to the playlist's persisted sort mode. */
    fun sortedEpisodes(playlistId: Long): Flow<List<EpisodeEntity>> =
        combine(
            playlistDao.byIdFlow(playlistId),
            playlistDao.episodesInPlaylist(playlistId),
        ) { playlist, episodes ->
            applySort(playlist?.sortMode ?: SortMode.MANUAL, episodes)
        }

    suspend fun sortedEpisodesOnce(playlistId: Long): List<EpisodeEntity> {
        val playlist = playlistDao.byId(playlistId) ?: return emptyList()
        return applySort(playlist.sortMode, playlistDao.episodesInPlaylistOnce(playlistId))
    }

    suspend fun create(name: String): Long = playlistDao.insert(PlaylistEntity(name = name))

    suspend fun rename(id: Long, name: String) {
        playlistDao.byId(id)?.let { playlistDao.update(it.copy(name = name)) }
    }

    suspend fun setSortMode(id: Long, sortMode: SortMode) {
        playlistDao.byId(id)?.let { playlistDao.update(it.copy(sortMode = sortMode)) }
    }

    suspend fun delete(id: Long) = playlistDao.deletePlaylist(id)

    suspend fun addEpisode(playlistId: Long, episodeId: String) =
        playlistDao.addToEnd(playlistId, episodeId)

    suspend fun removeEpisode(playlistId: Long, episodeId: String) =
        playlistDao.removeItem(playlistId, episodeId)

    /** Persists a new manual order (also switches the playlist to manual mode). */
    suspend fun reorder(playlistId: Long, orderedEpisodeIds: List<String>) {
        playlistDao.reorder(playlistId, orderedEpisodeIds)
    }

    companion object {
        fun applySort(sortMode: SortMode, episodes: List<EpisodeEntity>): List<EpisodeEntity> =
            when (sortMode) {
                SortMode.MANUAL -> episodes // already ordered by manualPosition
                SortMode.CHRONO_ASC -> episodes.sortedBy { it.pubDateMs }
                SortMode.CHRONO_DESC -> episodes.sortedByDescending { it.pubDateMs }
            }
    }
}
