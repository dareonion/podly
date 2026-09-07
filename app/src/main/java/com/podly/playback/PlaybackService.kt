package com.podly.playback

import android.app.PendingIntent
import android.os.Bundle
import android.content.Intent
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.util.Log
import androidx.annotation.OptIn
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.ForwardingPlayer
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.Player.PositionInfo
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.cache.CacheDataSource
import androidx.media3.datasource.okhttp.OkHttpDataSource
import androidx.media3.cast.CastPlayer
import androidx.media3.cast.SessionAvailabilityListener
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.session.LibraryResult
import androidx.media3.session.MediaLibraryService
import androidx.media3.session.MediaSession
import androidx.media3.session.SessionCommand
import androidx.media3.session.SessionResult
import androidx.media3.session.SessionError
import com.google.android.gms.cast.framework.CastContext
import com.google.android.gms.common.ConnectionResult
import com.google.android.gms.common.GoogleApiAvailability
import com.google.common.collect.ImmutableList
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import com.podly.appGraph
import com.podly.data.db.EpisodeEntity
import com.podly.data.db.ListeningSegmentEntity
import com.podly.network.Http
import com.podly.radio.RadioProfiles
import com.podly.ui.MainActivity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.asExecutor
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.guava.future
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume

/**
 * Media3 library service used both by the in-app player UI and Android Auto.
 *
 * Car/headset "previous" and "next" buttons are remapped to small in-episode
 * jumps (seekBack/seekForward) via [NudgingPlayer].
 */
