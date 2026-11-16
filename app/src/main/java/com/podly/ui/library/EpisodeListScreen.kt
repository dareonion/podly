package com.podly.ui.library

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.podly.AppGraph
import com.podly.data.db.EpisodeEntity
import com.podly.ui.EpisodeActions
import com.podly.ui.appViewModel
import com.podly.ui.components.AddToPlaylistDialog
import com.podly.ui.components.DescriptionDialog
import com.podly.ui.components.EpisodeRow
import com.podly.ui.util.plainDescription
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.stateIn

/** The Library's full lists, each a tap from its short version on the home tab. */
enum class LibraryList(val route: String, val title: String) {
    CONTINUE("continue", "Continue listening"),

    /**
     * Every episode of every subscribed show plus saved ones. It used to be the bottom
     * of the home tab; it is also the only list that reaches an episode saved from a
     * show you don't subscribe to, so it has to stay reachable.
     */
    ALL("all", "All episodes"),
    ;

    companion object {
        fun fromRoute(route: String?): LibraryList = entries.firstOrNull { it.route == route } ?: ALL
    }
}

/**
 * Narrows [episodes] to those whose title or show name contains every word of [query]
 * (case-insensitive; a Chinese title matches on the characters typed), keeping their order.
 */
internal fun filterEpisodes(episodes: List<EpisodeEntity>, query: String): List<EpisodeEntity> {
    // javaWhitespace, not \s: it includes the full-width space a Chinese keyboard types.
    val words = query.trim().split(Regex("\\p{javaWhitespace}+")).filter { it.isNotEmpty() }
    if (words.isEmpty()) return episodes
    return episodes.filter { episode ->
        val haystack = "${episode.title} ${episode.podcastTitle}"
        words.all { haystack.contains(it, ignoreCase = true) }
    }
}

class EpisodeListViewModel(graph: AppGraph, list: LibraryList) : ViewModel() {
    val actions = EpisodeActions(graph, viewModelScope)
    val episodes = when (list) {
        LibraryList.CONTINUE -> graph.podcasts.continueListening(limit = CONTINUE_LIMIT)
        LibraryList.ALL -> graph.podcasts.libraryEpisodes()
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())
    val playlists = graph.playlists.playlists()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    private companion object {
        const val CONTINUE_LIMIT = 100
    }
}

@Composable
fun EpisodeListScreen(list: LibraryList, onOpenEpisode: (String) -> Unit, onBack: () -> Unit) {
    val viewModel = appViewModel(key = "episode_list_${list.route}") { EpisodeListViewModel(it, list) }
    val episodes by viewModel.episodes.collectAsStateWithLifecycle()
    val playlists by viewModel.playlists.collectAsStateWithLifecycle()
    var episodeForPlaylist by remember { mutableStateOf<EpisodeEntity?>(null) }
    var episodeForDescription by remember { mutableStateOf<EpisodeEntity?>(null) }
    // Compose state rather than a StateFlow: a text field fed through a flow lags a
    // frame behind the IME, which can break Zhuyin/Pinyin composition. Saveable, so
    // the filter comes back with the scroll position it belongs to: after opening an
    // episode, and after Android has reclaimed the process in the background.
    var query by rememberSaveable { mutableStateOf("") }
    // Thousands of episodes in All episodes; Continue listening is capped at 100 and
    // needs no filter.
    val filterable = list == LibraryList.ALL
    val visible = remember(episodes, query, filterable) {
        if (filterable) filterEpisodes(episodes, query) else episodes
    }
    val focusManager = LocalFocusManager.current
    // A new filter starts from the top; coming back from an episode keeps the scroll.
    val listState = rememberLazyListState()
    val showFromTop = { listState.requestScrollToItem(0) }

    // imePadding keeps the last matches above the keyboard while you type.
    Column(modifier = Modifier.fillMaxSize().imePadding()) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(end = 16.dp, top = 4.dp, bottom = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") }
            Column {
                Text(list.title, style = MaterialTheme.typography.titleMedium)
                Text(
                    when {
                        visible.size != episodes.size -> "%,d of %,d episodes".format(visible.size, episodes.size)
                        episodes.size == 1 -> "1 episode"
                        else -> "%,d episodes".format(episodes.size)
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        if (filterable) {
            OutlinedTextField(
                value = query,
                onValueChange = {
                    query = it
                    showFromTop()
                },
                placeholder = { Text("Filter by episode or show") },
                leadingIcon = { Icon(Icons.Filled.Search, null) },
                trailingIcon = {
                    if (query.isNotEmpty()) {
                        IconButton(onClick = {
                            query = ""
                            showFromTop()
                        }) { Icon(Icons.Filled.Clear, "Clear filter") }
                    }
                },
                singleLine = true,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                keyboardActions = KeyboardActions(onSearch = { focusManager.clearFocus() }),
                modifier = Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, bottom = 4.dp),
            )
        }
        LazyColumn(state = listState, modifier = Modifier.fillMaxSize()) {
            if (visible.isEmpty() && episodes.isNotEmpty()) {
                item(key = "no-match") {
                    Text(
                        "No episode or show matches “${query.trim()}”.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(16.dp),
                    )
                }
            }
            items(visible, key = { it.id }) { episode ->
                EpisodeRow(
                    episode = episode,
                    // The list you see, so a filtered list plays on through its matches.
                    onPlay = { viewModel.actions.play(visible, visible.indexOf(episode)) },
                    onToggleLibrary = { viewModel.actions.toggleLibrary(episode) },
                    onDownload = { viewModel.actions.download(episode) },
                    onRemoveDownload = { viewModel.actions.removeDownload(episode) },
                    onAddToPlaylist = { episodeForPlaylist = episode },
                    onTogglePlayed = { viewModel.actions.togglePlayed(episode) },
                    onShowDescription = { episodeForDescription = episode },
                    onOpenDetail = { onOpenEpisode(episode.id) },
                )
            }
        }
    }

    episodeForPlaylist?.let { episode ->
        AddToPlaylistDialog(
            playlists = playlists,
            onAdd = { playlistId ->
                viewModel.actions.addToPlaylist(playlistId, episode.id)
                episodeForPlaylist = null
            },
            onCreateAndAdd = { name ->
                viewModel.actions.createPlaylistAndAdd(name, episode.id)
                episodeForPlaylist = null
            },
            onDismiss = { episodeForPlaylist = null },
        )
    }

    episodeForDescription?.let { episode ->
        plainDescription(episode.description)?.let { description ->
            DescriptionDialog(
                title = episode.title,
                description = description,
                onDismiss = { episodeForDescription = null },
            )
        }
    }
}
