package com.podly.ui.discover

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.podly.network.TrendingPeriod

/** The "Popular podcasts" block: period chips plus the trending chart rows. */
internal fun LazyListScope.trendingSection(
    state: DiscoverUiState,
    viewModel: DiscoverViewModel,
    onOpenPodcast: (String) -> Unit,
) {
    item {
        Text(
            "Popular podcasts",
            style = MaterialTheme.typography.titleMedium,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
        )
    }
    item {
        Row(
            modifier = Modifier.padding(horizontal = 16.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            FilterChip(
                selected = state.trendingPeriod == TrendingPeriod.NOW,
                onClick = { viewModel.loadTrending(TrendingPeriod.NOW) },
                label = { Text("Now") },
            )
            FilterChip(
                selected = state.trendingPeriod == TrendingPeriod.WEEK,
                onClick = { viewModel.loadTrending(TrendingPeriod.WEEK) },
                label = { Text("Week") },
                enabled = state.hasPodcastIndexCreds,
            )
            FilterChip(
                selected = state.trendingPeriod == TrendingPeriod.MONTH,
                onClick = { viewModel.loadTrending(TrendingPeriod.MONTH) },
                label = { Text("Month") },
                enabled = state.hasPodcastIndexCreds,
            )
        }
    }
    if (!state.hasPodcastIndexCreds) {
        item {
            Text(
                "Add free PodcastIndex API keys in Settings to unlock Week/Month trending.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
            )
        }
    }
    if (state.trendingLoading) {
        item {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp),
                horizontalArrangement = Arrangement.Center,
            ) { CircularProgressIndicator() }
        }
    }
    itemsIndexed(state.trending) { index, item ->
        PodcastListRow(
            title = "${index + 1}. ${item.title}",
            subtitle = item.author,
            artworkUrl = item.artworkUrl,
            onClick = { viewModel.openTrending(item) { id -> onOpenPodcast(id) } },
        )
    }
}
