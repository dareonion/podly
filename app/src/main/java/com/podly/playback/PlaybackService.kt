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
import androidx.media3.common.util.Util
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.cache.CacheDataSource
import androidx.media3.datasource.okhttp.OkHttpDataSource
import androidx.media3.cast.DefaultMediaItemConverter
import androidx.media3.cast.RemoteCastPlayer
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
import com.google.common.collect.ImmutableList
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import com.podly.appGraph
import com.podly.data.db.EpisodeEntity
import com.podly.data.db.ListeningSegmentEntity
import com.podly.network.Http
import com.podly.radio.RadioProfiles
import com.podly.ui.MainActivity
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.guava.future
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
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

    /**
     * The episode and player position the last progress write recorded, or, for an
     * episode loaded paused, the position it was loaded at. A paused player is only
     * saved once it has moved away from this.
     */
    private var lastSavedPosition: Pair<String, Long>? = null

    private var recoveryJob: Job? = null
    private var recoveryAttempts = 0

    /** Always the local ExoPlayer; [castPlayer] takes over the session while casting. */
    private lateinit var localPlayer: NudgingPlayer
    private var castPlayer: NudgingPlayer? = null
    private var rawCastPlayer: RemoteCastPlayer? = null
    private var castQueue: CastQueueMirror? = null

    private var seekBackMs = 10_000L
    private var seekForwardMs = 30_000L

    // Mirrors session.player. MediaSession's getters assert the application
    // thread, but LibraryCallback resolves media items on Dispatchers.IO, so
    // reading session.player there threw and failed the whole session operation.
    @Volatile private var activePlayerOrNull: Player? = null
    @Volatile private var casting = false

    /**
     * Bumped whenever something other than playback resumption sets the queue. A
     * resumption that finishes after a bump is dropped rather than replacing the
     * newer queue (see onPlaybackResumption).
     */
    private val queueGeneration = AtomicInteger()

    /**
     * Counts set requests as they arrive (onSetMediaItems, on the application thread),
     * and nothing else. A spoken request searches the library before it resolves, and
     * Media3 applies each request as it completes, so one that finds another set
     * request arrived after it is dropped. Not [queueGeneration]: that also moves when
     * an older operation finishes, which would drop the newer request instead.
     */
    private val setRequests = AtomicInteger()

    /** Non-null exactly while a radio session owns the queue. */
    @Volatile private var radio: RadioSessionState? = null
    /**
     * The current radio pick, until it has been listened to long enough to count.
     * While an episode is on probation nothing is written about it — no progress, no
     * listening segments, no auto-download — so a rejected pick leaves no trace in
     * Continue listening or History. Everything withheld is written on commit.
     *
     * Outlives [radio] on purpose: stopping radio is not the same as keeping what
     * was playing. It carries its own profile id so it can still commit once the
     * session that created it is gone.
     */
    private var probation: RadioProbation? = null
    private var radioRefillJob: Job? = null

    /**
     * Episodes the listener queued into a radio session themselves ("Play next",
     * "Add to queue"). Radio did not pick them, so they never go on probation and
     * "Not now" does not count against them. Written from onAddMediaItems on IO.
     */
    private val listenerQueued: MutableSet<String> = ConcurrentHashMap.newKeySet()

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
        // Unticking a show for a restricted profile while its radio plays removes that
        // show's picks already queued, not just ones a later refill would fetch.
        RadioProfiles.ALL.filter { it.restrictToSelectedShows }.forEach { profile ->
            scope.launch {
                graph.radioProfiles.selectedShows(profile.id).distinctUntilChanged().drop(1)
                    .collect { allowed ->
                        val current = radio
                        if (current?.profileId == profile.id) {
                            dropUntickedPicks(current, allowed)
                            activePlayer().currentEpisodeIdOrNull()
                                ?.takeIf { it !in listenerQueued }
                                ?.let { skipIfUnticked(current, it) }
                        }
                    }
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
        // The load runs in the background, so onCreate still returns promptly
        // for Android Auto's first browse request.
        initCast(this) ?: return
        val cast = runCatching {
            // Safe to build before the CastContext has loaded: Cast replays a
            // session that is already connected once the load completes.
            RemoteCastPlayer.Builder(applicationContext).build()
        }.onFailure { Log.w(TAG, "Cast player unavailable; playback stays local", it) }
            .getOrNull() ?: return
        rawCastPlayer = cast
        val queue = CastQueueMirror(cast)
        castQueue = queue
        castPlayer = NudgingPlayer(queue).apply {
            seekBackMs = this@PlaybackService.seekBackMs
            seekForwardMs = this@PlaybackService.seekForwardMs
        }
        cast.setSessionAvailabilityListener(object : SessionAvailabilityListener {
            override fun onCastSessionAvailable() {
                queue.attach(connectedReceiverQueue())
                castPlayer?.let(::transferPlaybackTo)
            }

            override fun onCastSessionUnavailable() {
                // Reads the whole queue first, while the mirror still knows it.
                transferPlaybackTo(localPlayer)
                queue.detach()
            }
        })
        // The listener misses a session the player found while being built
        // (the CastContext was already loaded earlier in this process).
        if (cast.isCastSessionAvailable) {
            queue.attach(connectedReceiverQueue())
            castPlayer?.let(::transferPlaybackTo)
        }
    }

    /** The connected Cast session's queue, which CastQueueMirror reads hidden items from. */
    private fun connectedReceiverQueue(): ReceiverQueue? = runCatching {
        CastContext.getSharedInstance()?.sessionManager?.currentCastSession?.remoteMediaClient?.mediaQueue
    }.onFailure { Log.w(TAG, "Cast queue unavailable", it) }
        .getOrNull()?.let { MediaQueueSource(it, DefaultMediaItemConverter()) }

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

        // From the Cast player, items the receiver hasn't sent yet must not read as removed.
        val handedBack = if (source === castPlayer) castQueue?.itemsForHandover() else null
        val episodeIds = (handedBack ?: (0 until source.mediaItemCount).map(source::getMediaItemAt))
            .map { MediaIds.episodeIdOrNull(it.mediaId) }
        val index = source.currentMediaItemIndex
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

        if (episodeIds.all { it == null }) return
        scope.launch {
            // Recounted around anything that can't be carried across, so the episode
            // that was current stays current.
            val (episodes, safeIndex) = compactAt(episodesInOrder(episodeIds), index)
            if (episodes.isEmpty()) return@launch
            val resolved = episodes.map { MediaItemFactory.playable(it, forCast = toCast) }
            // Same 512 KB Cast message ceiling as onSetMediaItems.
            val (items, startIndex) =
                if (toCast) castQueueWindow(resolved, safeIndex) else resolved to safeIndex
            // Before the items, not after: RemoteCastPlayer sends the load with
            // autoplay = its playWhenReady, which a fresh Cast session reports as
            // true, so casting a paused episode started it playing on the receiver.
            target.playWhenReady = wasPlaying
            queueGeneration.incrementAndGet()
            target.setMediaItems(items, startIndex, position)
            target.prepare()
            if (toCast && episodes.any { MediaItemFactory.localUriOrNull(it) != null }) {
                // Worth saying out loud: the user deliberately downloaded these.
                appGraph.messages.post(
                    "Casting streams over the network — a Cast device can't play downloaded audio."
                )
            }
        }
    }

    /** The episodes for [ids], in order, null where missing; in batches (see lookUpInOrder). */
    private suspend fun episodesInOrder(ids: List<String?>): List<EpisodeEntity?> =
        lookUpInOrder(ids, EpisodeEntity::id) { appGraph.podcasts.episodesByIds(it) }

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
        castQueue?.detach()
        castPlayer = null
        rawCastPlayer = null
        castQueue = null
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

    /**
     * Persists playback position every few seconds so episodes resume where they
     * left off. While paused only once something moved (a seek, here or from another
     * Cast sender) or a radio pick committed after it ended: re-saving an unchanged
     * paused position wrote `completed = false` back over "Mark played" on the
     * loaded episode.
     */
    private fun startProgressPersistence(player: Player) {
        scope.launch {
            while (isActive) {
                delay(5_000)
                val active = activePlayer()
                castQueue?.retryUnanswered()
                recordListeningProgress(active)
                // A pick committed here may already have ended, and its withheld
                // completion has no other chance to be written.
                val committed = maybeCommitRadioEpisode(active)
                if (active.isPlaying || committed || pausedPositionMoved(active)) saveProgress(active)
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
            if (newEpisodeId != probation?.episodeId) {
                // Cleared even outside radio, so a withheld pick can never outlive
                // the episode it belongs to and stall progress for anything else.
                val profileId = radio?.profileId
                probation = if (profileId != null && newEpisodeId != null && newEpisodeId !in listenerQueued) {
                    RadioProbation(newEpisodeId, profileId)
                } else {
                    null
                }
            }
            scope.launch {
                flushListeningSegment()
                if (player.isPlaying) beginListeningSegment(player)
                snapshotQueue(player)
                // Frees space right after an episode finishes (no-op unless enabled).
                appGraph.downloader.deleteCompletedDownloads()
                if (radio != null) scheduleRadioRefill()
            }
            val session = radio
            if (session != null && newEpisodeId != null && newEpisodeId !in listenerQueued &&
                RadioProfiles.byId(session.profileId).restrictToSelectedShows
            ) {
                scope.launch { skipIfUnticked(session, newEpisodeId) }
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
                // Saved straight away; if the player is still buffering, mark the
                // position unsaved so the next tick retries.
                if (reason == Player.DISCONTINUITY_REASON_SEEK && !player.isPlaying && !saveProgress(player)) {
                    player.currentEpisodeIdOrNull()?.let { lastSavedPosition = it to UNSAVED_POSITION }
                }
            }
        }

        override fun onTimelineChanged(timeline: androidx.media3.common.Timeline, reason: Int) {
            scope.launch {
                snapshotQueue(activePlayer())
                // A Cast item can become known only once it is already playing (a
                // placeholder resolved late), with no transition to run the check.
                val session = radio
                if (session != null && RadioProfiles.byId(session.profileId).restrictToSelectedShows) {
                    activePlayer().currentEpisodeIdOrNull()
                        ?.takeIf { it !in listenerQueued }
                        ?.let { skipIfUnticked(session, it) }
                }
            }
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
        if (pending != null && pending.covers(segment.episodeId)) {
            pending.buffer(segment)
            return
        }
        appGraph.database.episodeDao().insertListeningSegment(segment)
    }

    /**
     * True while [episodeId] is a radio pick the user has not yet stuck with.
     *
     * Deliberately not gated on `radio != null`: leaving radio does not earn a
     * pick its place in Continue listening, and clearing the gate on stop let the
     * next progress save write a fourteen-second pick straight into it. Bounded
     * by the episode id and cleared on every transition, so a stale one can never
     * withhold anything else.
     */
    private fun isOnProbation(episodeId: String): Boolean =
        probation?.covers(episodeId) == true

    /** The segment still in flight, which [RadioProbation] cannot see for itself. */
    private fun liveListenedMs(pending: RadioProbation): Long =
        activeListenSegment
            ?.takeIf { pending.covers(it.episodeId) }
            ?.let { (it.endPositionMs - it.startPositionMs).coerceAtLeast(0) }
            ?: 0

    /** What a skip reports as listened, in-flight segment included. */
    private fun probationListenedMs(): Long {
        val pending = probation ?: return 0
        return pending.listenedIncluding(liveListenedMs(pending))
    }

    /**
     * Writes back everything probation withheld, once the pick has clearly been
     * kept; true when it committed just now.
     */
    private suspend fun maybeCommitRadioEpisode(player: Player): Boolean {
        val pending = probation ?: return false
        val ended = player.playbackState == Player.STATE_ENDED
        if (!pending.shouldCommit(liveListenedMs(pending), ended)) return false
        probation = null
        val dao = appGraph.database.episodeDao()
        pending.segments.forEach { dao.insertListeningSegment(it) }
        appGraph.radio.onAccepted(pending.profileId, pending.episodeId, pending.listenedMs)
        appGraph.downloader.autoDownloadStartedEpisode(pending.episodeId)
        return true
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
        // Until the receiver has sent every item, the Cast queue would save short.
        if (player === castPlayer && castQueue?.isComplete() == false) return
        val (ids, index) = compactAt(
            (0 until player.mediaItemCount).map { MediaIds.episodeIdOrNull(player.getMediaItemAt(it).mediaId) },
            player.currentMediaItemIndex,
        )
        if (ids.isNotEmpty()) {
            appGraph.playbackState.save(ids, index)
        }
    }

    /**
     * Whether a paused [player] sits somewhere other than where its episode's
     * position was last saved. An episode that has had no save yet was just loaded,
     * at its own saved position, so that only sets the baseline: saving it would
     * undo a "Mark played" made before the first tick.
     */
    private fun pausedPositionMoved(player: Player): Boolean {
        val episodeId = player.currentEpisodeIdOrNull() ?: return false
        val now = episodeId to player.currentPosition
        val last = lastSavedPosition
        if (last == null || last.first != episodeId) {
            lastSavedPosition = now
            return false
        }
        return last != now
    }

    /** Writes the player's position (and completion) for its episode; false when it wrote none. */
    private suspend fun saveProgress(player: Player): Boolean {
        if (player.playbackState != Player.STATE_READY && player.playbackState != Player.STATE_ENDED) return false
        val mediaId = player.currentMediaItem?.mediaId ?: return false
        val episodeId = MediaIds.episodeIdOrNull(mediaId) ?: return false
        // A queue transition is still restoring this episode's saved position;
        // saving now would overwrite it with ~0.
        if (episodeId == pendingResumeEpisodeId) return false
        if (isOnProbation(episodeId)) {
            // Withhold the position, but still record duration: it is harmless, and
            // without it the Up next sheet shows 0:00 for every undecided pick.
            // Withholding rather than writing 0 matters — a zero would clobber real
            // progress when radio happens to serve a part-heard episode.
            val pendingDuration = player.duration.takeIf { it != C.TIME_UNSET } ?: 0L
            if (pendingDuration > 0) {
                appGraph.database.episodeDao().updateDuration(episodeId, pendingDuration)
            }
            return false
        }
        val position = player.currentPosition
        val duration = player.duration.takeIf { it != C.TIME_UNSET } ?: 0L
        val completed = duration > 0 && position >= duration - 10_000
        val dao = appGraph.database.episodeDao()
        dao.updateProgress(episodeId, if (completed) 0 else position, completed, System.currentTimeMillis())
        if (duration > 0) dao.updateDuration(episodeId, duration)
        lastSavedPosition = episodeId to position
        return true
    }

    // ---- radio ---------------------------------------------------------------

    /** Builds resolved, playable items for the next radio picks. */
    private suspend fun radioItems(
        profileId: String,
        count: Int,
        exclude: Set<String> = emptySet(),
        recordServes: Boolean = true,
    ): List<Pair<MediaItem, EpisodeEntity>> {
        val profile = RadioProfiles.byId(profileId)
        return appGraph.radio.nextBatch(profile, count, exclude, recordServes = recordServes)
            .map { MediaItemFactory.playable(it, forCast = isCasting()) to it }
    }

    /**
     * Starts a radio session. Note the explicit start position: a service-side
     * setMediaItems does not pass through onSetMediaItems, so the C.TIME_UNSET
     * convention that normally restores a saved position does not apply here.
     */
    private suspend fun startRadio(profileId: String, firstEpisodeId: String? = null) {
        // Starting on a chosen episode still starts a *session*: the rest of the
        // queue is the usual picks, so Next keeps working from there. The allowlist
        // binds that chosen episode too: a picks list loaded before its show was
        // unticked, or a controller's request, must not get round it.
        val allowed = if (RadioProfiles.byId(profileId).restrictToSelectedShows) {
            appGraph.radioProfiles.selectedShowsOnce(profileId)
        } else {
            null
        }
        val chosen = firstEpisodeId?.let { appGraph.podcasts.episodeById(it) }
            ?.takeIf { allowed == null || it.podcastId in allowed }
        val rest = radioItems(
            profileId,
            RADIO_LOOKAHEAD + if (chosen == null) 1 else 0,
            exclude = setOfNotNull(chosen?.id),
        )
        // Rechecked after the lookups above, which suspend, and before the session
        // changes hands, so a failed start leaves the old session as it was. What can
        // still slip through in the brief setup below is caught by [skipIfUnticked]
        // once it becomes current.
        val built = stillAllowed(
            profileId,
            if (chosen != null) {
                listOf(MediaItemFactory.playable(chosen, forCast = isCasting()) to chosen) + rest
            } else {
                rest
            },
        )
        if (built.isEmpty()) {
            appGraph.messages.post("Radio has nothing to play for this profile yet.")
            return
        }
        val player = activePlayer()
        val first = built.first().second
        val items = built.map { it.first }
        val (window, index) = if (isCasting()) castQueueWindow(items, 0) else items to 0
        // Only now that the new session can start: a switch to a profile with nothing
        // to play leaves the old session running, and it still needs its refill.
        radioRefillJob?.cancel()
        closeOutListening(player)
        listenerQueued.clear()
        radio = RadioSessionState(profileId)
        probation = RadioProbation(first.id, profileId)
        appGraph.radioSession.start(profileId)
        publishRadioExtras(profileId)
        queueGeneration.incrementAndGet()
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
        if (episodeId !in listenerQueued) appGraph.radio.onSkipped(session.profileId, episodeId, listened)
        if (!player.hasNextMediaItem()) refillRadioQueue()
        // The refill suspends. A switch or leaving radio meanwhile replaced the queue
        // this command was about, and advancing now would skip the new first pick.
        if (radio !== session) return
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
        closeOutListening(activePlayer())
        radio = null
        listenerQueued.clear()
        // probation is deliberately left standing. Stopping a pick you have heard
        // for fourteen seconds is not keeping it, and clearing the gate here let
        // the next progress save write it straight into Continue listening. It
        // still commits on its own once the episode earns its 90 seconds.
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

    /**
     * The allowlist's last line: a restricted profile's radio never plays a pick from
     * a show that is not ticked *now*. Checked whenever an episode becomes current, so
     * it holds however the pick got into the queue: a refill or start that read the
     * ticks before a change, an earlier pick reached with Previous, a Cast placeholder
     * resolved late. Episodes the listener queued themselves are theirs to play.
     */
    private suspend fun skipIfUnticked(session: RadioSessionState, episodeId: String) {
        val allowed = appGraph.radioProfiles.selectedShowsOnce(session.profileId)
        val podcastId = appGraph.podcasts.episodeById(episodeId)?.podcastId ?: return
        if (podcastId in allowed) return
        val player = activePlayer()
        // The lookups suspend: act only if that pick is still the one playing.
        if (radio !== session || player.currentEpisodeIdOrNull() != episodeId || episodeId in listenerQueued) return
        if (!player.isCommandAvailable(Player.COMMAND_CHANGE_MEDIA_ITEMS)) {
            player.pause()
            return
        }
        val index = player.currentMediaItemIndex
        if (player.hasNextMediaItem()) {
            player.seekToNextMediaItem()
            player.removeMediaItem(index)
            scheduleRadioRefill()
        } else {
            // Nothing after it: drop only this pick, keeping whatever else is queued (the
            // listener's own additions, earlier picks), and end the session rather than
            // leave an empty one. stopRadio saves what remains; if nothing does, the
            // saved queue still names this pick, so it is forgotten for resumption.
            player.pause()
            player.removeMediaItem(index)
            stopRadio()
            if (player.mediaItemCount == 0) appGraph.playbackState.clearIfContains(episodeId)
            appGraph.messages.post("Radio stopped: that show is no longer chosen for this profile.")
        }
    }

    /** The picks the profile's ticks allow as they are now; all of them when it is unrestricted. */
    private suspend fun stillAllowed(
        profileId: String,
        picks: List<Pair<MediaItem, EpisodeEntity>>,
    ): List<Pair<MediaItem, EpisodeEntity>> {
        if (!RadioProfiles.byId(profileId).restrictToSelectedShows) return picks
        val allowed = appGraph.radioProfiles.selectedShowsOnce(profileId)
        return picks.filter { it.second.podcastId in allowed }
    }

    /**
     * Takes the radio picks of shows just unticked out of the queue already built, on
     * both sides of the episode playing (earlier picks are reachable with Previous), so
     * the queue matches the ticks. The episode playing is [skipIfUnticked]'s to judge,
     * and the listener's own additions stay.
     */
    private suspend fun dropUntickedPicks(session: RadioSessionState, allowed: Set<String>) {
        val player = activePlayer()
        if (!player.isCommandAvailable(Player.COMMAND_CHANGE_MEDIA_ITEMS)) return
        val current = player.currentMediaItemIndex
        val queued = (0 until player.mediaItemCount)
            .filter { it != current }
            .mapNotNull { MediaIds.episodeIdOrNull(player.getMediaItemAt(it).mediaId) }
            .filterNot { it in listenerQueued }
        val unticked = queued.filterTo(HashSet()) { id ->
            appGraph.podcasts.episodeById(id)?.let { it.podcastId !in allowed } ?: false
        }
        // The lookups suspend: the session may have changed and the queue moved since.
        if (radio !== session || unticked.isEmpty()) return
        val now = activePlayer()
        for (i in now.mediaItemCount - 1 downTo 0) {
            if (i == now.currentMediaItemIndex) continue
            val id = MediaIds.episodeIdOrNull(now.getMediaItemAt(i).mediaId) ?: continue
            if (id in unticked && id !in listenerQueued) now.removeMediaItem(i)
        }
        snapshotQueue(now)
        scheduleRadioRefill()
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
        val built = stillAllowed(
            session.profileId,
            radioItems(session.profileId, plan.fetchCount, exclude = queued, recordServes = false),
        )
        // radioItems suspends; a switch to another profile in the meantime replaced
        // the queue, and these picks belong to the session that asked for them.
        if (built.isEmpty() || radio !== session) return
        player.addMediaItems(built.map { it.first })
        // Served only once really queued: a discarded refill used to mark its picks
        // served all the same, which lowers their scores for no reason. Once appended
        // they are playable even if radio is left right now, so the record must finish
        // regardless of this refill being cancelled.
        withContext(NonCancellable) {
            appGraph.radio.recordServed(session.profileId, built.map { it.second.id })
        }
    }

    /**
     * Saves the listening in flight before the radio session changes hands. A segment
     * is labelled with the session current when it is flushed, and the transition that
     * starting a new session causes flushed it only after the switch: the old episode's
     * last stretch was filed under the new profile, and a pick still on probation
     * escaped it, because probation had already moved to the new first pick.
     */
    private suspend fun closeOutListening(player: Player) {
        recordListeningProgress(player)
        flushListeningSegment()
    }

    private inner class LibraryCallback : MediaLibrarySession.Callback {

        /**
         * Runs synchronously on the application thread, so it must not touch the
         * database or DataStore. Every controller, trusted or not, gets the library
         * defaults plus the radio commands.
         */
        override fun onConnect(
            session: MediaSession,
            controller: MediaSession.ControllerInfo,
        ): MediaSession.ConnectionResult {
            // Deliberately not derived from super.onConnect(): that returns an empty
            // command set (session=0, player=0), so granting only what it contains
            // leaves every controller with an empty timeline — which silently blanks
            // the mini player and the radio card while audio plays fine.
            // Grant the library defaults plus the radio commands instead. The player
            // commands are set explicitly because the one-argument builder that used
            // to supply them is deprecated, and its replacement withholds them from
            // untrusted controllers.
            return MediaSession.ConnectionResult.AcceptedResultBuilder()
                .setAvailablePlayerCommands(MediaSession.ConnectionResult.DEFAULT_PLAYER_COMMANDS)
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
                    CarBrowseTree.root(),
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
            val children = CarBrowseTree(appGraph.database, appGraph.playlists).children(parentId).page(page, pageSize)
            LibraryResult.ofItemList(ImmutableList.copyOf(forBrowser(browser, children)), params)
        }

        override fun onGetItem(
            session: MediaLibrarySession,
            browser: MediaSession.ControllerInfo,
            mediaId: String,
        ): ListenableFuture<LibraryResult<MediaItem>> = scope.future(Dispatchers.IO) {
            val episode = MediaIds.episodeIdOrNull(mediaId)?.let { appGraph.podcasts.episodeById(it) }
            val podcast = MediaIds.podcastIdOrNull(mediaId)?.let { appGraph.database.podcastDao().byId(it) }
            // Under the id asked for, so an episode looked up from inside its show still
            // plays on through it.
            val item = when {
                episode != null -> MediaItemFactory.browsableEpisode(episode, mediaId)
                podcast != null -> MediaItemFactory.podcast(podcast)
                else -> null
            }
            if (item != null) {
                LibraryResult.ofItem(forBrowser(browser, listOf(item)).single(), null)
            } else {
                LibraryResult.ofError(SessionError.ERROR_BAD_VALUE)
            }
        }

        /**
         * Android Auto's search button. The legacy browser gets its results only once
         * notifySearchResultChanged names its query exactly as sent, so that is called
         * with the original string, and with a count of 0 when nothing matches or the
         * search fails, rather than leaving the car waiting.
         */
        override fun onSearch(
            session: MediaLibrarySession,
            browser: MediaSession.ControllerInfo,
            query: String,
            params: LibraryParams?,
        ): ListenableFuture<LibraryResult<Void>> {
            scope.launch {
                val count = try {
                    withContext(Dispatchers.IO) { CarSearch(appGraph.database).results(query).size }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    Log.w(TAG, "Car search failed", e)
                    0
                }
                session.notifySearchResultChanged(browser, query, count, params)
            }
            return Futures.immediateFuture(LibraryResult.ofVoid(params))
        }

        override fun onGetSearchResult(
            session: MediaLibrarySession,
            browser: MediaSession.ControllerInfo,
            query: String,
            page: Int,
            pageSize: Int,
            params: LibraryParams?,
        ): ListenableFuture<LibraryResult<ImmutableList<MediaItem>>> = scope.future(Dispatchers.IO) {
            val results = CarSearch(appGraph.database).results(query).page(page, pageSize)
            LibraryResult.ofItemList(ImmutableList.copyOf(forBrowser(browser, results)), params)
        }

        /** Browse items as [browser] should get them: see [CarArtwork]. */
        private fun forBrowser(browser: MediaSession.ControllerInfo, items: List<MediaItem>): List<MediaItem> =
            CarArtwork.forBrowser(this@PlaybackService, browser.packageName, items)

        /** Resolves bare mediaIds (from Auto or the in-app UI) into playable items with URIs. */
        override fun onAddMediaItems(
            mediaSession: MediaSession,
            controller: MediaSession.ControllerInfo,
            mediaItems: List<MediaItem>,
        ): ListenableFuture<List<MediaItem>> = scope.future {
            queueGeneration.incrementAndGet()
            val episodes = withContext(Dispatchers.IO) {
                // Radio's own refills call the player directly and never come through
                // here, so anything added while radio runs is the listener's choice.
                if (radio != null) {
                    mediaItems.mapNotNullTo(listenerQueued) { MediaIds.episodeIdOrNull(it.mediaId) }
                }
                episodesInOrder(mediaItems.map { MediaIds.episodeIdOrNull(it.mediaId) }).filterNotNull()
            }
            // See onSetMediaItems.
            queueGeneration.incrementAndGet()
            val forCast = isCasting()
            episodes.map { MediaItemFactory.playable(it, forCast) }
        }

        override fun onSetMediaItems(
            mediaSession: MediaSession,
            controller: MediaSession.ControllerInfo,
            mediaItems: List<MediaItem>,
            startIndex: Int,
            startPositionMs: Long,
        ): ListenableFuture<MediaSession.MediaItemsWithStartPosition> {
            val request = setRequests.incrementAndGet()
            // "Play Podly" (a spoken request naming nothing): carry on with what is
            // loaded, or restore the saved queue into an empty player the way
            // resumption does, guards included. A failed future leaves the queue as it
            // is; Media3's legacy path does nothing with the failure.
            // (A prepareFromSearch with no words also lands here and plays; nothing
            // known sends one.)
            if (spokenQuery(mediaItems)?.isBlank() == true) {
                val player = mediaSession.player
                if (player.currentMediaItem == null) {
                    return onPlaybackResumption(mediaSession, controller, isForPlayback = true)
                }
                // As the play button does: an idle player is prepared and an ended one
                // starts over, which play() alone would not do.
                Util.handlePlayButtonAction(player)
                return Futures.immediateFailedFuture(NothingNewToPlay())
            }
            return setMediaItemsAsync(request, mediaItems, startIndex, startPositionMs)
        }

        private fun setMediaItemsAsync(
            request: Int,
            mediaItems: List<MediaItem>,
            startIndex: Int,
            startPositionMs: Long,
        ): ListenableFuture<MediaSession.MediaItemsWithStartPosition> = scope.future {
            queueGeneration.incrementAndGet()
            // A spoken request that another set request overtook while it searched is
            // dropped, before it ends radio and again before it applies (setRequests).
            val overtaken: (() -> Boolean)? =
                if (spokenQuery(mediaItems) != null) ({ setRequests.get() != request }) else null
            val queue = withContext(Dispatchers.IO) {
                resolveQueue(mediaItems, startIndex, startPositionMs, overtaken)
            }
            if (overtaken?.invoke() == true) throw NothingNewToPlay()
            // This future completes on the application thread, where Media3 applies the
            // result at once. So bumped again here, a resumption that began while this
            // resolved can't restore over it; and the items are built for whichever
            // player has the session now, as casting may have started or stopped.
            queueGeneration.incrementAndGet()
            queue.items(forCast = isCasting())
        }

        private suspend fun resolveQueue(
            mediaItems: List<MediaItem>,
            startIndex: Int,
            startPositionMs: Long,
            overtaken: (() -> Boolean)? = null,
        ): ResolvedQueue {
            // Every lookup comes first, so a spoken request that matches nothing, or is
            // overtaken while it searches, leaves radio and the queue as they were.
            val (ids, index) = requestedIds(mediaItems, startIndex) ?: throw NothingNewToPlay()
            // Recounted around episodes that no longer exist, so the one asked for
            // still starts.
            val (episodes, safeIndex) = compactAt(episodesInOrder(ids), index)
            if (overtaken?.invoke() == true) throw NothingNewToPlay()
            // Setting a queue from anywhere else is an explicit choice, so it ends the
            // radio session. Without this the session would leak: the hardware skip
            // remap and the withholding of progress would both stay armed over
            // ordinary listening.
            if (radio != null) {
                radio = null
                scope.launch { stopRadio() }
            }
            // An explicit start position wins; otherwise the episode's saved one.
            val position = startPositionMs.takeIf { it != C.TIME_UNSET && it > 0 }
            return ResolvedQueue(episodes, safeIndex, position).prebuild(isCasting())
        }

        /**
         * The episode ids a set request names, and the index to start at; null for a
         * spoken request that matches nothing. A spoken "play …" goes through
         * [CarSearch.voiceQueue]; one episode played from inside its show in Android
         * Auto (a `show-ep/` id) plays on through the show; anything else is the list
         * as sent. Reads only, so nothing has changed if this returns null.
         */
        private suspend fun requestedIds(mediaItems: List<MediaItem>, startIndex: Int): Pair<List<String?>, Int>? {
            spokenQuery(mediaItems)?.let { query ->
                return CarSearch(appGraph.database).voiceQueue(query)?.let { it to 0 }
            }
            val fromShow = mediaItems.singleOrNull()?.let { MediaIds.showEpisodeIdOrNull(it.mediaId) }
            val podcastId = fromShow?.let { appGraph.podcasts.episodeById(it)?.podcastId }
            if (fromShow != null && podcastId != null) {
                val order = appGraph.database.episodeDao().episodeOrderForPodcast(podcastId)
                return continueThroughShow(order, fromShow) to 0
            }
            return mediaItems.map { MediaIds.episodeIdOrNull(it.mediaId) } to startIndex
        }

        /**
         * Android Auto / system "resume" entry point after process death: restore the
         * last queue and position, falling back to the most recent in-progress episode.
         * The same items come back whether or not [isForPlayback] is set.
         *
         * Media3 also calls this on any play request while the player has no current
         * item. The Cast player reads as empty until the receiver reports a queue just
         * sent, so tapping an episode while connected to an idle receiver lands here
         * right behind its own load. Restoring then replaced the episode tapped with the
         * saved queue, and once crashed the app by sending a whole saved library queue
         * to the receiver. A failed future makes Media3 skip the restore and just play.
         */
        override fun onPlaybackResumption(
            mediaSession: MediaSession,
            controller: MediaSession.ControllerInfo,
            isForPlayback: Boolean,
        ): ListenableFuture<MediaSession.MediaItemsWithStartPosition> {
            // On the application thread here, which the Cast mirror needs.
            if (isCasting() && castQueue?.hasLoadInFlight() == true) {
                return Futures.immediateFailedFuture(ResumptionSuperseded())
            }
            val generation = queueGeneration.get()
            // Completes on the application thread, where Media3 applies the result at
            // once (it posts or runs on that thread), so nothing can set a queue or
            // switch players between this check and the restore.
            return scope.future {
                val saved = withContext(Dispatchers.IO) { savedQueue() }
                // A queue set meanwhile is newer than the saved one and must stay.
                if (queueGeneration.get() != generation) throw ResumptionSuperseded()
                // Built for whichever player has the session now: casting may have
                // started or stopped during the lookup.
                saved.items(forCast = isCasting())
            }
        }

        private suspend fun savedQueue(): ResolvedQueue {
            val graph = appGraph
            val saved = graph.playbackState.load()
            val episodes: List<EpisodeEntity>
            val startIndex: Int
            if (saved != null) {
                episodes = episodesInOrder(saved.episodeIds).filterNotNull()
                val savedEpisodeId = saved.episodeIds.getOrNull(saved.currentIndex)
                startIndex = episodes.indexOfFirst { it.id == savedEpisodeId }.coerceAtLeast(0)
            } else {
                episodes = graph.database.episodeDao().continueListeningOnce(1)
                    .ifEmpty { graph.database.episodeDao().libraryEpisodesOnce().take(1) }
                startIndex = 0
            }
            return ResolvedQueue(episodes, startIndex).prebuild(isCasting())
        }
    }

    /**
     * A queue's episodes, looked up off the main thread, to be made into items on it
     * for the player that has the session by then. [startPositionMs] null means the
     * current episode's saved position. Items are built ahead, off the main thread,
     * for the player current at lookup ([prebuild]); thousands of them would stall the
     * UI. They are built again only if casting started or stopped in between.
     */
    private class ResolvedQueue(
        val episodes: List<EpisodeEntity>,
        val startIndex: Int,
        val startPositionMs: Long? = null,
    ) {
        private var built: Pair<Boolean, MediaSession.MediaItemsWithStartPosition>? = null

        fun prebuild(forCast: Boolean) = apply { items(forCast) }

        fun items(forCast: Boolean): MediaSession.MediaItemsWithStartPosition {
            built?.takeIf { it.first == forCast }?.let { return it.second }
            return build(forCast).also { built = forCast to it }
        }

        private fun build(forCast: Boolean): MediaSession.MediaItemsWithStartPosition {
            if (episodes.isEmpty()) return MediaSession.MediaItemsWithStartPosition(emptyList(), 0, 0L)
            val current = episodes[startIndex]
            val resumePosition = startPositionMs
                ?: current.playbackPositionMs.takeIf { !current.completed } ?: 0L
            val items = episodes.map { MediaItemFactory.playable(it, forCast) }
            val (window, index) = if (forCast) castQueueWindow(items, startIndex) else items to startIndex
            return MediaSession.MediaItemsWithStartPosition(window, index, resumePosition)
        }
    }

    /**
     * The words of a spoken "play … on Podly", which Media3 hands over as one item with
     * no media id and the words as its search query; null for any other request.
     */
    private fun spokenQuery(mediaItems: List<MediaItem>): String? =
        mediaItems.singleOrNull()
            ?.takeIf { MediaIds.episodeIdOrNull(it.mediaId) == null }
            ?.requestMetadata?.searchQuery

    /** A set request that leaves the queue as it is: a spoken one naming nothing, matching nothing, or overtaken. */
    private class NothingNewToPlay : Exception("Nothing new to play")

    /** A playback resumption dropped because a newer queue was set; Media3 then just plays. */
    private class ResumptionSuperseded :
        Exception("Not restoring the saved queue: a newer one was set")

    companion object {
        private const val MIN_LISTEN_SEGMENT_MS = 1_000L

        private const val CONTINUOUS_POSITION_TOLERANCE_MS = 12_000L
        /** Never a real position, so the next paused tick sees the position as moved. */
        private const val UNSAVED_POSITION = -1L
        private const val TAG = "PodlyPlayback"
        private const val MAX_RECOVERY_ATTEMPTS = 5
        private val RECOVERABLE_ERROR_CODES = setOf(
            PlaybackException.ERROR_CODE_IO_UNSPECIFIED,
            PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED,
            PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_TIMEOUT,
        )
    }

    private data class RadioSessionState(val profileId: String)

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
