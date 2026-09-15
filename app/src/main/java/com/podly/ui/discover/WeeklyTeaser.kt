package com.podly.ui.discover

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import com.podly.data.db.DigestRow

/**
 * The top of the weekly digest, alternating English and Chinese, with a way into
 * the whole thing. Absent until a digest has synced.
 */
internal fun LazyListScope.weeklyTeaserSection(
    rows: List<DigestRow>,
    onOpenWeekly: () -> Unit,
    onOpenEpisode: (String) -> Unit,
    onPlay: (String) -> Unit,
) {
    if (rows.isEmpty()) return
    item(key = "weekly-header") {
        Row(
            modifier = Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                "Best of last week",
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.weight(1f),
            )
            Text(
                "See all",
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.clickable(onClick = onOpenWeekly),
            )
        }
    }
    items(rows, key = { "weekly-${it.episodeId}" }) { row ->
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable { onOpenEpisode(row.episodeId) }
                .padding(start = 16.dp, end = 4.dp, top = 8.dp, bottom = 8.dp),
            verticalAlignment = Alignment.Top,
        ) {
            AsyncImage(
                model = row.artworkUrl,
                contentDescription = null,
                modifier = Modifier.size(48.dp).clip(RoundedCornerShape(8.dp)),
            )
            Column(modifier = Modifier.weight(1f).padding(horizontal = 12.dp)) {
                Text(
                    row.title,
                    style = MaterialTheme.typography.bodyLarge,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    row.podcastTitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                row.reason?.let { blurb ->
                    Text(
                        blurb,
                        style = MaterialTheme.typography.bodySmall,
                        maxLines = 3,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.padding(top = 2.dp),
                    )
                }
            }
            IconButton(onClick = { onPlay(row.episodeId) }) {
                Icon(Icons.Filled.PlayArrow, "Play")
            }
        }
    }
}
