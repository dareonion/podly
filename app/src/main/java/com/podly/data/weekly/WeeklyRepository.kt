package com.podly.data.weekly

import com.podly.data.db.DigestRow
import com.podly.data.db.RadioDao
import com.podly.data.radio.RadioRepository
import com.podly.network.Http
import com.podly.network.RemoteRecsApi
import com.podly.radio.RadioProfiles
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import java.io.File

/** `weekly/index.json`: every published week, newest first. */
@Serializable
data class WeeklyIndexFile(
    val version: Int = 1,
    val issues: List<WeeklyIssue> = emptyList(),
)

@Serializable
data class WeeklyIssue(
    /** ISO week, "2026-W37". */
    val id: String,
    val label: String = "",
    val weekStart: String = "",
    val weekEnd: String = "",
    val file: String = "",
    val generatedAtMs: Long = 0,
    val entryCount: Int = 0,
    /** Picks per language family, "en" and "zh". */
    val counts: Map<String, Int> = emptyMap(),
    /** The models whose judgement that week's ranking reflects, e.g. ["claude", "codex"]. */
    val pickedBy: List<String> = emptyList(),
)

/**
 * The weekly digest: last week's best episodes in English and Chinese, chosen by
 * Claude and Codex together and published by `podly-radio weekly`.
 *
 * Each week is an ordinary radio-pool file, so it hydrates through
 * [RadioRepository.replaceDiscovery] like every other pool: podcasts go in
 * unsubscribed, episodes through the upsert that never clobbers progress, and
 * the blurb rides as the pool row's reason. Weeks are stored under their own
 * pool ids, so only the newest week, synced as [RadioProfiles.WEEKLY_ID] by the
 * pool worker, is blended into radio as a pool. A browsed back-issue's episodes
 * are still ordinary rows, so a profile with `includeUnsubscribed` can reach them
 * through its backlog, like any episode opened from Discover.
 */
class WeeklyRepository(
    private val remote: RemoteRecsApi,
    private val radio: RadioRepository,
    private val radioDao: RadioDao,
    private val indexCache: File,
) {

    /** The published weeks, newest first. Falls back to the last good copy when offline. */
    suspend fun issues(): List<WeeklyIssue> = withContext(Dispatchers.IO) {
        val fresh = remote.weeklyIndex()
        runCatching {
            indexCache.parentFile?.mkdirs()
            indexCache.writeText(Http.json.encodeToString(WeeklyIndexFile.serializer(), fresh))
        }
        fresh.issues.filter { isValidIssueId(it.id) }
    }

    suspend fun cachedIssues(): List<WeeklyIssue> = withContext(Dispatchers.IO) {
        runCatching {
            Http.json.decodeFromString(WeeklyIndexFile.serializer(), indexCache.readText())
                .issues.filter { isValidIssueId(it.id) }
        }.getOrDefault(emptyList())
    }

    /** Downloads one week and materialises it under that week's own pool id. */
    suspend fun load(issue: WeeklyIssue) {
        val pool = remote.weeklyIssue(issue.file)
        // Never trust the file's own id: a file naming "you" would overwrite radio.
        radio.replaceDiscovery(pool.copy(profileId = poolIdFor(issue.id)))
    }

    /** One week's picks, best first; the newest synced week when [issueId] is null. */
    fun entries(issueId: String?): Flow<List<DigestRow>> =
        radioDao.digestEntries(issueId?.let(::poolIdFor) ?: RadioProfiles.WEEKLY_ID)

    companion object {
        private val ISSUE_ID = Regex("\\d{4}-W\\d{2}")

        fun isValidIssueId(id: String): Boolean = ISSUE_ID.matches(id)

        fun poolIdFor(issueId: String): String = "weekly-${issueId.lowercase()}"

        fun isChinese(row: DigestRow): Boolean = row.language?.startsWith("zh") == true

        /**
         * The top [count] picks, alternating languages while both last.
         *
         * Straight score order can fill a short teaser from one language, and the
         * digest exists to cover both.
         */
        fun interleaveLanguages(rows: List<DigestRow>, count: Int): List<DigestRow> {
            val (chinese, english) = rows.partition(::isChinese)
            val out = mutableListOf<DigestRow>()
            var i = 0
            while (out.size < count && (i < english.size || i < chinese.size)) {
                english.getOrNull(i)?.let { if (out.size < count) out += it }
                chinese.getOrNull(i)?.let { if (out.size < count) out += it }
                i++
            }
            return out
        }
    }
}
