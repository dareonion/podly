package com.podly.ui.weekly

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.automirrored.filled.PlaylistAdd
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import coil.compose.AsyncImage
import com.podly.AppGraph
import com.podly.data.db.DigestRow
import com.podly.data.db.PlaylistEntity
import com.podly.data.weekly.WeeklyIssue
import com.podly.data.weekly.WeeklyRepository
import com.podly.ui.EpisodeActions
import com.podly.ui.appViewModel
import com.podly.ui.components.AddToPlaylistDialog
import com.podly.ui.components.ErrorNotice
import com.podly.ui.util.friendlyError
import com.podly.ui.util.publishedText
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class WeeklyUiState(
    /** Newest first. Empty until the index has been read, or when it never could be. */
    val issues: List<WeeklyIssue> = emptyList(),
    /** Null shows whatever week the pool worker last synced. */
    val selectedId: String? = null,
    val loading: Boolean = false,
    val error: String? = null,
) {
    val selected: WeeklyIssue? get() = issues.firstOrNull { it.id == selectedId }
    private val index: Int get() = issues.indexOfFirst { it.id == selectedId }
    val older: WeeklyIssue? get() = index.takeIf { it >= 0 }?.let { issues.getOrNull(it + 1) }
    val newer: WeeklyIssue? get() = index.takeIf { it > 0 }?.let { issues.getOrNull(it - 1) }
}

class WeeklyViewModel(private val graph: AppGraph) : ViewModel() {

    private val _state = MutableStateFlow(WeeklyUiState())
    val state: StateFlow<WeeklyUiState> = _state

    val actions = EpisodeActions(graph, viewModelScope)

