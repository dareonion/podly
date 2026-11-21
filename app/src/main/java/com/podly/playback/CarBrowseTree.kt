package com.podly.playback

import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import com.podly.data.PlaylistRepository
import com.podly.data.db.PodlyDatabase
import com.podly.radio.RadioProfiles
import com.podly.ui.library.SubscriptionSort
import com.podly.ui.library.filterSubscriptions
import java.text.Collator

/**
 * The browse tree Android Auto (and any other media browser) walks, read from Room.
 *
 * Android Auto shows the root's children as tabs, and only four of them: Google's
 * guidance is that any past the limit "may be omitted or made less discoverable".
 * So the root holds at most four (Continue, This week once a digest has synced,
 * Podcasts, Library), and Playlists, Downloads and the full episode list sit one
 * level down, inside Library. Every list is capped at [MAX_CHILDREN].
 */
internal class CarBrowseTree(
    private val database: PodlyDatabase,
    private val playlists: PlaylistRepository,
    private val collator: () -> Comparator<Any?> = { Collator.getInstance() },
) {

    suspend fun children(parentId: String): List<MediaItem> = when (parentId) {
        MediaIds.ROOT -> listOfNotNull(
            MediaItemFactory.folder(MediaIds.NODE_CONTINUE, "Continue"),
            // Only once a digest has synced: an empty folder in the car reads as
            // broken, and there is nothing to do about it from the driver's seat.
            MediaItemFactory.folder(MediaIds.NODE_WEEKLY, "This week").takeIf {
                database.radioDao().poolEpisodesOnce(RadioProfiles.WEEKLY_ID, 1).isNotEmpty()
            },
            MediaItemFactory.folder(MediaIds.NODE_PODCASTS, "Podcasts", MediaMetadata.MEDIA_TYPE_FOLDER_PODCASTS),
            MediaItemFactory.folder(MediaIds.NODE_LIBRARY, "Library", MediaMetadata.MEDIA_TYPE_FOLDER_MIXED),
        )
        MediaIds.NODE_CONTINUE ->
            database.episodeDao().continueListeningOnce(MAX_CHILDREN).map(MediaItemFactory::browsableEpisode)
        MediaIds.NODE_WEEKLY ->
            database.radioDao().poolEpisodesOnce(RadioProfiles.WEEKLY_ID, MAX_CHILDREN)
                .map(MediaItemFactory::browsableEpisode)
        // Recently played first, as on the phone's Your podcasts page with that sort:
        // the show you are in the middle of is the likeliest one wanted in the car.
        MediaIds.NODE_PODCASTS ->
            filterSubscriptions(
                database.podcastDao().subscribedWithLastPlayedOnce(),
                query = "",
                sort = SubscriptionSort.RECENT,
                collator = collator(),
            ).take(MAX_CHILDREN).map { MediaItemFactory.podcast(it.podcast) }
        MediaIds.NODE_LIBRARY -> listOf(
            MediaItemFactory.folder(MediaIds.NODE_PLAYLISTS, "Playlists", MediaMetadata.MEDIA_TYPE_FOLDER_PLAYLISTS),
            MediaItemFactory.folder(MediaIds.NODE_DOWNLOADS, "Downloads"),
            MediaItemFactory.folder(MediaIds.NODE_ALL_EPISODES, "All episodes"),
        )
        MediaIds.NODE_PLAYLISTS ->
            database.playlistDao().playlistsOnce()
                .take(MAX_CHILDREN)
                .map { MediaItemFactory.folder(MediaIds.playlist(it.id), it.name) }
        MediaIds.NODE_DOWNLOADS ->
            database.episodeDao().downloadedEpisodesOnce()
                .take(MAX_CHILDREN)
                .map(MediaItemFactory::browsableEpisode)
        MediaIds.NODE_ALL_EPISODES ->
            database.episodeDao().libraryEpisodesOnce()
                .take(MAX_CHILDREN)
                .map(MediaItemFactory::browsableEpisode)
        else -> {
            val podcastId = MediaIds.podcastIdOrNull(parentId)
            val playlistId = MediaIds.playlistIdOrNull(parentId)
            when {
                // In the show's own sort order, so a serial set to oldest first starts at
                // its first episode here too. Played from here, an episode plays on
                // through the show (continueThroughShow).
                podcastId != null ->
                    database.episodeDao().firstEpisodesForPodcast(podcastId, MAX_CHILDREN)
                        .map { MediaItemFactory.browsableEpisode(it, MediaIds.showEpisode(it.id)) }
                playlistId != null ->
                    playlists.sortedEpisodesOnce(playlistId)
                        .take(MAX_CHILDREN)
                        .map(MediaItemFactory::browsableEpisode)
                else -> emptyList()
            }
        }
    }

    companion object {
        const val MAX_CHILDREN = 100

        /** The root item itself: pure, so `onGetLibraryRoot` can return it already complete. */
        fun root(): MediaItem =
            MediaItemFactory.folder(MediaIds.ROOT, "Podly", MediaMetadata.MEDIA_TYPE_FOLDER_MIXED)
    }
}

/**
 * The [page]th run of [pageSize] items. Media3 rejects a children result longer than
 * the page size asked for ("Invalid size=…, pageSize=…"), so a browser that pages got
 * an error instead of the list. An unpaged legacy browser asks for page 0 of
 * Int.MAX_VALUE.
 */
internal fun <T> List<T>.page(page: Int, pageSize: Int): List<T> {
    if (page < 0 || pageSize <= 0) return emptyList()
    val from = page.toLong() * pageSize
    if (from >= size) return emptyList()
    return subList(from.toInt(), minOf(size.toLong(), from + pageSize).toInt())
}
