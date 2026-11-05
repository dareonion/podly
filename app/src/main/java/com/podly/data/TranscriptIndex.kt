package com.podly.data

import android.util.Log
import com.podly.data.db.EpisodeDao
import com.podly.network.Http
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.Serializable
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

@Serializable
data class TranscriptIndexFile(
    val entries: List<TranscriptIndexEntry>,
    val generatedAt: String? = null,
)

@Serializable
data class TranscriptIndexEntry(
    val id: String,
    val url: String,
    val type: String = "text/vtt",
    val durationMs: Long? = null,
    val guid: String? = null,
    val audioUrl: String? = null,
    val feedUrl: String? = null,
    val title: String? = null,
    val language: String? = null,
)

/** A directory URL, including its path; never a string prefix of a hostname or port. */
fun transcriptServerUrl(value: String): HttpUrl {
    val url = value.trim().toHttpUrl()
    require(url.username.isEmpty() && url.password.isEmpty() && url.query == null && url.fragment == null) {
        "Use an http(s) server URL without credentials, query or fragment"
    }
    return if (url.encodedPath.endsWith('/')) url else url.newBuilder().addPathSegment("").build()
}

fun isOnTranscriptServer(url: String?, server: String): Boolean {
    val candidate = url?.toHttpUrlOrNull() ?: return false
    val base = runCatching { transcriptServerUrl(server) }.getOrNull() ?: return false
    val path = base.encodedPath.removeSuffix("/")
    return candidate.scheme == base.scheme && candidate.host == base.host && candidate.port == base.port &&
        (candidate.encodedPath == path || candidate.encodedPath.startsWith("$path/"))
}

fun canApplyTranscript(currentUrl: String?, server: String): Boolean =
    currentUrl == null || isOnTranscriptServer(currentUrl, server)

/** Resolve relative to index.json, including a server installed under a subdirectory. */
fun resolveTranscriptUrl(server: String, reference: String): String {
    val base = transcriptServerUrl(server)
    val indexUrl = checkNotNull(base.resolve("index.json"))
    val resolved = requireNotNull(indexUrl.resolve(reference)) { "Invalid transcript URL: $reference" }
    require(isOnTranscriptServer(resolved.toString(), base.toString())) { "Transcript URL is outside its server" }
    return resolved.toString()
}

data class TranscriptIndexSnapshot(
    val server: String,
    /** Entries have absolute URLs after validation. */
    val entries: Map<String, TranscriptIndexEntry>,
)

data class TranscriptSyncStatus(
    val syncing: Boolean = false,
    val lastSyncMs: Long? = null,
    val matched: Int = 0,
    val missing: Int = 0,
    val error: String? = null,
)

/**
 * Optional local transcripts. The feed always wins; compare-and-set writes also
 * protect a feed refresh that lands between reading a row and applying the index.
 * One mutex serializes startup, settings changes, feed refreshes and page opens.
 */
