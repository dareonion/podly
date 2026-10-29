package com.podly.data.chat

import android.util.Log
import com.podly.data.AppleShow
import com.podly.data.AppleShowResolver
import com.podly.data.ArchiveRescuer
import com.podly.data.PlaylistRepository
import com.podly.data.PodcastRepository
import com.podly.data.db.EpisodeEntity
import com.podly.data.db.PodcastEntity
import com.podly.data.db.stableId
import com.podly.network.AppleEpisode
import com.podly.network.Http
import com.podly.network.ItunesApi
import com.podly.network.XiaoyuzhouApi
import com.podly.network.ai.RecentEpisodeMatcher
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import java.security.MessageDigest
import java.time.Instant
import java.time.ZoneOffset
import kotlin.math.abs

private const val TAG = "ChatImporter"

/**
 * Turns a ChatGPT recommendations chat into a playlist: its episodes in the chat's
 * order, each with the chat's reason as its note, plus the shows it recommends as
 * a whole.
 *
 * Every episode lands on a real feed's episode row, the same one a subscription
 * uses, so progress and downloads are shared. A link says where to look: a 小宇宙
 * page gives the show and the exact title, an Apple link the show's id (and the
 * episode's GUID when Apple still lists it), pod.link the MD5 of the GUID. Without
 * one, the show is found by name. A pick that can't be pinned to a feed episode is
 * reported, never guessed: a show with no feed anywhere is a miss, not a
 * subscribable row that can never refresh.
 */
