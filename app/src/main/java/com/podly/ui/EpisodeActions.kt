package com.podly.ui

import com.podly.AppGraph
import com.podly.data.db.EpisodeEntity
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/** Episode-row actions shared by every screen that lists episodes. */
class EpisodeActions(private val graph: AppGraph, private val scope: CoroutineScope) {

    /** Queues [episodes] into the player and starts at [index]. */
    fun play(episodes: List<EpisodeEntity>, index: Int) {
        graph.player.play(episodes.map { it.id }, index)
    }

    fun toggleLibrary(episode: EpisodeEntity) = launchReporting("Library update") {
        graph.podcasts.setEpisodeInLibrary(episode.id, !episode.inLibrary)
    }

    fun togglePlayed(episode: EpisodeEntity) = launchReporting("Marking played") {
        graph.podcasts.setEpisodePlayed(episode.id, !episode.completed)
    }

    fun download(episode: EpisodeEntity) = launchReporting("Download") {
        graph.downloader.enqueue(episode.id)
    }

    fun removeDownload(episode: EpisodeEntity) = launchReporting("Removing download") {
        graph.downloader.remove(episode.id)
    }

    fun addToPlaylist(playlistId: Long, episodeId: String) = launchReporting("Adding to playlist") {
        graph.playlists.addEpisode(playlistId, episodeId)
    }

    fun createPlaylistAndAdd(name: String, episodeId: String) = launchReporting("Creating playlist") {
        val playlistId = graph.playlists.create(name)
        graph.playlists.addEpisode(playlistId, episodeId)
    }

    /** Fire-and-forget, but failures surface as a snackbar instead of vanishing. */
    private fun launchReporting(action: String, block: suspend () -> Unit) = scope.launch {
        try {
            block()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            graph.messages.post("$action failed: ${e.message ?: e.toString()}")
        }
    }
}