class TranscriptIndex(
    private val episodes: EpisodeDao,
    private val settings: SettingsRepository,
    private val fetch: suspend (String) -> String = { Http.get(it) },
    private val elapsedMs: () -> Long = { System.nanoTime() / 1_000_000 },
) {
    private val mutex = Mutex()
    private val _status = MutableStateFlow(TranscriptSyncStatus())
    val status: StateFlow<TranscriptSyncStatus> = _status
    private val _snapshot = MutableStateFlow<TranscriptIndexSnapshot?>(null)
    val snapshot: StateFlow<TranscriptIndexSnapshot?> = _snapshot
    private var attemptedServer: String? = null
    private var statusServer: String? = null
    private var lastAttemptMs: Long? = null

    /** The first emission is the app-start sync; subsequent ones handle edits/removal. */
    fun start(scope: CoroutineScope) = scope.launch {
        try {
            settings.settings.map { it.transcriptServer }.distinctUntilChanged().collect { sync(force = true) }
        } catch (error: Exception) {
            report(error)
        }
    }

    /** A page open uses the cached index indefinitely; refresh hooks may fetch after a minute. */
    suspend fun applyToEpisode(episodeId: String) = sync(episodeId = episodeId)

    suspend fun sync(force: Boolean = false, episodeId: String? = null) {
        mutex.withLock {
            try {
                syncLocked(force, episodeId)
            } catch (error: Exception) {
                report(error)
            } finally {
                _status.value = _status.value.copy(syncing = false)
            }
        }
    }

    private suspend fun syncLocked(force: Boolean, episodeId: String?) {
        val config = settings.current()
        val rawServer = config.transcriptServer
        val server = if (rawServer.isBlank()) "" else transcriptServerUrl(rawServer).toString()
        // The pending marker covers process death between Room writes and the
        // final DataStore write. Both old origins remain recoverable on restart.
        setOf(config.transcriptAppliedServer, config.transcriptPendingServer)
            .filter { it.isNotEmpty() && it != server }
            .forEach { old ->
                episodes.withTranscripts().filter { isOnTranscriptServer(it.transcriptUrl, old) }.forEach {
                    episodes.replaceTranscript(it.id, it.transcriptUrl, null, null)
                }
            }
        if (_snapshot.value?.server != server) _snapshot.value = null
        // Reset the status only on an actual server change: a failed first fetch leaves no
        // snapshot, and the next throttled sync must not wipe that error from Settings.
        if (statusServer != server) {
            statusServer = server
            _status.value = TranscriptSyncStatus()
        }
        if (server.isEmpty()) {
            settings.finishTranscriptApply("")
            return
        }
        _status.value = _status.value.copy(syncing = true)
        val now = elapsedMs()
        val due = attemptedServer != server || lastAttemptMs?.let { now - it >= 60_000 } != false
        val shouldFetch = force || (due && (episodeId == null || _snapshot.value == null))
        if (shouldFetch) {
            attemptedServer = server
            lastAttemptMs = now
            try {
                val indexUrl = checkNotNull(transcriptServerUrl(server).resolve("index.json")).toString()
                val file = Http.json.decodeFromString<TranscriptIndexFile>(fetch(indexUrl))
                val resolved = file.entries.map {
                    require(it.id.isNotBlank() && it.url.isNotBlank()) { "Index entry needs an id and URL" }
                    it.copy(url = resolveTranscriptUrl(server, it.url))
                }
                require(resolved.map { it.id }.distinct().size == resolved.size) { "Duplicate episode ids in index" }
                _snapshot.value = TranscriptIndexSnapshot(server, resolved.associateBy { it.id })
                _status.value = _status.value.copy(lastSyncMs = System.currentTimeMillis(), error = null)
            } catch (error: Exception) {
                report(error)
                // A cached catalogue can still match rows inserted while offline.
            }
        }
        val cached = _snapshot.value ?: return
        // Only a server change needs the crash-recovery marker; a full refresh calls this once
        // per show, and two DataStore writes each time would be wasted work.
        val switching = config.transcriptAppliedServer != server || config.transcriptPendingServer.isNotEmpty()
        if (switching) settings.beginTranscriptApply(server)
        val rows = cached.entries.keys.chunked(500).flatMap { episodes.byIds(it) }
        rows.filter { episodeId == null || it.id == episodeId }.forEach { episode ->
            val entry = cached.entries.getValue(episode.id)
            if (episode.transcriptUrl != entry.url && canApplyTranscript(episode.transcriptUrl, server)) {
                episodes.replaceTranscript(episode.id, episode.transcriptUrl, entry.url, entry.type)
            }
        }
        if (switching) settings.finishTranscriptApply(server)
        _status.value = _status.value.copy(matched = rows.size, missing = cached.entries.size - rows.size)
    }

    private fun report(error: Exception) {
        if (error is CancellationException) throw error
        _status.value = _status.value.copy(error = error.message ?: "Transcript sync failed")
        Log.w("TranscriptIndex", "Transcript sync failed", error)
    }
}

/** Unknown durations are not evidence of a mismatch. */
fun transcriptTimingDiffers(measuredMs: Long?, knownMs: Long?): Boolean =
    measuredMs != null && measuredMs > 0 && knownMs != null && knownMs > 0 &&
        kotlin.math.abs(measuredMs - knownMs) > 10_000