class ChatImporter(
    private val podcasts: PodcastRepository,
    private val playlists: PlaylistRepository,
    private val appleShows: AppleShowResolver,
    private val rescuer: ArchiveRescuer,
    private val itunes: ItunesApi = ItunesApi(),
) {
    data class Result(
        val playlistId: Long,
        val name: String,
        val episodes: Int,
        val shows: Int,
        val missed: List<String>,
    )

    /**
     * [input] is a chatgpt.com share link (or text containing one), else the chat's
     * own text. [dateLabel] goes after the chat's title in the playlist's name.
     */
    suspend fun import(input: String, dateLabel: String, onProgress: (String) -> Unit = {}): Result {
        onProgress("Reading the chat...")
        val shareUrl = ChatSharePage.shareUrlIn(input)
        val chat = if (shareUrl != null) {
            ChatSharePage.parse(Http.get(shareUrl))
        } else {
            ChatSharePage.Chat(null, listOf(ChatSharePage.Message(input, emptyMap())))
        }
        val picks = chat.messages.flatMap(ChatPickExtractor::extract)
        require(picks.isNotEmpty()) { "Found no recommendations in this chat." }

        onProgress("Looking up ${picks.size} recommendations...")
        val appleEpisodes = appleEpisodeLists(picks)
        val sourced = gated(picks) { source(it, appleEpisodes) }

        // One feed pull per show, however many of its episodes the chat picks. Shows
        // known by Apple id go first: the rows they add are what a later search by
        // name finds first ("The Real Story" → 《The Real Story》By 報導者).
        val groups = sourced.withIndex().groupBy { it.value.showKey }
        val (byId, byName) = groups.entries.partition { it.key?.startsWith("apple:") == true }
        val outcomes = arrayOfNulls<Outcome>(picks.size)
        var matched = 0
        for (phase in listOf(byId, byName)) {
            gated(phase) { (key, members) ->
                val resolved = if (key == null) emptyMap() else resolveGroup(members)
                members.forEach { (index, s) -> outcomes[index] = resolved[index] ?: Outcome.Missed(s.pick.title) }
                synchronized(outcomes) { matched++ }
                onProgress("Matching shows... $matched of ${groups.size}")
            }
        }

        val episodes = LinkedHashMap<String, String?>()
        val shows = LinkedHashMap<String, String?>()
        val missedPicks = mutableListOf<ChatPick>()
        outcomes.forEachIndexed { i, outcome ->
            when (outcome) {
                is Outcome.Episode -> if (outcome.id !in episodes) episodes[outcome.id] = picks[i].reason
                is Outcome.Show -> if (outcome.podcastId !in shows) shows[outcome.podcastId] = picks[i].reason
                is Outcome.Missed, null -> missedPicks += picks[i]
            }
        }
        // The same episode under another of the chat's titles (its top-six card, a
        // "start here" row) is in the playlist, not missing; but only when nothing
        // says it's another show's ("Show B: Burnout" is not "Show A: Burnout").
        val included = podcasts.episodesByIds(episodes.keys.toList())
            .map { RecentEpisodeMatcher.normalize(it.podcastTitle) to RecentEpisodeMatcher.normalize(it.title) }
        val missed = missedPicks.filterNot { p ->
            val show = p.showName?.takeIf { !p.isShow }?.let(RecentEpisodeMatcher::normalize)
            val core = RecentEpisodeMatcher.normalize(p.showName?.takeIf { !p.isShow }?.let { p.title.removePrefix(it) } ?: p.title)
            core.codePointCount(0, core.length) >= 6 && included.any { (podcastTitle, title) ->
                title.contains(core) && (show == null || podcastTitle.contains(show) || show.contains(podcastTitle))
            }
        }.map { it.title }.distinct()
        require(episodes.isNotEmpty() || shows.isNotEmpty()) {
            "Couldn't find any of this chat's ${picks.size} recommendations in a podcast feed."
        }
        val name = "${chat.title?.takeIf { it.isNotBlank() } ?: "ChatGPT picks"} · $dateLabel"
        val playlistId = playlists.create(name)
        episodes.forEach { (id, note) -> playlists.addEpisode(playlistId, id, note) }
        playlists.addShows(playlistId, shows.toList())
        return Result(playlistId, name, episodes.size, shows.size, missed.toList())
    }

    private sealed interface Outcome {
        data class Episode(val id: String) : Outcome
        data class Show(val podcastId: String) : Outcome
        data class Missed(val title: String) : Outcome
    }

    /** What the pick's link (or title) says about where to find it. */
    private data class Sourced(
        val pick: ChatPick,
        val appleShowId: String? = null,
        val showTitle: String? = null,
        val episodeTitle: String = pick.title,
        val publishedApprox: String? = null,
        val guid: String? = null,
        val guidMd5: String? = null,
        val apple: AppleEpisode? = null,
    ) {
        val showKey: String?
            get() = appleShowId?.let { "apple:$it" }
                ?: showTitle?.let(RecentEpisodeMatcher::normalize)?.ifEmpty { null }?.let { "name:$it" }
    }

    /** Each Apple show's episode list, fetched once for all its episode links. */
    private suspend fun appleEpisodeLists(picks: List<ChatPick>): Map<String, List<AppleEpisode>> {
        val ids = picks.mapNotNull { (it.url?.let(ChatLink::parse) as? ChatLink.AppleEpisode)?.showId }.distinct()
        return gated(ids) { id -> id to (runCatching { itunes.episodesById(id) }.getOrNull().orEmpty()) }.toMap()
    }

    private suspend fun source(pick: ChatPick, appleEpisodes: Map<String, List<AppleEpisode>>): Sourced =
        when (val link = pick.url?.let(ChatLink::parse)) {
            is ChatLink.XiaoyuzhouEpisode -> retrying { XiaoyuzhouApi.episode(link.eid) }
                .onFailure { Log.w(TAG, "小宇宙 episode ${link.eid}: ${it.message}") }
                .getOrNull()
                ?.let { Sourced(pick, showTitle = it.show.title, episodeTitle = it.title, publishedApprox = day(it.pubDateMs)) }
                ?: Sourced(pick, showTitle = pick.showName)
            is ChatLink.AppleEpisode -> {
                val hit = appleEpisodes[link.showId].orEmpty().firstOrNull { it.trackId == link.episodeId }
                Sourced(
                    pick,
                    appleShowId = link.showId,
                    episodeTitle = hit?.title ?: pick.title,
                    publishedApprox = hit?.releaseDateMs?.let(::day),
                    guid = hit?.guid,
                    apple = hit,
                )
            }
            is ChatLink.PodLinkEpisode -> Sourced(pick, appleShowId = link.showId, guidMd5 = link.guidMd5)
            is ChatLink.AppleShow -> Sourced(pick, appleShowId = link.showId)
            is ChatLink.XiaoyuzhouShow -> Sourced(
                pick,
                showTitle = runCatching { XiaoyuzhouApi.show(link.pid).title }.getOrNull() ?: pick.showName,
            )
            ChatLink.Other, null -> Sourced(pick, showTitle = pick.showName)
        }

    /**
     * Tries the show's candidates in turn and keeps the first whose feed holds one
     * of the picked episodes: show names aren't unique, so a same-named show already
     * in Podly is only the first guess. A failed refresh still matches against the
     * episodes already stored. When no feed holds any, the first candidate whose
     * Apple record or archive does is the show.
     */
    private suspend fun resolveGroup(members: List<IndexedValue<Sourced>>): Map<Int, Outcome> {
        val candidates = candidatesFor(members.first().value)
        if (candidates.isEmpty()) return emptyMap()
        val loaded = mutableListOf<PodcastEntity>()
        var chosen: PodcastEntity? = null
        var found: Map<Int, String> = emptyMap()
        for (candidate in candidates) {
            val podcast = runCatching { podcasts.openPodcast(candidate) }
                .onFailure { Log.w(TAG, "Feed pull failed for ${candidate.title}: ${it.message}") }
                .getOrNull() ?: candidate
            loaded += podcast
            if (members.all { isShow(it.value, podcast) }) {
                chosen = podcast
                break
            }
            val matches = matchInFeed(podcast, members)
            if (matches.isNotEmpty()) {
                chosen = podcast
                found = matches
                break
            }
        }
        val out = HashMap<Int, Outcome>()
        if (chosen == null) {
            for (podcast in loaded) {
                val recovered = recover(podcast, members.filterNot { isShow(it.value, podcast) })
                if (recovered.isNotEmpty()) {
                    chosen = podcast
                    recovered.forEach { (index, id) -> out[index] = Outcome.Episode(id) }
                    break
                }
            }
        }
        val podcast = chosen ?: loaded.first()
        val missing = mutableListOf<IndexedValue<Sourced>>()
        for (member in members) {
            when {
                member.index in out -> Unit
                isShow(member.value, podcast) -> out[member.index] = Outcome.Show(podcast.id)
                found[member.index] != null -> out[member.index] = Outcome.Episode(found.getValue(member.index))
                else -> missing += member
            }
        }
        if (missing.isNotEmpty() && chosen != null) {
            recover(podcast, missing).forEach { (index, id) -> out[index] = Outcome.Episode(id) }
        }
        return out
    }

    /**
     * Episodes the feed no longer carries. A pick Apple named by GUID comes only from
     * Apple's own record of it: the archives match by title, and a same-titled rerun
     * is not that episode. Others are looked up in the configured archives by title
     * (a pod.link hash is no identity to hold them to; see [FeedEpisodeMatch]).
     */
    private suspend fun recover(podcast: PodcastEntity, missing: List<IndexedValue<Sourced>>): Map<Int, String> {
        if (missing.isEmpty()) return emptyMap()
        val out = HashMap(fromApple(podcast, missing))
        val rest = missing.filter { it.index !in out && it.value.guid == null }
        out += rescuer.rescue(
            podcast,
            rest.map { (index, s) -> index to ArchiveRescuer.Query(s.episodeTitle, s.publishedApprox) },
        )
        return out
    }

    /**
     * Whether a pick means the show itself. Behind a show-level link the chat's
     * guess is overruled by the show's real name: "E913 一个人民陪审员的五年" under
     * 故事FM's page is an episode, while "旅行熱炒店" under 旅行熱炒店：世界地理人文探索頻道,
     * or 解鎖地球：旅行、歷史、文化 under itself, is the show.
     */
    private fun isShow(s: Sourced, podcast: PodcastEntity): Boolean {
        val link = s.pick.url?.let(ChatLink::parse)
        if (link !is ChatLink.AppleShow && link !is ChatLink.XiaoyuzhouShow) return s.pick.isShow
        val title = RecentEpisodeMatcher.normalize(s.pick.title)
        val show = RecentEpisodeMatcher.normalize(podcast.title)
        return title.isNotEmpty() && show.isNotEmpty() && (show.startsWith(title) || title == show)
    }

    private suspend fun candidatesFor(s: Sourced): List<PodcastEntity> {
        s.appleShowId?.let { id ->
            return when (val show = runCatching { appleShows.resolve(id) }.getOrNull()) {
                is AppleShow.Found -> listOf(show.podcast)
                else -> emptyList()
            }
        }
        val name = s.showTitle ?: return emptyList()
        val wanted = RecentEpisodeMatcher.normalize(name).ifEmpty { return emptyList() }
        fun rank(p: PodcastEntity): Int? {
            val have = RecentEpisodeMatcher.normalize(p.title)
            return when {
                have == wanted -> 0
                have.isNotEmpty() && (have.contains(wanted) || wanted.contains(have)) -> 1
                else -> null
            }
        }
        // Shows already in Podly first, by name or containing it: "The Real Story" is
        // several English shows to Apple's search, and 《The Real Story》By 報導者 came
        // after three of them.
        fun ranked(list: List<PodcastEntity>) =
            list.mapNotNull { p -> rank(p)?.let { p to it } }.sortedBy { it.second }.map { it.first }
        val local = ranked(podcasts.allPodcastsOnce())
        val directory = ranked(runCatching { podcasts.search(name) }.getOrNull().orEmpty())
        return (local + directory).distinctBy { it.id }.take(MAX_CANDIDATES)
    }

    /** Pick index -> episode id, for the members this feed holds. */
    private suspend fun matchInFeed(podcast: PodcastEntity, members: List<IndexedValue<Sourced>>): Map<Int, String> {
        val episodes = podcasts.episodesForPodcastOnce(podcast.id)
        val out = HashMap<Int, String>()
        for ((index, s) in members) {
            if (isShow(s, podcast)) continue
            FeedEpisodeMatch.find(
                episodes = episodes,
                title = s.episodeTitle,
                publishedApprox = s.publishedApprox,
                showTitle = podcast.title,
                guid = s.guid,
                guidMd5 = s.guidMd5,
                appleReleaseMs = s.apple?.releaseDateMs,
            )?.let { out[index] = it }
        }
        return out
    }

    /**
     * Episodes Apple still lists but the feed no longer carries (short rolling
     * feeds), added from Apple's own record under the feed's show, with the id
     * the feed would give them.
     */
    private suspend fun fromApple(podcast: PodcastEntity, missing: List<IndexedValue<Sourced>>): Map<Int, String> {
        val rows = missing.mapNotNull { (index, s) ->
            val a = s.apple ?: return@mapNotNull null
            val audio = a.audioUrl ?: return@mapNotNull null
            index to EpisodeEntity(
                id = stableId(a.guid ?: audio),
                podcastId = podcast.id,
                podcastTitle = podcast.title,
                guid = a.guid,
                title = a.title,
                description = a.description,
                audioUrl = audio,
                pubDateMs = a.releaseDateMs,
                durationMs = a.durationMs,
                artworkUrl = a.artworkUrl ?: podcast.artworkUrl,
            )
        }
        if (rows.isEmpty()) return emptyMap()
        podcasts.insertEpisodes(rows.map { it.second })
        return rows.associate { (index, row) -> index to row.id }
    }

    /** One retry after a pause: dozens of 小宇宙 pages in a row now and then fail one. */
    private suspend fun <T> retrying(block: suspend () -> T): kotlin.Result<T> =
        runCatching { block() }.recoverCatching {
            if (it is CancellationException) throw it
            delay(RETRY_DELAY_MS)
            block()
        }

    private suspend fun <T, R> gated(items: List<T>, block: suspend (T) -> R): List<R> = coroutineScope {
        val gate = Semaphore(PodcastRepository.MAX_CONCURRENT_REFRESHES)
        items.map { async { gate.withPermit { block(it) } } }.awaitAll()
    }

    private fun day(ms: Long): String? =
        ms.takeIf { it > 0 }?.let { Instant.ofEpochMilli(it).atOffset(ZoneOffset.UTC).toLocalDate().toString() }

    private companion object {
        const val MAX_CANDIDATES = 3
        const val RETRY_DELAY_MS = 1_000L
    }
}