    val playlists: StateFlow<List<PlaylistEntity>> = graph.playlists.playlists()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    /**
     * The chosen week's picks. Before any index has been read (first open,
     * offline) the week the pool worker synced is shown instead. It is never
     * substituted for a selected week that is still loading, because nothing says
     * which week the synced copy is, and last week's picks under this week's label
     * would be worse than a spinner.
     */
    @OptIn(ExperimentalCoroutinesApi::class)
    val entries: StateFlow<List<DigestRow>> = _state
        .map { it.selectedId }
        .distinctUntilChanged()
        .flatMapLatest { issueId -> graph.weekly.entries(issueId) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    init {
        viewModelScope.launch {
            val cached = graph.weekly.cachedIssues()
            if (cached.isNotEmpty()) {
                _state.update { it.copy(issues = cached, selectedId = cached.first().id) }
            }
            refresh()
        }
    }

    fun refresh() = viewModelScope.launch {
        _state.update { it.copy(loading = true, error = null) }
        report {
            val issues = graph.weekly.issues()
            val selected = _state.value.selectedId?.takeIf { id -> issues.any { it.id == id } }
                ?: issues.firstOrNull()?.id
            _state.update { it.copy(issues = issues, selectedId = selected) }
            issues.firstOrNull { it.id == selected }?.let { graph.weekly.load(it) }
        }
    }

    fun select(issue: WeeklyIssue) = viewModelScope.launch {
        _state.update { it.copy(selectedId = issue.id, loading = true, error = null) }
        report { graph.weekly.load(issue) }
    }

    fun clearError() = _state.update { it.copy(error = null) }

    fun playOne(episodeId: String) = graph.player.playSingle(episodeId)

    /** Queues a whole section, so the digest plays through like a playlist. */
    fun playAll(rows: List<DigestRow>) {
        if (rows.isNotEmpty()) graph.player.play(rows.map { it.episodeId }, 0)
    }

    fun saveAsPlaylist(name: String, rows: List<DigestRow>) = viewModelScope.launch {
        try {
            val playlistId = graph.playlists.create(name)
            rows.forEach { graph.playlists.addEpisode(playlistId, it.episodeId) }
            graph.messages.post("Saved ${rows.size} episodes to “$name”")
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            graph.messages.post("Saving playlist failed: ${friendlyError(e)}")
        }
    }

    private suspend fun report(block: suspend () -> Unit) {
        try {
            block()
            _state.update { it.copy(loading = false) }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            _state.update { it.copy(loading = false, error = friendlyError(e)) }
        }
    }
}

/**
 * Last week's best episodes in English and Chinese, each with a blurb written
 * from its own show notes. Chosen by Claude and Codex together; every pick was
 * found in a real feed before it was published, so anything listed plays.
 */
@Composable
fun WeeklyScreen(onOpenEpisode: (String) -> Unit, onBack: () -> Unit = {}) {
    val viewModel = appViewModel { WeeklyViewModel(it) }
    val state by viewModel.state.collectAsStateWithLifecycle()
    val entries by viewModel.entries.collectAsStateWithLifecycle()
    val playlists by viewModel.playlists.collectAsStateWithLifecycle()
    var addingEpisodeId by remember { mutableStateOf<String?>(null) }

    val (chinese, english) = entries.partition(WeeklyRepository::isChinese)
    val label = state.selected?.label

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(bottom = 24.dp),
    ) {
        item(key = "header") {
            WeeklyHeader(
                state = state,
                count = entries.size,
                onBack = onBack,
                onSelect = viewModel::select,
                onRefresh = { viewModel.refresh() },
                onSave = {
                    viewModel.saveAsPlaylist(
                        if (label != null) "Best of $label" else "Best of the week",
                        english + chinese,
                    )
                },
            )
        }
        state.error?.let { error ->
            item(key = "error") {
                ErrorNotice(error, onRetry = { viewModel.refresh() }, onDismiss = viewModel::clearError)
            }
        }
        if (entries.isEmpty()) {
            item(key = "empty") {
                Box(modifier = Modifier.fillMaxWidth().padding(24.dp)) {
                    if (state.loading) {
                        CircularProgressIndicator(modifier = Modifier.align(Alignment.Center))
                    } else {
                        Text(
                            "No digest yet. A new one is published every Monday.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }
        section("english", "English", english, viewModel, onOpenEpisode) { addingEpisodeId = it }
        section("chinese", "中文", chinese, viewModel, onOpenEpisode) { addingEpisodeId = it }
    }

    addingEpisodeId?.let { episodeId ->
        AddToPlaylistDialog(
            playlists = playlists,
            onAdd = { playlistId ->
                viewModel.actions.addToPlaylist(playlistId, episodeId)
                addingEpisodeId = null
            },
            onCreateAndAdd = { name ->
                viewModel.actions.createPlaylistAndAdd(name, episodeId)
                addingEpisodeId = null
            },
            onDismiss = { addingEpisodeId = null },
        )
    }
}

@Composable
private fun WeeklyHeader(
    state: WeeklyUiState,
    count: Int,
    onBack: () -> Unit,
    onSelect: (WeeklyIssue) -> Unit,
    onRefresh: () -> Unit,
    onSave: () -> Unit,
) {
    var pickingWeek by remember { mutableStateOf(false) }
    Column(modifier = Modifier.fillMaxWidth().padding(top = 4.dp, bottom = 4.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            // The bottom bar can't get you out of here: Compose restores the saved
            // back stack of whichever tab opened this screen.
            IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") }
            Column(modifier = Modifier.weight(1f)) {
                Text("Best of the week", style = MaterialTheme.typography.titleMedium)
                Text(
                    buildString {
                        append("$count episodes in English and 中文")
                        pickedByText(state.selected?.pickedBy.orEmpty())?.let { append(", $it") }
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (state.loading && count > 0) {
                CircularProgressIndicator(modifier = Modifier.size(20.dp))
            }
            IconButton(onClick = onRefresh) { Icon(Icons.Filled.Refresh, "Refresh") }
        }
        Row(
            modifier = Modifier.padding(horizontal = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(
                onClick = { state.older?.let(onSelect) },
                enabled = state.older != null,
            ) { Icon(Icons.AutoMirrored.Filled.KeyboardArrowLeft, "Older week") }
            Box {
                TextButton(
                    onClick = { pickingWeek = true },
                    enabled = state.issues.size > 1,
                ) { Text(state.selected?.label ?: "This week") }
                DropdownMenu(expanded = pickingWeek, onDismissRequest = { pickingWeek = false }) {
                    state.issues.forEach { issue ->
                        DropdownMenuItem(
                            text = { Text(issue.label.ifBlank { issue.id }) },
                            onClick = {
                                pickingWeek = false
                                onSelect(issue)
                            },
                        )
                    }
                }
            }
            IconButton(
                onClick = { state.newer?.let(onSelect) },
                enabled = state.newer != null,
            ) { Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, "Newer week") }
            Box(modifier = Modifier.weight(1f))
            if (count > 0) {
                TextButton(onClick = onSave) {
                    Icon(Icons.AutoMirrored.Filled.PlaylistAdd, null)
                    Text(" Save as playlist")
                }
            }
        }
    }
}

/** "picked by Claude and Codex", or null when the index does not say. */
internal fun pickedByText(models: List<String>): String? {
    val names = models.map { model ->
        when (model) {
            "claude" -> "Claude"
            "codex" -> "Codex"
            else -> model
        }
    }
    return if (names.isEmpty()) null else "picked by ${names.joinToString(" and ")}"
}

private fun LazyListScope.section(
    key: String,
    title: String,
    rows: List<DigestRow>,
    viewModel: WeeklyViewModel,
    onOpenEpisode: (String) -> Unit,
    onAddToPlaylist: (String) -> Unit,
) {
    if (rows.isEmpty()) return
    item(key = "section-$key") {
        Row(
            modifier = Modifier.fillMaxWidth().padding(start = 16.dp, end = 8.dp, top = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                "$title · ${rows.size}",
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.weight(1f),
            )
            TextButton(onClick = { viewModel.playAll(rows) }) {
                Icon(Icons.Filled.PlayArrow, null)
                Text(" Play all")
            }
        }
    }
    itemsIndexed(rows, key = { _, row -> "$key-${row.episodeId}" }) { index, row ->
        DigestEntry(
            rank = index + 1,
            row = row,
            onOpen = { onOpenEpisode(row.episodeId) },
            onPlay = { viewModel.playOne(row.episodeId) },
            onAddToPlaylist = { onAddToPlaylist(row.episodeId) },
        )
    }
}

@Composable
private fun DigestEntry(
    rank: Int,
    row: DigestRow,
    onOpen: () -> Unit,
    onPlay: () -> Unit,
    onAddToPlaylist: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onOpen)
            .padding(start = 16.dp, end = 4.dp, top = 10.dp, bottom = 10.dp),
        verticalAlignment = Alignment.Top,
    ) {
        AsyncImage(
            model = row.artworkUrl,
            contentDescription = null,
            modifier = Modifier.size(56.dp).clip(RoundedCornerShape(8.dp)),
        )
        Column(modifier = Modifier.weight(1f).padding(horizontal = 12.dp)) {
            Text(
                "$rank. ${row.title}",
                style = MaterialTheme.typography.bodyLarge,
                maxLines = 3,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                row.podcastTitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            val published = publishedText(row.pubDateMs, row.durationMs)
            val status = when {
                row.completed -> "Played"
                row.playbackPositionMs > 0 -> "Started"
                else -> null
            }
            listOfNotNull(published, status).joinToString(" · ").takeIf { it.isNotEmpty() }?.let {
                Text(
                    it,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                )
            }
            // The blurb is the point of the digest, so it is never truncated.
            row.reason?.let { blurb ->
                Text(
                    blurb,
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.padding(top = 4.dp),
                )
            }
        }
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            IconButton(onClick = onPlay) { Icon(Icons.Filled.PlayArrow, "Play") }
            IconButton(onClick = onAddToPlaylist) {
                Icon(Icons.AutoMirrored.Filled.PlaylistAdd, "Add to playlist")
            }
        }
    }
}
