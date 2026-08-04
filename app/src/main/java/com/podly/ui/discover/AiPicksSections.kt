package com.podly.ui.discover

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.PlaylistAdd
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.EmojiEvents
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.podly.network.ai.RecentEpisodeWindow
import com.podly.ui.util.generatedText
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * The AI-sourced blocks: the fetch buttons, then (as loaded) the recent-episode
 * picks, the acclaimed list, and the personalized recommendations.
 */
internal fun LazyListScope.aiPicksSections(
    state: DiscoverUiState,
    viewModel: DiscoverViewModel,
    onOpenPodcast: (String) -> Unit,
    onOpenPlaylist: (Long) -> Unit,
) {
    item {
        Column(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(
                    onClick = viewModel::loadRecommendations,
                    enabled = !state.recsLoading,
                ) {
                    Icon(Icons.Filled.AutoAwesome, null)
                    Text("  AI picks")
                }
                Button(
                    onClick = viewModel::loadAcclaimed,
                    enabled = !state.acclaimedLoading,
                ) {
                    Icon(Icons.Filled.EmojiEvents, null)
                    Text("  Acclaimed")
                }
            }
            Button(
                onClick = { viewModel.loadRecentEpisodes() },
                enabled = !state.recentEpisodesLoading,
            ) {
                Icon(Icons.Filled.AutoAwesome, null)
                Text("  Best recent episodes")
            }
        }
    }
    if (state.recsLoading || state.acclaimedLoading || state.recentEpisodesLoading) {
        item {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp),
                horizontalArrangement = Arrangement.Center,
            ) { CircularProgressIndicator() }
        }
    }
    item {
        Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
            Text(
                "Best individual episodes",
                style = MaterialTheme.typography.titleMedium,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                RecentEpisodeWindow.entries.forEach { window ->
                    FilterChip(
                        selected = state.recentEpisodeWindow == window,
                        onClick = { viewModel.loadRecentEpisodes(window) },
                        label = { Text(window.label) },
                    )
                }
            }
        }
    }
    state.recentEpisodes?.let { picks ->
        recentEpisodesItems(picks, state, viewModel, onOpenPodcast, onOpenPlaylist)
    }
    state.acclaimed?.let { picks ->
        acclaimedItems(picks, state, viewModel, onOpenPodcast)
    }
    state.recommendations?.let { recs ->
        recommendationItems(recs, state, viewModel, onOpenPodcast)
    }
}

