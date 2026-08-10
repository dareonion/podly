package com.podly.ui.discover

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import com.podly.network.TrendingPeriod
import com.podly.network.TrendingPodcast

/** The "Popular podcasts" block: period chips plus a horizontal carousel of chart cards. */
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
    if (state.trending.isNotEmpty()) {
        item {
            LazyRow(
                contentPadding = PaddingValues(horizontal = 16.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                modifier = Modifier.padding(vertical = 8.dp),
            ) {
                itemsIndexed(state.trending) { index, item ->
                    TrendingCard(rank = index + 1, podcast = item) {
                        viewModel.openTrending(item) { id -> onOpenPodcast(id) }
                    }
                }
            }
        }
    }
}

/** One chart entry: artwork with a rank badge, title and author underneath. */
@Composable
private fun TrendingCard(rank: Int, podcast: TrendingPodcast, onClick: () -> Unit) {
    Column(
        modifier = Modifier
            .width(120.dp)
            .clickable(onClick = onClick),
    ) {
        Box {
            AsyncImage(
                model = podcast.artworkUrl,
                contentDescription = null,
                modifier = Modifier
                    .size(120.dp)
                    .clip(RoundedCornerShape(8.dp)),
            )
            Text(
                "$rank",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSecondaryContainer,
                modifier = Modifier
                    .padding(4.dp)
                    .background(
                        MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.9f),
                        RoundedCornerShape(6.dp),
                    )
                    .padding(horizontal = 6.dp, vertical = 1.dp),
            )
        }
        Text(
            podcast.title,
            style = MaterialTheme.typography.bodyMedium,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(top = 4.dp),
        )
        Text(
            podcast.author,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}
