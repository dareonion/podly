package com.podly.data

import com.podly.network.TranscriptCue

/** Built once per transcript; each position lookup is a binary search of timed starts. */
class TranscriptCueLookup(cues: List<TranscriptCue>) {
    private val timed = cues.withIndex().filter { it.value.startMs != null }.sortedBy { it.value.startMs }

    // HTML/JSON may omit ends; their next start then supplies the boundary (null: runs on).
    private val ends: List<Long?> = timed.indices.map { i ->
        timed[i].value.endMs ?: timed.getOrNull(i + 1)?.value?.startMs
    }

    // Latest end among cues 0..i, so a backward scan for a longer cue still running
    // (VTT allows overlaps) stops as soon as nothing earlier can contain the position.
    private val latestEnd: LongArray = LongArray(timed.size).also { arr ->
        var running = Long.MIN_VALUE
        for (i in timed.indices) {
            running = maxOf(running, ends[i] ?: Long.MAX_VALUE)
            arr[i] = running
        }
    }

    /** Returns the original display index, or -1 in a gap or outside the transcript. */
    fun at(positionMs: Long): Int {
        var low = 0
        var high = timed.size
        while (low < high) {
            val mid = (low + high) ushr 1
            if (timed[mid].value.startMs!! <= positionMs) low = mid + 1 else high = mid
        }
        var i = low - 1
        while (i >= 0 && latestEnd[i] > positionMs) {
            val end = ends[i]
            if (end == null || positionMs < end) return timed[i].index
            i--
        }
        return -1
    }
}