/** The recent-episode picks: header + coverage caption, save-as-playlist, then the rows. */
private fun LazyListScope.recentEpisodesItems(
    picks: List<ResolvedRecentEpisode>,
    state: DiscoverUiState,
    viewModel: DiscoverViewModel,
    onOpenPodcast: (String) -> Unit,
    onOpenPlaylist: (Long) -> Unit,
) {
    item {
        Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    "Worthwhile episodes from the past ${state.recentEpisodeWindow.label.lowercase()}",
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.weight(1f),
                )
                Text(
                    "Refresh",
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.clickable {
                        viewModel.loadRecentEpisodes(state.recentEpisodeWindow, force = true)
                    },
                )
            }
            recentCoverageCaption(
                state.recentCoverageStart,
                state.recentCoverageEnd,
                state.recentGeneratedAtMs,
            )?.let { caption ->
                Text(
                    caption,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
    item {
        Column(modifier = Modifier.padding(horizontal = 16.dp)) {
            Button(
                onClick = { viewModel.saveRecentEpisodesAsPlaylist() },
                enabled = !state.savingPlaylist,
            ) {
                if (state.savingPlaylist) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(18.dp),
                        strokeWidth = 2.dp,
                    )
                    Text("  Saving playlist…")
                } else {
                    Icon(Icons.AutoMirrored.Filled.PlaylistAdd, null)
                    Text("  Save as playlist")
                }
            }
            state.recentPlaylistResult?.let { result ->
                Spacer(Modifier.height(8.dp))
                Text(
                    "Saved ${result.saved} of ${result.total} to \"${result.name}\".",
                    style = MaterialTheme.typography.bodyMedium,
                )
                if (result.missed.isNotEmpty()) {
                    Text(
                        "Couldn't find in feeds: ${result.missed.joinToString("; ")}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    if (!state.hasArchiveCreds) {
                        Text(
                            "Some shows only publish their newest episodes. Add a free " +
                                "Taddy or PodcastIndex API key in Settings to pull older ones from an archive.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                TextButton(
                    onClick = { onOpenPlaylist(result.playlistId) },
                    contentPadding = PaddingValues(0.dp),
                ) {
                    Text("Open playlist")
                }
            }
        }
    }
    items(picks) { resolved ->
        val pick = resolved.pick
        AiPickRow(
            podcast = resolved.podcast,
            title = pick.episodeTitle,
            subtitle = resolved.podcast?.title ?: pick.podcastTitle,
            detail = pick.reason + (pick.publishedApprox?.let { " Published around $it." } ?: ""),
            fallbackQuery = pick.podcastTitle,
            viewModel = viewModel,
            onOpenPodcast = onOpenPodcast,
        )
    }
}

/** The award winners & critics' picks list. */
private fun LazyListScope.acclaimedItems(
    picks: List<ResolvedAcclaimed>,
    state: DiscoverUiState,
    viewModel: DiscoverViewModel,
    onOpenPodcast: (String) -> Unit,
) {
    item {
        Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    "Award winners & critics' picks from the last year",
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.weight(1f),
                )
                Text(
                    "Refresh",
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.clickable { viewModel.loadAcclaimed(force = true) },
                )
            }
            generatedText(state.acclaimedGeneratedAtMs)?.let { caption ->
                Text(
                    caption,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
    items(picks) { resolved ->
        val pick = resolved.pick
        AiPickRow(
            podcast = resolved.podcast,
            title = pick.episodeTitle ?: (resolved.podcast?.title ?: pick.podcastTitle),
            subtitle = if (pick.episodeTitle != null) {
                "Episode of ${resolved.podcast?.title ?: pick.podcastTitle}"
            } else {
                resolved.podcast?.author ?: pick.author
            },
            detail = pick.accolade,
            fallbackQuery = pick.podcastTitle,
            viewModel = viewModel,
            onOpenPodcast = onOpenPodcast,
        )
    }
}

/** The on-device personalized "AI picks for you" list. */
private fun LazyListScope.recommendationItems(
    recs: List<ResolvedRecommendation>,
    state: DiscoverUiState,
    viewModel: DiscoverViewModel,
    onOpenPodcast: (String) -> Unit,
) {
    item {
        Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
            Text(
                "AI picks for you",
                style = MaterialTheme.typography.titleMedium,
            )
            generatedText(state.recsGeneratedAtMs)?.let { caption ->
                Text(
                    caption,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
    items(recs) { resolved ->
        val rec = resolved.rec
        AiPickRow(
            podcast = resolved.podcast,
            title = resolved.podcast?.title ?: rec.title,
            subtitle = resolved.podcast?.author ?: rec.author,
            detail = rec.reason,
            fallbackQuery = rec.title,
            viewModel = viewModel,
            onOpenPodcast = onOpenPodcast,
        )
    }
}

/** e.g. "Best of May 26 – Jun 26 · updated 2 days ago", or null when nothing to show. */
private fun recentCoverageCaption(start: String?, end: String?, generatedAtMs: Long): String? =
    listOfNotNull(spanText(start, end), generatedText(generatedAtMs))
        .joinToString(" · ")
        .ifBlank { null }

/** Formats the ISO coverage span as "Best of May 26 – Jun 26", or null if unset/unparseable. */
private fun spanText(start: String?, end: String?): String? {
    if (start.isNullOrBlank() || end.isNullOrBlank()) return null
    return runCatching {
        val fmt = DateTimeFormatter.ofPattern("MMM d", Locale.US)
        "Best of ${LocalDate.parse(start).format(fmt)} – ${LocalDate.parse(end).format(fmt)}"
    }.getOrNull()
}
