package com.podly

import android.content.Context
import android.util.Log
import com.podly.data.AiPicksCache
import com.podly.data.ArchiveRescuer
import com.podly.data.CachedArchive
import com.podly.data.PicksImporter
import com.podly.data.PodcastIndexArchive
import com.podly.data.TaddyArchive
import com.podly.network.TaddyApi
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
import com.podly.ui.UiMessages
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

/**
 * Hand-rolled dependency graph; one instance lives on [PodlyApp].
 */
class AppGraph(private val context: Context) {
    /** Application context, for WorkManager enqueue/observe from ViewModels. */
    val appContext: Context = context.applicationContext
    /** Process-wide scope for fire-and-forget work that must outlive a component. */
    val applicationScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    /** One-shot user-visible messages; MainActivity shows them as snackbars. */
    val messages: UiMessages = UiMessages()
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
    val taddy: TaddyApi = TaddyApi()
    // Recovers picks that rolled off a short feed. Tries whichever archive is configured
    // (Taddy first, then PodcastIndex), falling through on missing creds / errors / limits.
    // Each provider's real answers are cached in memory so repeated rescues of the same
    // shows don't re-issue identical requests.
    val picksRescuer: ArchiveRescuer = ArchiveRescuer(
        podcasts,
        listOf(TaddyArchive(taddy, settings), PodcastIndexArchive(podcastIndex, settings))
            .map { CachedArchive(it, log = { msg -> Log.i("EpisodeArchive", msg) }) },
    )
    val picksImporter: PicksImporter = PicksImporter(podcasts, playlists, picksRescuer)
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
