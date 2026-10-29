package com.podly.ui.library

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import coil.compose.AsyncImage
import com.podly.AppGraph
import com.podly.data.db.SubscribedPodcast
import com.podly.ui.appViewModel
import com.podly.ui.util.relativeAge
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import java.text.Collator

enum class SubscriptionSort(val label: String) {
    // Not "A–Z": the order is the device locale's, which on a zh-TW phone puts Chinese
    // titles first, by stroke count.
    TITLE("Name"),
    RECENT("Recently played"),
}

/**
 * Narrows [shows] to those whose title or author contains every word of [query]
 * (case-insensitive; a Chinese title matches on the characters typed), then orders them.
 * Recently played puts never-played shows last, by name. Names sort without their
 * leading symbols, so "《The Real Story》" files under T and "☀️ 全球…" under 全.
 */
internal fun filterSubscriptions(
    shows: List<SubscribedPodcast>,
    query: String,
    sort: SubscriptionSort,
    collator: Comparator<Any?> = Collator.getInstance(),
): List<SubscribedPodcast> {
    val words = query.trim().split(Regex("\\s+")).filter { it.isNotEmpty() }
    val matching = shows.filter { show ->
        val haystack = "${show.podcast.title} ${show.podcast.author}"
        words.all { haystack.contains(it, ignoreCase = true) }
    }
    val byTitle = compareBy<SubscribedPodcast, String>(collator) { sortName(it.podcast.title) }
    return when (sort) {
        SubscriptionSort.TITLE -> matching.sortedWith(byTitle)
        SubscriptionSort.RECENT -> matching.sortedWith(compareByDescending<SubscribedPodcast> { it.lastPlayedAt }.then(byTitle))
    }
}

/** [title] from its first letter or digit, by code point so a supplementary-plane Han character counts. */
internal fun sortName(title: String): String {
    var i = 0
    while (i < title.length) {
        val codePoint = title.codePointAt(i)
        if (Character.isLetterOrDigit(codePoint)) return title.substring(i)
        i += Character.charCount(codePoint)
    }
    return title
}

class PodcastsViewModel(graph: AppGraph) : ViewModel() {
    /** Null until the first read, so the page doesn't flash "no subscriptions". */
    val shows: StateFlow<List<SubscribedPodcast>?> = graph.podcasts.subscribedWithLastPlayed()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)

    // Compose state rather than a StateFlow: a text field fed through a flow lags a
    // frame behind the IME, which can break Zhuyin/Pinyin composition. Kept here so a
    // filter survives opening a show and coming back.
    var query by mutableStateOf("")
    var sort by mutableStateOf(SubscriptionSort.TITLE)
}

/** Every subscribed show, filterable as you type: the way to a show the Library carousel buries. */
@Composable
fun PodcastsScreen(onOpenPodcast: (String) -> Unit, onBack: () -> Unit) {
    val viewModel = appViewModel { PodcastsViewModel(it) }
    val shows by viewModel.shows.collectAsStateWithLifecycle()
    val collator = remember { Collator.getInstance() }
    val all = shows ?: emptyList()
    val visible = remember(all, viewModel.query, viewModel.sort) {
        filterSubscriptions(all, viewModel.query, viewModel.sort, collator)
    }
    val focusManager = LocalFocusManager.current
    // A new filter or sort starts from the top. Without this the list keeps the first
    // visible row's key, so switching sort after scrolling lands mid-list.
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
                Text("Your podcasts", style = MaterialTheme.typography.titleMedium)
                Text(
                    when {
                        visible.size != all.size -> "${visible.size} of ${all.size} shows"
                        all.size == 1 -> "1 show"
                        else -> "${all.size} shows"
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        OutlinedTextField(
            value = viewModel.query,
            onValueChange = {
                viewModel.query = it
                showFromTop()
            },
            placeholder = { Text("Filter by name or host") },
            leadingIcon = { Icon(Icons.Filled.Search, null) },
            trailingIcon = {
                if (viewModel.query.isNotEmpty()) {
                    IconButton(onClick = {
                        viewModel.query = ""
                        showFromTop()
                    }) { Icon(Icons.Filled.Clear, "Clear filter") }
                }
            },
            singleLine = true,
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
            keyboardActions = KeyboardActions(onSearch = { focusManager.clearFocus() }),
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
        )
        Row(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            SubscriptionSort.entries.forEach { sort ->
                FilterChip(
                    selected = viewModel.sort == sort,
                    onClick = {
                        viewModel.sort = sort
                        showFromTop()
                    },
                    label = { Text(sort.label) },
                )
            }
        }
        LazyColumn(state = listState, modifier = Modifier.fillMaxSize()) {
            when {
                shows == null -> Unit
                all.isEmpty() -> item { EmptyNote("No subscriptions yet. Find podcasts in Discover.") }
                visible.isEmpty() -> item { EmptyNote("No subscribed show matches “${viewModel.query.trim()}”.") }
            }
            items(visible, key = { it.podcast.id }) { show ->
                SubscriptionRow(
                    show = show,
                    showLastPlayed = viewModel.sort == SubscriptionSort.RECENT,
                    onClick = { onOpenPodcast(show.podcast.id) },
                )
            }
        }
    }
}

@Composable
private fun SubscriptionRow(show: SubscribedPodcast, showLastPlayed: Boolean, onClick: () -> Unit) {
    val podcast = show.podcast
    val detail = listOfNotNull(
        show.lastPlayedAt.takeIf { showLastPlayed && it > 0 }
            ?.let { "Played ${relativeAge(it, System.currentTimeMillis())}" },
        podcast.author.ifBlank { null },
    ).joinToString(" · ")
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        AsyncImage(
            model = podcast.artworkUrl,
            contentDescription = null,
            modifier = Modifier.size(56.dp).clip(RoundedCornerShape(8.dp)),
        )
        Column(modifier = Modifier.weight(1f).padding(start = 12.dp)) {
            Text(
                podcast.title,
                style = MaterialTheme.typography.bodyLarge,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            if (detail.isNotEmpty()) {
                Text(
                    detail,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

@Composable
private fun EmptyNote(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(16.dp),
    )
}