@OptIn(UnstableApi::class)
class PlaybackService : MediaLibraryService() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var session: MediaLibrarySession? = null
    private var activeListenSegment: ActiveListenSegment? = null

    /** Episode whose saved position is about to be restored; saveProgress must not clobber it. */
    private var pendingResumeEpisodeId: String? = null

    private var recoveryJob: Job? = null
    private var recoveryAttempts = 0

    /** Always the local ExoPlayer; [castPlayer] takes over the session while casting. */
    private lateinit var localPlayer: NudgingPlayer
    private var castPlayer: NudgingPlayer? = null
    private var rawCastPlayer: CastPlayer? = null

    private var seekBackMs = 10_000L
    private var seekForwardMs = 30_000L

    // Mirrors session.player. MediaSession's getters assert the application
    // thread, but LibraryCallback resolves media items on Dispatchers.IO, so
    // reading session.player there threw and failed the whole session operation.
    @Volatile private var activePlayerOrNull: Player? = null
    @Volatile private var casting = false

    /** Non-null exactly while a radio session owns the queue. */
    @Volatile private var radio: RadioSessionState? = null
    /**
     * The current radio pick, until it has been listened to long enough to count.
     * While an episode is on probation nothing is written about it — no progress, no
     * listening segments, no auto-download — so a rejected pick leaves no trace in
     * Continue listening or History. Everything withheld is written on commit.
     */
    private var probation: Probation? = null
    private var radioRefillJob: Job? = null

    override fun onCreate() {
        super.onCreate()
        val graph = appGraph

        val httpDataSourceFactory = OkHttpDataSource.Factory(Http.client)
        // Streamed audio goes through the LRU cache; file:// URIs (downloads) bypass it
        // because DefaultDataSource only delegates http(s) to this factory.
        val cacheDataSourceFactory = CacheDataSource.Factory()
            .setCache(MediaCache.get(this))
            .setUpstreamDataSourceFactory(httpDataSourceFactory)
            .setFlags(CacheDataSource.FLAG_IGNORE_CACHE_ON_ERROR)
        val dataSourceFactory = DefaultDataSource.Factory(this, cacheDataSourceFactory)

        val exoPlayer = ExoPlayer.Builder(this)
            .setMediaSourceFactory(DefaultMediaSourceFactory(dataSourceFactory))
            // Audio is cheap to buffer (~10 MB per 10 min at 128 kbps): keeping
            // 5-10 minutes ahead rides out dead zones like leaving home Wi-Fi,
            // instead of failing 50 seconds after the network goes bad.
            .setLoadControl(
                DefaultLoadControl.Builder()
                    .setBufferDurationsMs(
                        /* minBufferMs = */ 300_000,
                        /* maxBufferMs = */ 600_000,
                        DefaultLoadControl.DEFAULT_BUFFER_FOR_PLAYBACK_MS,
                        DefaultLoadControl.DEFAULT_BUFFER_FOR_PLAYBACK_AFTER_REBUFFER_MS,
                    )
                    .build()
            )
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(C.USAGE_MEDIA)
                    .setContentType(C.AUDIO_CONTENT_TYPE_SPEECH)
                    .build(),
                /* handleAudioFocus = */ true,
            )
            .setHandleAudioBecomingNoisy(true)
            .build()

        val player = NudgingPlayer(exoPlayer)
        localPlayer = player
        // Nudge increments track Settings live (no runBlocking, no service restart).
        scope.launch {
            graph.settings.settings.collect { settings ->
                seekBackMs = settings.seekBackSeconds * 1000L
                seekForwardMs = settings.seekForwardSeconds * 1000L
                player.seekBackMs = seekBackMs
                player.seekForwardMs = seekForwardMs
                castPlayer?.seekBackMs = seekBackMs
                castPlayer?.seekForwardMs = seekForwardMs
            }
        }
        session = MediaLibrarySession.Builder(this, player, LibraryCallback())
            .setSessionActivity(mainActivityIntent())
            .build()
        activePlayerOrNull = player

        startProgressPersistence(player)
        startNetworkErrorRecovery(player)
        setUpCast()
    }

    /**
     * Wires up Chromecast when Play Services can provide it. A connected cast
     * session takes over the MediaSession via [transferPlaybackTo], so the
     * in-app UI and Android Auto keep talking to the same session either way.
     */
    private fun setUpCast() {
        // getSharedInstance must be called from the main thread — off it the Cast
        // SDK throws and casting silently never initialises. The Executor
        // argument is what keeps the heavy work (a Play Services Dynamite module
        // load, ~700ms observed) off the main thread, so onCreate still returns
        // promptly for Android Auto's first browse request. Failures are logged
        // rather than swallowed: a silent one looks exactly like "cast connects
        // but audio stays on the phone".
        val available = runCatching {
            GoogleApiAvailability.getInstance()
                .isGooglePlayServicesAvailable(this) == ConnectionResult.SUCCESS
        }.getOrDefault(false)
        if (!available) return
        val task = runCatching {
            CastContext.getSharedInstance(applicationContext, Dispatchers.IO.asExecutor())
        }.onFailure { Log.w(TAG, "Cast unavailable; playback stays local", it) }.getOrNull()
        task?.addOnFailureListener { Log.w(TAG, "Cast init failed; playback stays local", it) }
        task?.addOnSuccessListener { castContext ->
            if (session == null) return@addOnSuccessListener
            val cast = CastPlayer(castContext)
            rawCastPlayer = cast
            castPlayer = NudgingPlayer(cast).apply {
                seekBackMs = this@PlaybackService.seekBackMs
                seekForwardMs = this@PlaybackService.seekForwardMs
            }
            cast.setSessionAvailabilityListener(object : SessionAvailabilityListener {
                override fun onCastSessionAvailable() {
                    castPlayer?.let(::transferPlaybackTo)
                }

                override fun onCastSessionUnavailable() {
                    transferPlaybackTo(localPlayer)
                }
            })
            if (cast.isCastSessionAvailable) castPlayer?.let(::transferPlaybackTo)
        }
    }

    /**
     * Moves the queue, index, position and play/pause state onto [target] and
     * hands it the MediaSession. Items are rebuilt for the destination because
     * a Cast device needs the streaming URL where local playback prefers the
     * downloaded file.
     */
    private fun transferPlaybackTo(target: NudgingPlayer) {
        val session = this.session ?: return
        val source = session.player
        if (source === target) return

        val episodeIds = (0 until source.mediaItemCount).mapNotNull { index ->
            MediaIds.episodeIdOrNull(source.getMediaItemAt(index).mediaId)
        }
        val index = source.currentMediaItemIndex.coerceAtLeast(0)
        val position = source.currentPosition
        val wasPlaying = source.playWhenReady

        source.pause()
        // Progress bookkeeping follows the session's player rather than firing
        // from both; the inactive player would otherwise report its own stop.
        source.removeListener(progressListener)
        target.addListener(progressListener)
        session.setPlayer(target)
        val toCast = target === castPlayer
        activePlayerOrNull = target
        casting = toCast

        if (episodeIds.isEmpty()) return
        scope.launch {
            val dao = appGraph.database.episodeDao()
            val episodes = episodeIds.mapNotNull { dao.byId(it) }
            if (episodes.isEmpty()) return@launch
            val resolved = episodes.map { MediaItemFactory.playable(it, forCast = toCast) }
            val safeIndex = index.coerceIn(0, resolved.size - 1)
            // Same 512 KB Cast message ceiling as onSetMediaItems.
            val (items, startIndex) =
                if (toCast) castQueueWindow(resolved, safeIndex) else resolved to safeIndex
            target.setMediaItems(items, startIndex, position)
            target.prepare()
            target.playWhenReady = wasPlaying
            if (toCast && episodes.any { MediaItemFactory.localUriOrNull(it) != null }) {
                // Worth saying out loud: the user deliberately downloaded these.
                appGraph.messages.post(
                    "Casting streams over the network — a Cast device can't play downloaded audio."
                )
            }
        }
    }

    /** The player currently driving the session (local, or the Cast player). */
    private fun activePlayer(): Player = activePlayerOrNull ?: localPlayer

    /** True while a Cast device owns the session, so items must use streaming URLs. */
    private fun isCasting(): Boolean = casting


    /**
     * Streaming dies permanently on a few seconds of bad network (e.g. the
     * Wi-Fi → cellular handoff when leaving home): the default load policy
     * retries only briefly, then parks the player in IDLE with a fatal source
     * error. For network-flavored errors, wait until the OS reports validated
     * internet again and re-prepare — the player keeps its queue, position,
     * and playWhenReady, so playback resumes on its own. The "press play to
     * retry" path in PlayerConnection stays as the fallback for everything else.
     */
    private fun startNetworkErrorRecovery(player: Player) {
        player.addListener(object : Player.Listener {
            override fun onPlayerError(error: PlaybackException) {
                if (error.errorCode !in RECOVERABLE_ERROR_CODES) return
                if (recoveryAttempts >= MAX_RECOVERY_ATTEMPTS) return
                recoveryAttempts++
                recoveryJob?.cancel()
                recoveryJob = scope.launch {
                    awaitInternet()
                    delay(1_000L * recoveryAttempts)
                    // prepare() alone resumes playback: playWhenReady survives
                    // the error, and a repeat failure lands back here.
                    if (player.playbackState == Player.STATE_IDLE) player.prepare()
                }
            }

            override fun onPlaybackStateChanged(playbackState: Int) {
                if (playbackState == Player.STATE_READY) recoveryAttempts = 0
            }
        })
    }

    /** Suspends until the OS reports a validated internet connection. */
    private suspend fun awaitInternet() {
        val connectivity = getSystemService(ConnectivityManager::class.java) ?: return
        val validatedNow = connectivity.activeNetwork
            ?.let(connectivity::getNetworkCapabilities)
            ?.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED) == true
        if (validatedNow) return
        suspendCancellableCoroutine { continuation ->
            val request = NetworkRequest.Builder()
                .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                .addCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
                .build()
            val callback = object : ConnectivityManager.NetworkCallback() {
                override fun onAvailable(network: Network) {
                    runCatching { connectivity.unregisterNetworkCallback(this) }
                    if (continuation.isActive) continuation.resume(Unit)
                }
            }
            connectivity.registerNetworkCallback(request, callback)
            continuation.invokeOnCancellation {
                runCatching { connectivity.unregisterNetworkCallback(callback) }
            }
        }
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaLibrarySession? =
        session

    override fun onTaskRemoved(rootIntent: Intent?) {
        val player = session?.player
        if (player == null || !player.playWhenReady || player.mediaItemCount == 0) {
            stopSelf()
        }
    }

    override fun onDestroy() {
        // Capture the in-flight segment synchronously; the insert outlives this
        // service on the app-wide scope instead of blocking the main thread.
        takePendingSegment()?.let { segment ->
            // Drop it if it belongs to a pick still on probation: losing a few
            // seconds of history beats recording something the user never kept.
            if (!isOnProbation(segment.episodeId)) {
                appGraph.applicationScope.launch {
                    appGraph.database.episodeDao().insertListeningSegment(segment)
                }
            }
        }
        scope.cancel()
        rawCastPlayer?.setSessionAvailabilityListener(null)
        session?.release()
        // Release both players explicitly: while casting, session.player is the
        // Cast player and the local ExoPlayer would otherwise leak.
        castPlayer?.release()
        if (::localPlayer.isInitialized) localPlayer.release()
        castPlayer = null
        rawCastPlayer = null
        session = null
        super.onDestroy()
    }

    private fun mainActivityIntent(): PendingIntent =
        PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
            },
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )

    /**
     * Remaps next/previous (car & headset buttons) to in-episode nudges.
     * seekToNext/PreviousMediaItem stay untouched so the in-app UI can still
     * move through the queue. Increments are mutable so Settings changes apply
     * to the running player.
     */
    private class NudgingPlayer(player: Player) : ForwardingPlayer(player) {
        @Volatile var seekBackMs: Long = 10_000
        @Volatile var seekForwardMs: Long = 30_000

        override fun seekToNext() = seekForward()
        override fun seekToPrevious() = seekBack()

        override fun seekBack() = seekTo((currentPosition - seekBackMs).coerceAtLeast(0))

        override fun seekForward() {
            val target = currentPosition + seekForwardMs
            val duration = duration
            seekTo(if (duration == C.TIME_UNSET) target else target.coerceAtMost(duration))
        }

        override fun getSeekBackIncrement(): Long = seekBackMs

        override fun getSeekForwardIncrement(): Long = seekForwardMs

        override fun getAvailableCommands(): Player.Commands =
            super.getAvailableCommands().buildUpon()
                .addAll(
                    Player.COMMAND_SEEK_TO_NEXT,
                    Player.COMMAND_SEEK_TO_PREVIOUS,
                )
                .build()

        override fun isCommandAvailable(command: Int): Boolean =
            when (command) {
                Player.COMMAND_SEEK_TO_NEXT,
                Player.COMMAND_SEEK_TO_PREVIOUS,
                -> true
                else -> super.isCommandAvailable(command)
            }
    }

    /** Persists playback position every few seconds so episodes resume where they left off. */
    private fun startProgressPersistence(player: Player) {
        scope.launch {
            while (isActive) {
                delay(5_000)
                val active = activePlayer()
                recordListeningProgress(active)
                maybeCommitRadioEpisode(active)
                saveProgress(active)
            }
        }
        player.addListener(progressListener)
    }

    /**
     * Progress and listening-segment bookkeeping. Held as a field because
     * [transferPlaybackTo] moves it between the local and Cast players as the
     * session changes hands, so it reads the active player instead of a
     * captured one.
     */
    private val progressListener = object : Player.Listener {
        override fun onIsPlayingChanged(isPlaying: Boolean) {
            val player = activePlayer()
            scope.launch {
                if (isPlaying) {
                    beginListeningSegment(player)
                    // A playing episode counts as "started": queue a Wi-Fi
                    // download so it survives leaving the network (no-op
                    // when disabled, downloaded, completed, or blocked).
                    // Not while on probation: radio would otherwise fill the phone
                    // with audio the user is about to skip.
                    player.currentEpisodeIdOrNull()?.let {
                        if (!isOnProbation(it)) appGraph.downloader.autoDownloadStartedEpisode(it)
                    }
                } else {
                    recordListeningProgress(player)
                    flushListeningSegment()
                    saveProgress(player)
                }
            }
        }

        override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
            val player = activePlayer()
            // Auto-advance and in-queue jumps start episodes at 0; restore the saved
            // position instead. The initial queue set is handled by onSetMediaItems.
            if (reason == Player.MEDIA_ITEM_TRANSITION_REASON_AUTO ||
                reason == Player.MEDIA_ITEM_TRANSITION_REASON_SEEK
            ) {
                mediaItem?.mediaId?.let(MediaIds::episodeIdOrNull)?.let { episodeId ->
                    pendingResumeEpisodeId = episodeId
                    scope.launch { resumeSavedPosition(player, episodeId) }
                }
            }
            // Keyed on the episode changing, never the index: trimming played items
            // shifts currentMediaItemIndex and reports a PLAYLIST_CHANGED transition,
            // and treating that as a skip would reset probation on every refill.
            val newEpisodeId = mediaItem?.mediaId?.let(MediaIds::episodeIdOrNull)
            if (radio != null && newEpisodeId != probation?.episodeId) {
                probation = newEpisodeId?.let { Probation(it) }
            }
            scope.launch {
                flushListeningSegment()
                if (player.isPlaying) beginListeningSegment(player)
                snapshotQueue(player)
                // Frees space right after an episode finishes (no-op unless enabled).
                appGraph.downloader.deleteCompletedDownloads()
                if (radio != null) scheduleRadioRefill()
            }
        }

        override fun onPositionDiscontinuity(
            oldPosition: PositionInfo,
            newPosition: PositionInfo,
            reason: Int,
        ) {
            val player = activePlayer()
            scope.launch {
                flushListeningSegment()
                if (player.isPlaying) beginListeningSegment(player)
            }
        }

        override fun onTimelineChanged(timeline: androidx.media3.common.Timeline, reason: Int) {
            scope.launch { snapshotQueue(activePlayer()) }
        }
    }

    private fun beginListeningSegment(player: Player) {
        val episodeId = player.currentEpisodeIdOrNull() ?: return
        val position = player.currentPosition.coerceAtLeast(0)
        val now = System.currentTimeMillis()
        activeListenSegment = ActiveListenSegment(
            episodeId = episodeId,
            startPositionMs = position,
            endPositionMs = position,
            startedAt = now,
            endedAt = now,
        )
    }

    private suspend fun recordListeningProgress(player: Player) {
        if (!player.isPlaying) return
        val episodeId = player.currentEpisodeIdOrNull() ?: return
        val position = player.currentPosition.coerceAtLeast(0)
        val now = System.currentTimeMillis()
        val active = activeListenSegment
        if (active == null || active.episodeId != episodeId || !active.canContinueTo(position)) {
            flushListeningSegment()
            activeListenSegment = ActiveListenSegment(
                episodeId = episodeId,
                startPositionMs = position,
                endPositionMs = position,
                startedAt = now,
                endedAt = now,
            )
        } else {
            activeListenSegment = active.copy(
                endPositionMs = position.coerceAtLeast(active.endPositionMs),
                endedAt = now,
            )
        }
    }

    /** Detaches the active segment, or null if there's nothing worth persisting. */
    private fun takePendingSegment(): ListeningSegmentEntity? {
        val active = activeListenSegment ?: return null
        activeListenSegment = null
        if (active.endPositionMs - active.startPositionMs < MIN_LISTEN_SEGMENT_MS) return null
        return ListeningSegmentEntity(
            episodeId = active.episodeId,
            startPositionMs = active.startPositionMs,
            endPositionMs = active.endPositionMs,
            startedAt = active.startedAt,
            endedAt = active.endedAt,
            profileId = appGraph.radioSession.currentProfileIdOrNull(),
        )
    }

    /**
     * Buffers the segment instead of inserting it while its episode is on probation.
     * Keyed on the segment's own episode, not the current one: a flush at a queue
     * transition belongs to the episode that just ended.
     */
    private suspend fun flushListeningSegment() {
        val segment = takePendingSegment() ?: return
        val pending = probation
        if (radio != null && pending != null && pending.episodeId == segment.episodeId) {
            pending.segments += segment
            pending.listenedMs +=
                (segment.endPositionMs - segment.startPositionMs).coerceAtLeast(0)
            return
        }
        appGraph.database.episodeDao().insertListeningSegment(segment)
    }

    /** True while [episodeId] is a radio pick the user has not yet stuck with. */
    private fun isOnProbation(episodeId: String): Boolean =
        radio != null && probation?.episodeId == episodeId

    /** Includes the in-flight segment, so a continuously playing pick commits on time. */
    private fun probationListenedMs(): Long {
        val pending = probation ?: return 0
        val live = activeListenSegment
            ?.takeIf { it.episodeId == pending.episodeId }
            ?.let { (it.endPositionMs - it.startPositionMs).coerceAtLeast(0) }
            ?: 0
        return pending.listenedMs + live
    }

    /** Writes back everything probation withheld, once the pick has clearly been kept. */
    private suspend fun maybeCommitRadioEpisode(player: Player) {
        val pending = probation ?: return
        val session = radio ?: return
        val ended = player.playbackState == Player.STATE_ENDED
        if (probationListenedMs() < RADIO_COMMIT_MS && !ended) return
        probation = null
        val dao = appGraph.database.episodeDao()
        pending.segments.forEach { dao.insertListeningSegment(it) }
        appGraph.radio.onAccepted(session.profileId, pending.episodeId, pending.listenedMs)
        appGraph.downloader.autoDownloadStartedEpisode(pending.episodeId)
    }

    private fun Player.currentEpisodeIdOrNull(): String? =
        currentMediaItem?.mediaId?.let(MediaIds::episodeIdOrNull)

    private suspend fun resumeSavedPosition(player: Player, episodeId: String) {
        try {
            val episode = appGraph.database.episodeDao().byId(episodeId)
            if (player.currentEpisodeIdOrNull() == episodeId &&
                episode != null && !episode.completed && episode.playbackPositionMs > 0
            ) {
                player.seekTo(episode.playbackPositionMs)
            }
        } finally {
            if (pendingResumeEpisodeId == episodeId) pendingResumeEpisodeId = null
        }
    }

    /** Remembers the queue so onPlaybackResumption can restore it after process death. */
    private suspend fun snapshotQueue(player: Player) {
        val ids = (0 until player.mediaItemCount).mapNotNull { index ->
            MediaIds.episodeIdOrNull(player.getMediaItemAt(index).mediaId)
        }
        if (ids.isNotEmpty()) {
            appGraph.playbackState.save(ids, player.currentMediaItemIndex)
        }
    }

    private suspend fun saveProgress(player: Player) {
        if (player.playbackState != Player.STATE_READY && player.playbackState != Player.STATE_ENDED) return
        val mediaId = player.currentMediaItem?.mediaId ?: return
        val episodeId = MediaIds.episodeIdOrNull(mediaId) ?: return
        // A queue transition is still restoring this episode's saved position;
        // saving now would overwrite it with ~0.
        if (episodeId == pendingResumeEpisodeId) return
        if (isOnProbation(episodeId)) {
            // Withhold the position, but still record duration: it is harmless, and
            // without it the Up next sheet shows 0:00 for every undecided pick.
            // Withholding rather than writing 0 matters — a zero would clobber real
            // progress when radio happens to serve a part-heard episode.
            val pendingDuration = player.duration.takeIf { it != C.TIME_UNSET } ?: 0L
            if (pendingDuration > 0) {
                appGraph.database.episodeDao().updateDuration(episodeId, pendingDuration)
            }
            return
        }
        val position = player.currentPosition
        val duration = player.duration.takeIf { it != C.TIME_UNSET } ?: 0L
        val completed = duration > 0 && position >= duration - 10_000
        val dao = appGraph.database.episodeDao()
        dao.updateProgress(episodeId, if (completed) 0 else position, completed, System.currentTimeMillis())
        if (duration > 0) dao.updateDuration(episodeId, duration)
    }

    // ---- radio ---------------------------------------------------------------

    /** Builds resolved, playable items for the next radio picks. */
    private suspend fun radioItems(
        profileId: String,
        count: Int,
        exclude: Set<String> = emptySet(),
    ): List<Pair<MediaItem, EpisodeEntity>> {
        val profile = RadioProfiles.byId(profileId)
        return appGraph.radio.nextBatch(profile, count, exclude)
            .map { MediaItemFactory.playable(it, forCast = isCasting()) to it }
    }

    /**
     * Starts a radio session. Note the explicit start position: a service-side
     * setMediaItems does not pass through onSetMediaItems, so the C.TIME_UNSET
     * convention that normally restores a saved position does not apply here.
     */
    private suspend fun startRadio(profileId: String, firstEpisodeId: String? = null) {
        // Starting on a chosen episode still starts a *session*: the rest of the
        // queue is the usual picks, so Next keeps working from there.
        val chosen = firstEpisodeId?.let { appGraph.podcasts.episodeById(it) }
        val rest = radioItems(
            profileId,
            RADIO_LOOKAHEAD + if (chosen == null) 1 else 0,
            exclude = setOfNotNull(chosen?.id),
        )
        val built = if (chosen != null) {
            listOf(MediaItemFactory.playable(chosen, forCast = isCasting()) to chosen) + rest
        } else {
            rest
        }
        if (built.isEmpty()) {
            appGraph.messages.post("Radio has nothing to play for this profile yet.")
            return
        }
        val player = activePlayer()
        val first = built.first().second
        val items = built.map { it.first }
        val (window, index) = if (isCasting()) castQueueWindow(items, 0) else items to 0
        radio = RadioSessionState(profileId)
        probation = Probation(first.id)
        appGraph.radioSession.start(profileId)
        publishRadioExtras(profileId)
        player.setMediaItems(window, index, first.playbackPositionMs.takeIf { !first.completed } ?: 0L)
        player.prepare()
        player.play()
        snapshotQueue(player)
    }

    /** "Not now": cooldown the current pick and move on. */
    private suspend fun skipRadio() {
        val session = radio ?: return
        val player = activePlayer()
        val episodeId = player.currentEpisodeIdOrNull() ?: return
        val listened = probationListenedMs()
        probation = null
        appGraph.radio.onSkipped(session.profileId, episodeId, listened)
        if (!player.hasNextMediaItem()) refillRadioQueue()
        if (player.hasNextMediaItem()) {
            player.seekToNextMediaItem()
            player.play()
        } else {
            appGraph.messages.post("Radio is out of picks for now.")
        }
        scheduleRadioRefill()
    }

    /** Leaves radio; whatever is already queued keeps playing as an ordinary queue. */
    private suspend fun stopRadio() {
        radio = null
        probation = null
        radioRefillJob?.cancel()
        appGraph.radioSession.end()
        publishRadioExtras(null)
        snapshotQueue(activePlayer())
    }

    /**
     * Tells controllers which profile (if any) owns the queue. Must run on the
     * application thread — MediaSession's setters assert it.
     */
    private fun publishRadioExtras(profileId: String?) {
        val current = session ?: return
        current.setSessionExtras(
            Bundle().apply { profileId?.let { putString(RadioCommands.EXTRA_PROFILE_ID, it) } },
        )
    }

    private fun scheduleRadioRefill() {
        if (radio == null || radioRefillJob?.isActive == true) return
        radioRefillJob = scope.launch { refillRadioQueue() }
    }

    private suspend fun refillRadioQueue() {
        val session = radio ?: return
        val player = activePlayer()
        val plan = radioQueuePlan(player.mediaItemCount, player.currentMediaItemIndex)
        plan.trimRange?.let { player.removeMediaItems(it.first, it.last + 1) }
        if (plan.fetchCount == 0) return
        if (!player.isCommandAvailable(Player.COMMAND_CHANGE_MEDIA_ITEMS)) return
        val queued = (0 until player.mediaItemCount)
            .mapNotNull { MediaIds.episodeIdOrNull(player.getMediaItemAt(it).mediaId) }
            .toSet()
        val built = radioItems(session.profileId, plan.fetchCount, exclude = queued)
        if (built.isEmpty()) return
        player.addMediaItems(built.map { it.first })
    }

    private inner class LibraryCallback : MediaLibrarySession.Callback {

        /**
         * Runs synchronously on the application thread, so it must not touch the
         * database or DataStore — it only adds the radio commands to whatever the
         * default result already grants, preserving media3's trusted/untrusted split.
         */
        override fun onConnect(
            session: MediaSession,
            controller: MediaSession.ControllerInfo,
        ): MediaSession.ConnectionResult {
            // Deliberately not derived from super.onConnect(): that returns an empty
            // command set (session=0, player=0), so granting only what it contains
            // leaves every controller with an empty timeline — which silently blanks
            // the mini player and the radio card while audio plays fine.
            // Grant the library defaults plus the radio commands instead.
            return MediaSession.ConnectionResult.AcceptedResultBuilder(session)
                .setAvailableSessionCommands(
                    MediaSession.ConnectionResult.DEFAULT_SESSION_AND_LIBRARY_COMMANDS
                        .buildUpon()
                        .add(RadioCommands.START)
                        .add(RadioCommands.SKIP)
                        .add(RadioCommands.STOP)
                        .build()
                )
                .build()
        }

        override fun onCustomCommand(
            session: MediaSession,
            controller: MediaSession.ControllerInfo,
            customCommand: SessionCommand,
            args: Bundle,
        ): ListenableFuture<SessionResult> {
            when (customCommand.customAction) {
                RadioCommands.ACTION_START -> scope.launch {
                    val profileId = args.getString(RadioCommands.EXTRA_PROFILE_ID)
                        ?: appGraph.radioProfiles.currentProfileId()
                    startRadio(profileId, args.getString(RadioCommands.EXTRA_EPISODE_ID))
                }
                RadioCommands.ACTION_SKIP -> scope.launch { skipRadio() }
                RadioCommands.ACTION_STOP -> scope.launch { stopRadio() }
                else -> return Futures.immediateFuture(
                    SessionResult(SessionError.ERROR_NOT_SUPPORTED)
                )
            }
            // Immediate: the work runs on the app thread via scope, and returning a
            // coroutine future here would put session work on a caller's thread.
            return Futures.immediateFuture(SessionResult(SessionResult.RESULT_SUCCESS))
        }

        override fun onGetLibraryRoot(
            session: MediaLibrarySession,
            browser: MediaSession.ControllerInfo,
            params: LibraryParams?,
        ): ListenableFuture<LibraryResult<MediaItem>> =
            // Must already be complete. Legacy browsers (Android Auto, Bluetooth,
            // system media resumption) reach this through Media3's
            // MediaLibraryServiceLegacyStub.onGetRoot, which blocks the main
            // thread until this future completes and — since media3 1.11 —
            // observes completion via a callback posted to that same, blocked
            // main thread. A future finishing on any other thread therefore
            // never unblocks it: the app hangs and ANRs ("executing service
            // PlaybackService, waited 200003ms"). LegacyBrowserConnectTest
            // covers this on-device.
            Futures.immediateFuture(
                LibraryResult.ofItem(
                    MediaItemFactory.folder(MediaIds.ROOT, "Podly", childrenAreEpisodes = false),
                    params,
                )
            )

        override fun onGetChildren(
            session: MediaLibrarySession,
            browser: MediaSession.ControllerInfo,
            parentId: String,
            page: Int,
            pageSize: Int,
            params: LibraryParams?,
        ): ListenableFuture<LibraryResult<ImmutableList<MediaItem>>> = scope.future(Dispatchers.IO) {
            val graph = appGraph
            val children: List<MediaItem> = when {
                parentId == MediaIds.ROOT -> listOf(
                    MediaItemFactory.folder(MediaIds.NODE_CONTINUE, "Continue"),
                    MediaItemFactory.folder(MediaIds.NODE_PLAYLISTS, "Playlists", childrenAreEpisodes = false),
                    MediaItemFactory.folder(MediaIds.NODE_LIBRARY, "Library"),
                    MediaItemFactory.folder(MediaIds.NODE_DOWNLOADS, "Downloads"),
                )
                parentId == MediaIds.NODE_CONTINUE ->
                    graph.database.episodeDao().continueListeningOnce(MAX_BROWSE_CHILDREN)
                        .map(MediaItemFactory::browsableEpisode)
                parentId == MediaIds.NODE_PLAYLISTS ->
                    graph.database.playlistDao().playlistsOnce()
                        .take(MAX_BROWSE_CHILDREN)
                        .map { playlist ->
                            MediaItemFactory.folder(MediaIds.playlist(playlist.id), playlist.name)
                        }
                parentId == MediaIds.NODE_LIBRARY ->
                    graph.database.episodeDao().libraryEpisodesOnce()
                        .take(MAX_BROWSE_CHILDREN)
                        .map(MediaItemFactory::browsableEpisode)
                parentId == MediaIds.NODE_DOWNLOADS ->
                    graph.database.episodeDao().downloadedEpisodesOnce()
                        .take(MAX_BROWSE_CHILDREN)
                        .map(MediaItemFactory::browsableEpisode)
                else -> MediaIds.playlistIdOrNull(parentId)?.let { playlistId ->
                    graph.playlists.sortedEpisodesOnce(playlistId)
                        .take(MAX_BROWSE_CHILDREN)
                        .map(MediaItemFactory::browsableEpisode)
                } ?: emptyList()
            }
            LibraryResult.ofItemList(ImmutableList.copyOf(children), params)
        }

        override fun onGetItem(
            session: MediaLibrarySession,
            browser: MediaSession.ControllerInfo,
            mediaId: String,
        ): ListenableFuture<LibraryResult<MediaItem>> = scope.future(Dispatchers.IO) {
            val episodeId = MediaIds.episodeIdOrNull(mediaId)
            val episode = episodeId?.let { appGraph.podcasts.episodeById(it) }
            if (episode != null) {
                LibraryResult.ofItem(MediaItemFactory.browsableEpisode(episode), null)
            } else {
                LibraryResult.ofError(SessionError.ERROR_BAD_VALUE)
            }
        }

        /** Resolves bare mediaIds (from Auto or the in-app UI) into playable items with URIs. */
        override fun onAddMediaItems(
            mediaSession: MediaSession,
            controller: MediaSession.ControllerInfo,
            mediaItems: List<MediaItem>,
        ): ListenableFuture<List<MediaItem>> = scope.future(Dispatchers.IO) {
            mediaItems.mapNotNull { item -> resolve(item) }
        }

        override fun onSetMediaItems(
            mediaSession: MediaSession,
            controller: MediaSession.ControllerInfo,
            mediaItems: List<MediaItem>,
            startIndex: Int,
            startPositionMs: Long,
        ): ListenableFuture<MediaSession.MediaItemsWithStartPosition> = scope.future(Dispatchers.IO) {
            // Setting a queue from anywhere else is an explicit choice, so it ends the
            // radio session. Without this the session would leak: the hardware skip
            // remap and the withholding of progress would both stay armed over
            // ordinary listening.
            if (radio != null) {
                radio = null
                scope.launch { stopRadio() }
            }
            val forCast = isCasting()
            val resolvedPairs = mediaItems.mapNotNull { item ->
                val episodeId = MediaIds.episodeIdOrNull(item.mediaId)
                val episode = episodeId?.let { appGraph.podcasts.episodeById(it) }
                if (episode != null) MediaItemFactory.playable(episode, forCast) to episode else null
            }
            val resolved = resolvedPairs.map { it.first }
            val safeIndex = startIndex.coerceIn(0, (resolved.size - 1).coerceAtLeast(0))
            val resumePosition = when {
                startPositionMs != C.TIME_UNSET && startPositionMs > 0 -> startPositionMs
                else -> resolvedPairs.getOrNull(safeIndex)?.second
                    ?.takeIf { !it.completed }?.playbackPositionMs ?: 0L
            }
            val (items, index) = if (forCast) castQueueWindow(resolved, safeIndex) else resolved to safeIndex
            MediaSession.MediaItemsWithStartPosition(items, index, resumePosition)
        }

        private suspend fun resolve(item: MediaItem): MediaItem? {
            val episodeId = MediaIds.episodeIdOrNull(item.mediaId) ?: return null
            val episode: EpisodeEntity = appGraph.podcasts.episodeById(episodeId) ?: return null
            return MediaItemFactory.playable(episode, forCast = isCasting())
        }

        /**
         * Android Auto / system "resume" entry point after process death: restore the
         * last queue and position, falling back to the most recent in-progress episode.
         */
        override fun onPlaybackResumption(
            mediaSession: MediaSession,
            controller: MediaSession.ControllerInfo,
        ): ListenableFuture<MediaSession.MediaItemsWithStartPosition> = scope.future(Dispatchers.IO) {
            val graph = appGraph
            val saved = graph.playbackState.load()
            val episodes: List<EpisodeEntity>
            val startIndex: Int
            if (saved != null) {
                val byId = saved.episodeIds
                    .mapNotNull { graph.podcasts.episodeById(it) }
                    .associateBy { it.id }
                episodes = saved.episodeIds.mapNotNull(byId::get)
                val savedEpisodeId = saved.episodeIds.getOrNull(saved.currentIndex)
                startIndex = episodes.indexOfFirst { it.id == savedEpisodeId }.coerceAtLeast(0)
            } else {
                episodes = graph.database.episodeDao().continueListeningOnce(1)
                    .ifEmpty { graph.database.episodeDao().libraryEpisodesOnce().take(1) }
                startIndex = 0
            }
            if (episodes.isEmpty()) {
                return@future MediaSession.MediaItemsWithStartPosition(emptyList(), 0, 0L)
            }
            val current = episodes[startIndex]
            val resumePosition = current.playbackPositionMs.takeIf { !current.completed } ?: 0L
            MediaSession.MediaItemsWithStartPosition(
                episodes.map(MediaItemFactory::playable),
                startIndex,
                resumePosition,
            )
        }
    }

    companion object {
        private const val MAX_BROWSE_CHILDREN = 100
        private const val MIN_LISTEN_SEGMENT_MS = 1_000L

        /** Listening this long makes a radio pick count: it stops being probationary. */
        private const val RADIO_COMMIT_MS = 90_000L
        private const val CONTINUOUS_POSITION_TOLERANCE_MS = 12_000L
        private const val TAG = "PodlyPlayback"
        private const val MAX_RECOVERY_ATTEMPTS = 5
        private val RECOVERABLE_ERROR_CODES = setOf(
            PlaybackException.ERROR_CODE_IO_UNSPECIFIED,
            PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED,
            PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_TIMEOUT,
        )
    }

    private data class RadioSessionState(val profileId: String)

    private class Probation(val episodeId: String) {
        var listenedMs: Long = 0
        val segments: MutableList<ListeningSegmentEntity> = mutableListOf()
    }

    private data class ActiveListenSegment(
        val episodeId: String,
        val startPositionMs: Long,
        val endPositionMs: Long,
        val startedAt: Long,
        val endedAt: Long,
    ) {
        fun canContinueTo(positionMs: Long): Boolean =
            positionMs >= endPositionMs &&
                positionMs - endPositionMs <= CONTINUOUS_POSITION_TOLERANCE_MS
    }
}