/**
 * Which of a feed's [EpisodeEntity] rows a pick names. A known identity wins: the
 * GUID Apple gave for the linked episode, or pod.link's hash of it. Against Apple's
 * GUID, another row counts only with Apple's exact title on Apple's day (a feed that
 * rewrote its GUIDs). Pure, for tests.
 */
internal object FeedEpisodeMatch {
    private const val DAY_MS = 86_400_000L

    fun find(
        episodes: List<EpisodeEntity>,
        title: String,
        publishedApprox: String?,
        showTitle: String?,
        guid: String? = null,
        guidMd5: String? = null,
        appleReleaseMs: Long? = null,
    ): String? {
        guid?.let { g -> episodes.firstOrNull { it.guid == g }?.let { return it.id } }
        // pod.link's id was the MD5 of the GUID on 說故事的人 but matches nothing on 在人间
        // (ximalaya), so a hash that finds nothing says nothing.
        guidMd5?.let { h -> episodes.firstOrNull { e -> e.guid?.let(::md5) == h }?.let { return it.id } }
        if (guid != null) {
            // Apple's GUID is in no row: only a feed that rewrote its GUIDs still has the
            // episode, under Apple's exact title and on Apple's day.
            if (appleReleaseMs == null) return null
            val wanted = RecentEpisodeMatcher.normalize(title)
            return episodes.singleOrNull {
                RecentEpisodeMatcher.normalize(it.title) == wanted && abs(it.pubDateMs - appleReleaseMs) <= 2 * DAY_MS
            }?.id
        }
        val candidates = episodes.map { RecentEpisodeMatcher.Candidate(it.title, it.description, it.pubDateMs) }
        return RecentEpisodeMatcher.bestMatch(title, publishedApprox, candidates, showTitle)?.let { episodes[it].id }
    }

    fun md5(s: String): String =
        MessageDigest.getInstance("MD5").digest(s.toByteArray()).joinToString("") { "%02x".format(it) }
}

