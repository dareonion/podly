package com.podly.ui

import com.podly.AppGraph
import com.podly.data.db.EpisodeEntity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/** Episode-row actions shared by every screen that lists episodes. */
class EpisodeActions(private val graph: AppGraph, private val scope: CoroutineScope) {

    /** Queues [episodes] into the player and starts at [index]. */
    fun play(episodes: List<EpisodeEntity>, index: Int) {
        graph.player.play(episodes.map { it.id }, index)
    }

    fun toggleLibrary(episode: EpisodeEntity) = scope.launch {
        graph.podcasts.setEpisodeInLibrary(episode.id, !episode.inLibrary)
    }

    fun download(episode: EpisodeEntity) = scope.launch {
        graph.downloader.enqueue(episode.id)
    }

    fun removeDownload(episode: EpisodeEntity) = scope.launch {
        graph.downloader.remove(episode.id)
    }

    fun addToPlaylist(playlistId: Long, episodeId: String) = scope.launch {
        graph.playlists.addEpisode(playlistId, episodeId)
    }

    fun createPlaylistAndAdd(name: String, episodeId: String) = scope.launch {
        val playlistId = graph.playlists.create(name)
        graph.playlists.addEpisode(playlistId, episodeId)
    }
}
