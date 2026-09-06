package com.podly.data.radio

import com.podly.data.db.EpisodeDao
import com.podly.data.db.EpisodeEntity
import com.podly.data.db.RadioCandidateRow
import com.podly.data.db.RadioDao
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
    private val profiles: RadioProfileStore,
) {

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
        val discovery = radioDao.discovery(
            profileId = profile.id,
            nowMs = nowMs,
            maxDurationMs = profile.maxDurationMs ?: 0L,
            limit = PREFILTER_LIMIT,
        )
        val picked = RadioScorer.nextBatch(
            backlog = backlog.map { RadioCandidate(it, discovery = false) },
            discovery = discovery.map { RadioCandidate(it, discovery = true) },
            profile = profile,
            nowMs = nowMs,
            exclude = exclude,
            count = count,
            random = random,
        )
        return picked.mapNotNull { episodeDao.byId(it.episodeId) }
            .also { episodes -> episodes.forEach { onServed(profile.id, it.id, nowMs) } }
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

    fun poolCount(profileId: String): Flow<Int> =
        radioDao.poolCount(profileId, System.currentTimeMillis())

    private companion object {
        /** How many rows each SQL slice returns before scoring narrows them down. */
        const val PREFILTER_LIMIT = 150
    }
}
