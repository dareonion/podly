package com.podly.data

import com.podly.data.db.PlaylistSummary
import com.podly.data.db.EpisodeEntity
import com.podly.data.db.PlaylistDao
import com.podly.data.db.PlaylistEntity
import com.podly.data.db.PlaylistShow
import com.podly.data.db.PlaylistShowEntity
import com.podly.data.db.SortMode
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map

class PlaylistRepository(private val playlistDao: PlaylistDao) {

    fun playlists(): Flow<List<PlaylistEntity>> = playlistDao.playlists()

    fun playlistSummaries(): Flow<List<PlaylistSummary>> = playlistDao.playlistSummaries()
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

    suspend fun addEpisode(playlistId: Long, episodeId: String, note: String? = null) =
        playlistDao.addToEnd(playlistId, episodeId, note?.trim()?.ifEmpty { null })

    /** Shows the playlist recommends as a whole, in order. */
    fun shows(playlistId: Long): Flow<List<PlaylistShow>> = playlistDao.shows(playlistId)

    suspend fun addShows(playlistId: Long, shows: List<Pair<String, String?>>) =
        shows.forEachIndexed { position, (podcastId, note) ->
            playlistDao.insertShow(PlaylistShowEntity(playlistId, podcastId, position, note?.trim()?.ifEmpty { null }))
        }

    /** Episode id -> its note, for the rows that have one. */
    fun notes(playlistId: Long): Flow<Map<String, String>> =
        playlistDao.notes(playlistId).map { rows -> rows.associate { it.episodeId to it.note } }

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
