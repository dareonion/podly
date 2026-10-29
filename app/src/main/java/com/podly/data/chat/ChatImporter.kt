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
import java.text.Normalizer
import java.time.Instant
import java.time.LocalDate
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
        // Episodes still missing under a show name no search found: Apple's episode
        // search finds them under whatever show carries them. "微笑台灣 Podcast：大陳島
        // 撤退後，他們去哪了？" is 【微笑台灣Ep.192】 of 聽天下：天下雜誌Podcast, whose segment
        // 微笑台灣 is, not a show of its own.
        val searchable = outcomes.indices.filter { i ->
            outcomes[i] is Outcome.Missed && sourced[i].appleShowId == null && !sourced[i].pick.isShow
        }
        if (searchable.isNotEmpty()) {
            onProgress("Searching Apple for ${searchable.size} more...")
            gated(searchable) { i -> searchApple(sourced[i])?.let { outcomes[i] = Outcome.Episode(it) } }
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

    /**
     * The pick's episode from Apple's episode search, added from Apple's record under
     * its show with the id the show's feed gives it (its GUID), without pulling a feed
     * that can hold thousands of episodes (聽天下 has 4,840). See [AppleEpisodeSearch]
     * for what a result must show to count.
     */
    private suspend fun searchApple(s: Sourced): String? {
        val show = s.showTitle ?: return null
        val core = AppleEpisodeSearch.core(s.episodeTitle, show) ?: return null
        val results = runCatching { itunes.searchEpisodes(core) }
            .onFailure { Log.w(TAG, "Apple episode search for $core: ${it.message}") }
            .getOrNull() ?: return null
        val hit = AppleEpisodeSearch.pick(results, core, show, s.publishedApprox) ?: return null
        val showId = hit.collectionId ?: return null
        val found = runCatching { appleShows.resolve(showId) }.getOrNull()
        val podcast = podcasts.addPodcast((found as? AppleShow.Found)?.podcast ?: return null)
        // Already stored (a subscription, an earlier import), perhaps under a GUID the
        // feed has since rewritten: that row, not a second one.
        FeedEpisodeMatch.find(
            episodes = podcasts.episodesForPodcastOnce(podcast.id),
            title = hit.title,
            publishedApprox = day(hit.releaseDateMs),
            showTitle = podcast.title,
            guid = hit.guid,
            appleReleaseMs = hit.releaseDateMs,
        )?.let { return it }
        val member = IndexedValue(0, s.copy(apple = hit, guid = hit.guid))
        return fromApple(podcast, listOf(member))[0]
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

/**
 * Which result of an Apple episode search is the pick's episode. It is a last resort,
 * after the show's own feed (or none) failed, and it judges each result alone, without
 * the feed's other titles that would show its shared words. So a result must carry
 * the show's name, whole, in its show's name or as the tag its title opens with
 * (【微笑台灣Ep.192】 in 聽天下：天下雜誌Podcast; "Science explains…" is no tag); its title,
 * not counting the show's name, must hold every word of the chat's, short ones too (a
 * sibling "How Steve Jobs built Pixar" is not "…built Apple", nor "…of VR" "…of AI",
 * nor 【微笑台灣Ep.193】日本… 台灣…), and the same episode and part numbers where both
 * give any; it must be dated within two days of the chat's date when the link gave one
 * (a rerun is another episode); it needs a GUID, the id the feed gives the episode;
 * and the results that pass must be one show's, and one episode unless exactly one is
 * titled exactly so. Pure, for tests.
 */
internal object AppleEpisodeSearch {
    // Words saying it is a podcast, not which: "微笑台灣 Podcast" is 微笑台灣.
    private val GENERIC = setOf("podcast", "podcasts", "播客")

    private const val DATE_SLACK_DAYS = 2
    private const val DAY_MS = 86_400_000L

    private const val CJK = """\p{IsHan}\p{IsHiragana}\p{IsKatakana}\p{IsHangul}"""

    // A run of Chinese, Japanese or Korean; of digits; or of other letters.
    private val RUN = Regex("[$CJK]+|\\p{N}+|[\\p{L}&&[^$CJK]]+")

    // An episode marker before its number: "Ep268", "Ep.268", "EP 12", "Episode 268",
    // "Vol. 5", "#123", and "No" and "E" only touching it or with a dot ("No.5", "E913"),
    // so "No country", "維他命 E：5 個好處" and "Hepatitis E (2024)" keep their word.
    // Chinese may run straight into it: 微笑台灣Ep.192.
    private const val MARKER = """(?<![\p{L}&&[^$CJK]])(?:(?:episode|eps|ep|vol)\.?\s?#?|(?:no|e)\.?|#\s?)"""
    private val MARKED = Regex("(?i)$MARKER(?=\\d)")

    // The numbers saying which episode: after a marker, or 第 (第192集).
    private val NUMBERED = Regex("(?i)(?:$MARKER|第\\s?)(\\d++)")

    // A leading tag is bracketed, 【微笑台灣Ep.192】, or ends at a separator, 微笑台灣｜….
    private const val OPEN = "【[「《(（"
    private const val CLOSE = "】]」》)）"
    private const val SEPARATORS = ":：|｜-–—·"

    /** The episode's own words, without the chat's show name in front. */
    fun core(episodeTitle: String, show: String): String? {
        val title = episodeTitle.trimStart { !it.isLetterOrDigit() }
        // Only a name set off as a label comes off: "微笑台灣 Podcast：…", "《微笑台灣
        // Podcast》…", not 故事 from "故事的結局" or "Story" from "Storytelling explained".
        val after = title.removePrefix(show)
        val labelled = show.isNotEmpty() && title.startsWith(show) &&
            after.trimStart().firstOrNull()?.let { it in CLOSE || it in SEPARATORS } == true
        return (if (labelled) after else title).trimStart { !it.isLetterOrDigit() }.ifBlank { null }
    }

    fun pick(results: List<AppleEpisode>, core: String, show: String, publishedApprox: String?): AppleEpisode? {
        val name = name(show).ifEmpty { return null }
        val wanted = words(core).ifEmpty { return null }
        val numbers = numbers(core)
        val parts = RecentEpisodeMatcher.parts(core)
        val day = publishedApprox?.let { runCatching { LocalDate.parse(it) }.getOrNull() }?.toEpochDay()
        val hits = results.filter { a ->
            a.guid != null && a.audioUrl != null && a.collectionId != null &&
                namesShow(name, a) &&
                words(a.title, without = name).containsAll(wanted) &&
                agree(numbers, numbers(a.title)) &&
                agree(parts, RecentEpisodeMatcher.parts(a.title)) &&
                (day == null || abs(Math.floorDiv(a.releaseDateMs, DAY_MS) - day) <= DATE_SLACK_DAYS)
        }
        if (hits.map { it.collectionId }.distinct().size != 1) return null
        hits.singleOrNull()?.let { return it }
        val exact = RecentEpisodeMatcher.normalize(core)
        return hits.singleOrNull { RecentEpisodeMatcher.normalize(it.title) == exact }
    }

    /**
     * Every word, short ones too ("AI" is not "VR"), numbers apart from the letters
     * around them and without leading zeros, episode markers before a number left out,
     * and Chinese, Japanese and Korean as character pairs, the way the matcher reads them;
     * [without] the show's name (normalized), which is no word of the episode's.
     */
    internal fun words(s: String, without: String? = null): Set<String> {
        val text = RecentEpisodeMatcher.normalize(MARKED.replace(Normalizer.normalize(s, Normalizer.Form.NFKC), " "))
        return buildSet {
            RUN.findAll(without?.let { withoutName(text, it) } ?: text).forEach { run ->
                val w = run.value
                val first = w.codePointAt(0)
                when {
                    isCjk(first) -> {
                        val chars = w.codePoints().toArray().map { String(Character.toChars(it)) }
                        if (chars.size == 1) add(chars[0]) else chars.zipWithNext { a, b -> add(a + b) }
                    }
                    Character.isLetter(first) -> add(w)
                    else -> add(w.trimStart('0').ifEmpty { "0" })
                }
            }
        }
    }

    /** The matcher's episode numbers, and any after a marker here or 第: "Episode 268", 第192集. */
    private fun numbers(s: String): Set<String> =
        RecentEpisodeMatcher.episodeNumbers(s) +
            NUMBERED.findAll(Normalizer.normalize(s, Normalizer.Form.NFKC)).map { it.groupValues[1].trimStart('0').ifEmpty { "0" } }

    // Numbers given on both sides must be the same ones: "【微笑台灣Ep.193】Ep.192〈…〉聽眾回應"
    // answers Ep.192 but is not it.
    private fun agree(a: Set<String>, b: Set<String>) = a.isEmpty() || b.isEmpty() || a == b

    private fun namesShow(name: String, a: AppleEpisode): Boolean {
        if (nameAt(name(a.collectionName.orEmpty()), name).isNotEmpty()) return true
        // The title's tag is the name, perhaps with an episode number: 微笑台灣Ep.192.
        val tag = leadingTag(a.title) ?: return false
        val text = RecentEpisodeMatcher.normalize(MARKED.replace(tag, " "))
        if (!text.startsWith(name) || joins(name.last(), text.getOrNull(name.length))) return false
        return (words(text.removePrefix(name)) - GENERIC).all { Character.isDigit(it.codePointAt(0)) }
    }

    private fun leadingTag(title: String): String? {
        val raw = Normalizer.normalize(title, Normalizer.Form.NFKC).trim()
        if (raw.isEmpty()) return null
        if (raw[0] in OPEN) {
            val end = raw.indexOfAny(CLOSE.toCharArray(), 1)
            return if (end > 1) raw.substring(1, end) else null
        }
        val end = raw.indexOfAny(SEPARATORS.toCharArray())
        return if (end > 0) raw.substring(0, end) else null
    }

    private fun name(s: String) =
        RecentEpisodeMatcher.normalize(s).split(' ').filter { it.isNotEmpty() && it !in GENERIC }.joinToString(" ")

    /** Where [name] stands whole in [text], not inside a longer word ("Café" in "Cafés") or number. */
    private fun nameAt(text: String, name: String): List<Int> = buildList {
        var i = text.indexOf(name)
        while (i >= 0) {
            if (!joins(name.last(), text.getOrNull(i + name.length)) && !joins(name.first(), text.getOrNull(i - 1))) add(i)
            i = text.indexOf(name, i + 1)
        }
    }

    private fun withoutName(text: String, name: String): String {
        var out = text
        nameAt(text, name).asReversed().forEach { i -> out = out.replaceRange(i, minOf(i + name.length, out.length), " ") }
        return out
    }

    // Letters and digits run on into one word, except Chinese, Japanese and Korean,
    // which have no spaces to stop them: 微笑台灣 opens 【微笑台灣Ep.192】.
    private fun joins(edge: Char, next: Char?) =
        next != null && edge.isLetterOrDigit() && next.isLetterOrDigit() && !isCjk(edge.code) && !isCjk(next.code)

    private fun isCjk(codePoint: Int) = Character.UnicodeScript.of(codePoint) in CJK_SCRIPTS

    private val CJK_SCRIPTS = setOf(
        Character.UnicodeScript.HAN,
        Character.UnicodeScript.HIRAGANA,
        Character.UnicodeScript.KATAKANA,
        Character.UnicodeScript.HANGUL,
    )
}
