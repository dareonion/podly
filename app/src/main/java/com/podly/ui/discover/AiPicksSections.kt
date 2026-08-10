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
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.podly.network.ai.RecentEpisodeWindow
import com.podly.ui.util.generatedText
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * The AI-sourced sections: the recent-episode picks, the acclaimed list, and
 * the personalized recommendations. The first two load themselves (cache
 * first, static file when stale); personalized picks stay behind an explicit
 * Generate action because they call the user's configured AI provider.
 */
internal fun LazyListScope.aiPicksSections(
    state: DiscoverUiState,
    viewModel: DiscoverViewModel,
    onOpenPodcast: (String) -> Unit,
    onOpenPlaylist: (Long) -> Unit,
) {
    recentEpisodesSection(state, viewModel, onOpenPodcast, onOpenPlaylist)
    acclaimedSection(state, viewModel, onOpenPodcast)
    recommendationsSection(state, viewModel, onOpenPodcast)
}

/** The recent-episode picks: header with window chips, save-as-playlist, then the rows. */
private fun LazyListScope.recentEpisodesSection(
    state: DiscoverUiState,
    viewModel: DiscoverViewModel,
    onOpenPodcast: (String) -> Unit,
    onOpenPlaylist: (Long) -> Unit,
) {
    item {
        Column(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            SectionHeader("Best recent episodes", "Refresh") {
                viewModel.loadRecentEpisodes(state.recentEpisodeWindow, force = true)
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                RecentEpisodeWindow.entries.forEach { window ->
                    FilterChip(
                        selected = state.recentEpisodeWindow == window,
                        onClick = { viewModel.loadRecentEpisodes(window) },
                        label = { Text(window.label) },
                    )
                }
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
    if (state.recentEpisodesLoading) loadingItem()
    state.recentEpisodes?.let { picks ->
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
}

/** The award winners & critics' picks list; hidden until its auto-load lands. */
private fun LazyListScope.acclaimedSection(
    state: DiscoverUiState,
    viewModel: DiscoverViewModel,
    onOpenPodcast: (String) -> Unit,
) {
    if (state.acclaimed == null && !state.acclaimedLoading) return
    item {
        Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
            SectionHeader("Award winners & critics' picks from the last year", "Refresh") {
                viewModel.loadAcclaimed(force = true)
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
    if (state.acclaimedLoading) loadingItem()
    state.acclaimed?.let { picks ->
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
}

/** The on-device personalized "AI picks for you" list, generated on demand. */
private fun LazyListScope.recommendationsSection(
    state: DiscoverUiState,
    viewModel: DiscoverViewModel,
    onOpenPodcast: (String) -> Unit,
) {
    item {
        Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
            SectionHeader(
                "AI picks for you",
                when {
                    state.recsLoading -> null
                    state.recommendations == null -> "Generate"
                    else -> "Refresh"
                },
            ) { viewModel.loadRecommendations() }
            generatedText(state.recsGeneratedAtMs)?.let { caption ->
                Text(
                    caption,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (state.recommendations == null && !state.recsLoading) {
                Text(
                    "Show suggestions based on your listening history, generated with your AI key from Settings.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
    if (state.recsLoading) loadingItem()
    state.recommendations?.let { recs ->
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
}

/** A section title with an optional trailing text action ("Refresh"/"Generate"). */
@Composable
private fun SectionHeader(title: String, action: String?, onAction: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            title,
            style = MaterialTheme.typography.titleMedium,
            modifier = Modifier.weight(1f),
        )
        if (action != null) {
            Text(
                action,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.clickable(onClick = onAction),
            )
        }
    }
}

private fun LazyListScope.loadingItem() {
    item {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            horizontalArrangement = Arrangement.Center,
        ) { CircularProgressIndicator() }
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
