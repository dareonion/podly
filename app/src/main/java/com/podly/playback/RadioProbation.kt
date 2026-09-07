package com.podly.playback

import com.podly.data.db.ListeningSegmentEntity

/**
 * A radio pick the listener has not yet stuck with.
 *
 * Radio starts episodes the listener never asked for, so anything written about
 * one before they have shown they want it is noise: a rejected pick would sit in
 * Continue listening at eight seconds and in History for ever. Everything is
 * withheld until [COMMIT_MS] of real listening, then written back retroactively
 * so a kept pick loses nothing.
 *
 * It carries its own [profileId] because it outlives the radio session: stopping
 * radio is not the same as keeping what happened to be playing, and a probation
 * cleared on stop let the next progress save record a fourteen-second pick.
 */
internal class RadioProbation(val episodeId: String, val profileId: String) {

    private val buffered = mutableListOf<ListeningSegmentEntity>()

    /** Listening already accounted for by a finished segment. */
    var listenedMs: Long = 0
        private set

    val segments: List<ListeningSegmentEntity> get() = buffered

    fun covers(episodeId: String?): Boolean = episodeId == this.episodeId

    /** Holds a segment back from History, and counts it toward committing. */
    fun buffer(segment: ListeningSegmentEntity) {
        if (!covers(segment.episodeId)) return
        buffered += segment
        listenedMs += (segment.endPositionMs - segment.startPositionMs).coerceAtLeast(0)
    }

    /** Includes the in-flight segment, so a continuously playing pick commits on time. */
    fun listenedIncluding(liveMs: Long): Long = listenedMs + liveMs.coerceAtLeast(0)

    /**
     * An episode that reached the end counts however short it was: a four-minute
     * kids' story can finish without ever passing the threshold.
     */
    fun shouldCommit(liveMs: Long, ended: Boolean): Boolean =
        ended || listenedIncluding(liveMs) >= COMMIT_MS

    companion object {
        /** Listening this long makes a radio pick count. */
        const val COMMIT_MS = 90_000L
    }
}
