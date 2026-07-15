package com.podly

import android.content.Context
import com.podly.data.AiPicksCache
import com.podly.data.PlaybackStateStore
import com.podly.data.PlaylistRepository
import com.podly.data.PodcastRepository
import com.podly.data.SettingsRepository
import com.podly.data.db.PodlyDatabase
import com.podly.downloads.Downloader
import com.podly.network.AppleChartsApi
import com.podly.network.PodcastIndexApi
import com.podly.network.RemoteRecsApi
import com.podly.network.ai.AiRecommender
import com.podly.playback.PlayerConnection

/**
 * Hand-rolled dependency graph; one instance lives on [PodlyApp].
 */
class AppGraph(private val context: Context) {
    /** Application context, for WorkManager enqueue/observe from ViewModels. */
    val appContext: Context = context.applicationContext
    val database: PodlyDatabase = PodlyDatabase.build(context)
    val settings: SettingsRepository = SettingsRepository(context)
    val playbackState: PlaybackStateStore = PlaybackStateStore(context)
    val podcasts: PodcastRepository =
        PodcastRepository(database.podcastDao(), database.episodeDao())
    val playlists: PlaylistRepository = PlaylistRepository(database.playlistDao())
    val downloader: Downloader =
        Downloader(context, settings, database.podcastDao(), database.episodeDao())
    val appleCharts: AppleChartsApi = AppleChartsApi()
    val podcastIndex: PodcastIndexApi = PodcastIndexApi()
    val aiRecommender: AiRecommender =
        AiRecommender(settings, database.podcastDao(), database.episodeDao())
    // Recent-episode + acclaimed lists are pre-generated server-side and fetched as static JSON.
    val remoteRecs: RemoteRecsApi = RemoteRecsApi()
    val aiPicksCache: AiPicksCache = AiPicksCache(context)

    /** Lazy so the controller (and thus the service) only spins up when the UI needs it. */
    val player: PlayerConnection by lazy { PlayerConnection(context) }
}

val Context.appGraph: AppGraph
    get() = (applicationContext as PodlyApp).graph
