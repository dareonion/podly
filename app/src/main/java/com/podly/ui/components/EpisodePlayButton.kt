package com.podly.ui.components

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.platform.LocalContext
import com.podly.appGraph

/**
 * A row's play button. On the episode already loaded it pauses and resumes, and
 * shows Pause while that episode plays; on any other episode it calls [onPlay].
 *
 * Every list's play action rebuilds the queue (or starts a radio session), so
 * pressing it on the loaded episode would throw away what is queued after it.
 * Reading the player here, the way MiniPlayer does, gives every list this
 * without threading playback state through each screen.
 */
@Composable
fun EpisodePlayButton(episodeId: String, onPlay: () -> Unit) {
    val player = LocalContext.current.appGraph.player
    val playback by player.state.collectAsState()
    val isCurrent = playback.episodeId == episodeId
    // Buffering towards playback counts as playing: the button must not flick back
    // to "play" in the second between the tap and the first audio. Buffering
    // while paused (a seek) does not; togglePlayPause draws the same line.
    val playing = isCurrent &&
        (playback.isPlaying || (playback.isBuffering && playback.playWhenReady))
    IconButton(onClick = { if (isCurrent) player.togglePlayPause() else onPlay() }) {
        Icon(
            if (playing) Icons.Filled.Pause else Icons.Filled.PlayArrow,
            if (playing) "Pause" else "Play",
        )
    }
}
