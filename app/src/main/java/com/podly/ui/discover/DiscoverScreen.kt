package com.podly.ui.discover

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.podly.data.db.PodcastEntity
import com.podly.ui.appViewModel

@Composable
fun DiscoverScreen(onOpenPodcast: (String) -> Unit, onOpenPlaylist: (Long) -> Unit) {
    val viewModel = appViewModel { DiscoverViewModel(it) }
    val state by viewModel.state.collectAsStateWithLifecycle()

    Box(modifier = Modifier.fillMaxSize()) {
        LazyColumn(modifier = Modifier.fillMaxSize()) {
            item {
                OutlinedTextField(
                    value = state.query,
                    onValueChange = viewModel::setQuery,
                    label = { Text("Search podcasts (or paste a feed URL)") },
                    singleLine = true,
                    trailingIcon = {
                        IconButton(onClick = viewModel::search) {
                            Icon(Icons.Filled.Search, "Search")
                        }
                    },
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp),
                )
            }

            state.error?.let { error ->
                item {
                    Text(
                        error,
                        color = MaterialTheme.colorScheme.error,
                        modifier = Modifier.padding(horizontal = 16.dp),
                    )
                }
            }

            if (state.searching) {
                item {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(16.dp),
                        horizontalArrangement = Arrangement.Center,
                    ) { CircularProgressIndicator() }
                }
            }

            val results = state.searchResults
            if (results != null) {
                searchResultsSection(results, viewModel, onOpenPodcast)
            } else {
                trendingSection(state, viewModel, onOpenPodcast)
                aiPicksSections(state, viewModel, onOpenPodcast, onOpenPlaylist)
            }
        }

        if (state.opening) {
            CircularProgressIndicator(modifier = Modifier.align(Alignment.Center))
        }
    }
}

/** The search-results block: a "Results"/"Clear" header plus one row per match. */
private fun LazyListScope.searchResultsSection(
    results: List<PodcastEntity>,
    viewModel: DiscoverViewModel,
    onOpenPodcast: (String) -> Unit,
) {
    item {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("Results", style = MaterialTheme.typography.titleMedium)
            Text(
                "Clear",
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.clickable { viewModel.clearSearch() },
            )
        }
    }
    items(results, key = { it.id }) { podcast ->
        PodcastListRow(
            title = podcast.title,
            subtitle = podcast.author,
            artworkUrl = podcast.artworkUrl,
            onClick = { viewModel.openPodcast(podcast) { id -> onOpenPodcast(id) } },
        )
    }
}
