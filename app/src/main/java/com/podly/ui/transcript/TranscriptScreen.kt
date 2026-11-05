package com.podly.ui.transcript

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.repeatOnLifecycle
import androidx.lifecycle.viewModelScope
import com.podly.AppGraph
import com.podly.data.TranscriptCueLookup
import com.podly.data.TranscriptRepository
import com.podly.network.TranscriptCue
import com.podly.ui.appViewModel
import com.podly.ui.util.formatPosition
import com.podly.ui.util.friendlyError
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

class TranscriptViewModel(
    private val graph: AppGraph,
    private val episodeId: String,
) : ViewModel() {
    val player = graph.player
    private val _state = MutableStateFlow<TranscriptRepository.Result?>(null)
    val state: StateFlow<TranscriptRepository.Result?> = _state

    init {
        viewModelScope.launch { _state.value = graph.transcripts.transcript(episodeId) }
    }

    /** Reading another episode must never start playback or seek the loaded one. */
    fun seekTo(positionMs: Long) {
        if (player.state.value.episodeId == episodeId) player.seekTo(positionMs)
    }
}

@Composable
fun TranscriptScreen(episodeId: String, onBack: () -> Unit) {
    val viewModel = appViewModel { TranscriptViewModel(it, episodeId) }
    val result by viewModel.state.collectAsStateWithLifecycle()
    val playerState by viewModel.player.state.collectAsStateWithLifecycle()
    val loaded = playerState.episodeId == episodeId
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    var position by remember(episodeId) { mutableLongStateOf(0L) }
    LaunchedEffect(loaded, lifecycle) {
        if (loaded) {
            lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
                while (true) {
                    position = viewModel.player.positionMs()
                    delay(500)
                }
            }
        }
    }

    Column(modifier = Modifier.fillMaxSize()) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(end = 16.dp, top = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") }
            Text("Transcript", style = MaterialTheme.typography.titleMedium)
        }

        when (val state = result) {
            null -> Column(
                modifier = Modifier.fillMaxSize().padding(24.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) { CircularProgressIndicator() }
            is TranscriptRepository.Result.None -> Message("No transcript is available for this episode.")
            is TranscriptRepository.Result.Failed ->
                Message("Couldn't load the transcript: ${friendlyError(state.error)}")
            is TranscriptRepository.Result.Ready -> if (state.cues.isEmpty()) {
                Message("The transcript file was empty.")
            } else {
                TranscriptLines(state.cues, loaded, position, viewModel::seekTo)
            }
        }
    }
}

@Composable
private fun TranscriptLines(cues: List<TranscriptCue>, loaded: Boolean, position: Long, seekTo: (Long) -> Unit) {
    val lookup = remember(cues) { TranscriptCueLookup(cues) }
    val active = if (loaded) lookup.at(position) else -1
    val listState = rememberLazyListState()
    var following by remember(cues) { mutableStateOf(true) }
    val manualScroll = remember(cues) {
        object : NestedScrollConnection {
            override fun onPreScroll(available: Offset, source: NestedScrollSource): Offset {
                // Programmatic scrolls are SideEffect; a drag/wheel is UserInput.
                if (source == NestedScrollSource.UserInput && available.y != 0f) following = false
                return Offset.Zero
            }
        }
    }
    LaunchedEffect(active, following) {
        if (following && active >= 0) {
            val layout = listState.layoutInfo
            val item = layout.visibleItemsInfo.firstOrNull { it.index == active }
            if (item == null || item.offset < layout.viewportStartOffset ||
                item.offset + item.size > layout.viewportEndOffset
            ) {
                listState.animateScrollToItem(active)
            }
        }
    }
    if (loaded && !following) {
        TextButton(onClick = { following = true }) { Text("Follow") }
    }
    LazyColumn(
        state = listState,
        modifier = Modifier.fillMaxSize().nestedScroll(manualScroll),
        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 32.dp),
    ) {
        itemsIndexed(cues) { index, cue ->
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(if (index == active) MaterialTheme.colorScheme.secondaryContainer else Color.Transparent)
                    .then(
                        if (loaded && cue.startMs != null) {
                            Modifier.clickable { seekTo(cue.startMs) }
                        } else Modifier,
                    )
                    .padding(vertical = 6.dp),
            ) {
                val heading = listOfNotNull(cue.speaker, cue.startMs?.let { formatPosition(it) }).joinToString("  ")
                if (heading.isNotEmpty()) {
                    Text(
                        heading,
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.Medium,
                        color = MaterialTheme.colorScheme.primary,
                    )
                }
                Text(cue.text, style = MaterialTheme.typography.bodyMedium)
            }
        }
    }
}

@Composable
private fun Message(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(16.dp),
    )
}
