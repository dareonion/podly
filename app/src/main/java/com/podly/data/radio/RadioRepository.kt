package com.podly.data.radio

import com.podly.data.db.EpisodeDao
import com.podly.data.db.EpisodeEntity
import com.podly.data.db.RadioCandidateRow
import com.podly.data.db.RadioDao
import com.podly.network.RemoteRecsApi
import com.podly.data.db.RadioPoolEntity
import com.podly.data.db.PodcastEntity
import com.podly.data.db.PodcastDao
import com.podly.data.db.RadioFeedbackEntity
import com.podly.radio.RadioCandidate
import com.podly.radio.RadioCooldown
import com.podly.radio.RadioProfile
import com.podly.radio.RadioProfiles
import com.podly.radio.RadioScorer
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlin.random.Random

/** What radio should play next, and what the user thought of what it played. */
class RadioRepository(
    private val radioDao: RadioDao,
    private val episodeDao: EpisodeDao,
    private val podcastDao: PodcastDao,
    private val profiles: RadioProfileStore,
    private val remoteRecs: RemoteRecsApi,
) {

    /** An episode radio would play, and why it is on the list. */
    data class Recommendation(
        val episode: EpisodeEntity,
        /** The pool's citation, or null for something out of the user's backlog. */
        val reason: String?,
        val discovery: Boolean,
    )

    /**
     * What radio would play, in order, without committing to any of it.
     *
     * Deliberately does not mark anything served: this backs a list the user
     * browses, and marking a hundred episodes as served because they scrolled
     * past would poison the very ranking they are reading.
     */
    suspend fun recommendations(
        profile: RadioProfile,
        count: Int = 50,
        nowMs: Long = System.currentTimeMillis(),
        random: Random = Random(nowMs / 3_600_000),
    ): List<Recommendation> {
        val picked = pickCandidates(profile, count, emptySet(), nowMs, random)
        val byId = episodeDao.byIds(picked.map { it.episodeId }).associateBy { it.id }
        return picked.mapNotNull { candidate ->
            byId[candidate.episodeId]?.let { episode ->
                Recommendation(episode, candidate.row.reason, candidate.discovery)
            }
        }
    }

    /**
     * The next [count] episodes for [profile], best first.
     *
     * Returns entities rather than ids: every item is a row that existed a moment ago,
     * which is what stops the playback service silently dropping an unresolvable id.
     */
    suspend fun nextBatch(
        profile: RadioProfile,
        count: Int,
        exclude: Set<String> = emptySet(),
        nowMs: Long = System.currentTimeMillis(),
        random: Random = Random.Default,
    ): List<EpisodeEntity> {
        val picked = pickCandidates(profile, count, exclude, nowMs, random)
        return picked.mapNotNull { episodeDao.byId(it.episodeId) }
            .also { episodes -> episodes.forEach { onServed(profile.id, it.id, nowMs) } }
    }

    /** The shared ranking behind both [nextBatch] and [recommendations]. */
    private suspend fun pickCandidates(
        profile: RadioProfile,
        count: Int,
        exclude: Set<String>,
        nowMs: Long,
        random: Random,
    ): List<RadioCandidate> {
        val allowed = profiles.selectedShowsOnce(profile.id)
        val restrict = profile.restrictBacklogToSelectedShows
        // A restricted profile with nothing chosen must play nothing at all: that
        // empty allowlist is the toddler content gate, not an oversight.
        val backlog: List<RadioCandidateRow> =
            if (restrict && allowed.isEmpty()) {
                emptyList()
            } else {
                val args = BacklogArgs(profile, allowed, restrict, nowMs)
                (recentBacklog(args) + randomBacklog(args)).distinctBy { it.episodeId }
            }
        // Notable episodes live in their own pool and are blended in here rather
        // than copied into every profile's pool, so there is one list of them.
        val notable = if (profile.includeNotable) {
            radioDao.discovery(
                profileId = RadioProfiles.NOTABLE_ID,
                nowMs = nowMs,
                maxDurationMs = profile.maxDurationMs ?: 0L,
                limit = PREFILTER_LIMIT,
            )
        } else {
            emptyList()
        }
        val discovery = (
            radioDao.discovery(
                profileId = profile.id,
                nowMs = nowMs,
                maxDurationMs = profile.maxDurationMs ?: 0L,
                limit = PREFILTER_LIMIT,
            ) + notable
            ).distinctBy { it.episodeId }
        // An unsubscribed pool episode satisfies the backlog query too (that query
        // stopped requiring a subscription when radio was widened to unsubscribed
        // shows), so the same row can arrive in both buckets. The pool copy wins:
        // it is the one carrying the citation and the generator's priority, and
        // whichever bucket wins also decides which share the pick is counted
        // against.
        val discoveryIds = discovery.mapTo(HashSet()) { it.episodeId }
        val backlogOnly = backlog.filterNot { it.episodeId in discoveryIds }
        return RadioScorer.nextBatch(
            backlog = backlogOnly.map { RadioCandidate(it, discovery = false) },
            discovery = discovery.map { RadioCandidate(it, discovery = true) },
            profile = profile,
            nowMs = nowMs,
            exclude = exclude,
            count = count,
            random = random,
        )
    }

    private class BacklogArgs(
        val profile: RadioProfile,
        val allowed: Set<String>,
        val restrict: Boolean,
        val nowMs: Long,
    )

    private suspend fun recentBacklog(a: BacklogArgs) = radioDao.backlogRecent(
        profileId = a.profile.id,
        nowMs = a.nowMs,
        includeUnsubscribed = if (a.profile.includeUnsubscribed) 1 else 0,
        restrictShows = if (a.restrict) 1 else 0,
        allowedPodcastIds = a.allowed.toList().ifEmpty { listOf("") },
        maxDurationMs = a.profile.maxDurationMs ?: 0L,
        minDurationMs = a.profile.minDurationMs ?: 0L,
        limit = PREFILTER_LIMIT,
    )

    private suspend fun randomBacklog(a: BacklogArgs) = radioDao.backlogRandom(
        profileId = a.profile.id,
        nowMs = a.nowMs,
        includeUnsubscribed = if (a.profile.includeUnsubscribed) 1 else 0,
        restrictShows = if (a.restrict) 1 else 0,
        allowedPodcastIds = a.allowed.toList().ifEmpty { listOf("") },
        maxDurationMs = a.profile.maxDurationMs ?: 0L,
        minDurationMs = a.profile.minDurationMs ?: 0L,
        limit = PREFILTER_LIMIT,
    )

    suspend fun onServed(profileId: String, episodeId: String, nowMs: Long = System.currentTimeMillis()) {
        val prev = radioDao.feedback(profileId, episodeId)
            ?: RadioFeedbackEntity(profileId, episodeId)
        radioDao.putFeedback(
            prev.copy(lastServedAt = nowMs, serveCount = prev.serveCount + 1),
        )
    }

    /** "Not now": records a decaying cooldown, never a permanent block. */
    suspend fun onSkipped(
        profileId: String,
        episodeId: String,
        listenedMs: Long = 0,
        nowMs: Long = System.currentTimeMillis(),
    ) {
        val prev = radioDao.feedback(profileId, episodeId)
            ?: RadioFeedbackEntity(profileId, episodeId)
        val skips = RadioCooldown.effectiveSkipCount(prev.skipCount, prev.lastSkippedAt, nowMs) + 1
        radioDao.putFeedback(
            prev.copy(
                skipCount = skips,
                lastSkippedAt = nowMs,
                listenedMs = prev.listenedMs + listenedMs,
                blockedUntil = RadioCooldown.blockedUntil(skips, nowMs),
            ),
        )
    }

    /** Kept: clears any cooldown the episode had accumulated. */
    suspend fun onAccepted(profileId: String, episodeId: String, listenedMs: Long = 0) {
        val prev = radioDao.feedback(profileId, episodeId)
            ?: RadioFeedbackEntity(profileId, episodeId)
        radioDao.putFeedback(
            prev.copy(
                skipCount = 0,
                blockedUntil = 0,
                listenedMs = prev.listenedMs + listenedMs,
            ),
        )
    }

    suspend fun clearCooldowns(profileId: String) = radioDao.clearCooldowns(profileId)

    /** Downloads the published pool for [profileId] and materialises it. */
    suspend fun syncPool(profileId: String) {
        replaceDiscovery(remoteRecs.radioPool(profileId))
    }

    /**
     * Materialises a downloaded pool into rows radio can actually play.
     *
     * Deliberately does no network: every entry already carries the podcast and
     * episode fields, so this is pure local insertion. Podcasts go in
     * unsubscribed and episodes through upsertFromFeed, which never clobbers
     * progress or download state if the user already has the episode.
     */
    suspend fun replaceDiscovery(pool: RadioPoolFile) {
        val profileId = pool.profileId
        val catalogVersion = pool.generatedAtMs
        val podcasts = mutableMapOf<String, PodcastEntity>()
        val episodes = mutableListOf<EpisodeEntity>()
        val rows = mutableListOf<RadioPoolEntity>()
        val now = System.currentTimeMillis()

        pool.entries.forEach { entry ->
            if (entry.episode.audioUrl.isBlank() || entry.episode.pubDateMs <= 0) return@forEach
            podcasts.getOrPut(entry.podcast.id) {
                PodcastEntity(
                    id = entry.podcast.id,
                    title = entry.podcast.title,
                    author = entry.podcast.author,
                    feedUrl = entry.podcast.feedUrl,
                    artworkUrl = entry.podcast.artworkUrl,
                    description = entry.podcast.description,
                    subscribed = false,
                )
            }
            episodes += EpisodeEntity(
                id = entry.episode.id,
                podcastId = entry.podcast.id,
                podcastTitle = entry.podcast.title,
                guid = entry.episode.guid,
                title = entry.episode.title,
                description = entry.episode.description,
                audioUrl = entry.episode.audioUrl,
                pubDateMs = entry.episode.pubDateMs,
                durationMs = entry.episode.durationMs,
                artworkUrl = entry.episode.artworkUrl ?: entry.podcast.artworkUrl,
            )
            rows += RadioPoolEntity(
                profileId = profileId,
                episodeId = entry.episode.id,
                reason = entry.why,
                language = entry.language ?: entry.podcast.language,
                priority = entry.score,
                catalogVersion = catalogVersion,
                addedAt = now,
            )
        }

        podcasts.values.forEach { podcastDao.insertIgnore(it) }
        episodeDao.upsertFromFeed(episodes)
        radioDao.upsertPool(rows)
        radioDao.pruneOldCatalog(profileId, catalogVersion)
        radioDao.pruneUnplayablePool()
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    fun backlogCount(profileId: String): Flow<Int> {
        val profile = RadioProfiles.byId(profileId)
        return profiles.selectedShows(profileId).flatMapLatest { allowed ->
            radioDao.backlogCount(
                profileId = profileId,
                nowMs = System.currentTimeMillis(),
                includeUnsubscribed = if (profile.includeUnsubscribed) 1 else 0,
                restrictShows = if (profile.restrictBacklogToSelectedShows) 1 else 0,
                allowedPodcastIds = allowed.toList().ifEmpty { listOf("") },
                maxDurationMs = profile.maxDurationMs ?: 0L,
            )
        }
    }

    /** Pool size as the card reports it: the profile's own picks plus notable. */
    fun poolCount(profileId: String): Flow<Int> =
        radioDao.poolCount(profileId, System.currentTimeMillis())

    private companion object {
        /** How many rows each SQL slice returns before scoring narrows them down. */
        const val PREFILTER_LIMIT = 150
    }
}
