package com.podly.generator

import java.io.File
import java.time.LocalDate
import kotlin.system.exitProcess

/** Base URL of the published site, used to recover a file when a fresh run fails. */
private const val BASE_URL = "https://dareonion.github.io/podly/"
private const val ACCLAIMED_FILE = "acclaimed.json"
private const val ACCLAIMED_COVERAGE = "the last 12 months"

/**
 * Generates the static recommendation files and writes them to OUTPUT_DIR (default `site`).
 *
 * Each payload is best-effort: if a fresh generation fails, the previously published
 * file is re-fetched and re-emitted so a deploy never drops a list; if even that's
 * unavailable (first run), a minimal empty payload is written. The process exits
 * non-zero only when EVERY payload failed, so a total outage leaves the prior deploy intact.
 */
fun main() {
    val apiKey = System.getenv("ANTHROPIC_API_KEY")?.takeIf { it.isNotBlank() }
        ?: run {
            System.err.println("ANTHROPIC_API_KEY is not set")
            exitProcess(2)
        }
    val outDir = File(System.getenv("OUTPUT_DIR")?.takeIf { it.isNotBlank() } ?: "site")
    outDir.mkdirs()

    val client = RecsClient(apiKey)
    val now = LocalDate.now()
    val generatedAtMs = System.currentTimeMillis()
    var successes = 0

    for (window in RecentWindow.values()) {
        val fresh = generate(
            outDir = outDir,
            fileName = window.fileName,
            produce = {
                val picks = client.recentEpisodes(window)
                log("${window.name}: ${picks.size} picks from Claude; resolving against iTunes…")
                json.encodeToString(RecentFile.serializer(), recentFile(window, now, generatedAtMs, picks))
            },
            emptyFallback = {
                json.encodeToString(RecentFile.serializer(), recentFile(window, now, generatedAtMs, emptyList()))
            },
        )
        if (fresh) successes++
    }

    val acclaimedFresh = generate(
        outDir = outDir,
        fileName = ACCLAIMED_FILE,
        produce = {
            val items = client.acclaimed()
            log("acclaimed: ${items.size} items from Claude; resolving against iTunes…")
            json.encodeToString(AcclaimedFile.serializer(), acclaimedFile(generatedAtMs, items))
        },
        emptyFallback = {
            json.encodeToString(AcclaimedFile.serializer(), acclaimedFile(generatedAtMs, emptyList()))
        },
    )
    if (acclaimedFresh) successes++

    if (successes == 0) {
        System.err.println("All payloads failed; leaving the previously published site intact.")
        exitProcess(1)
    }
    log("Wrote ${outDir.listFiles()?.size ?: 0} files to ${outDir.absolutePath} ($successes freshly generated).")
}

/**
 * Writes [produce]'s JSON to [outDir]/[fileName]. On failure, re-publishes the
 * currently-live file when reachable, else writes [emptyFallback]. Returns true
 * only on a fresh generation.
 */
private fun generate(
    outDir: File,
    fileName: String,
    produce: () -> String,
    emptyFallback: () -> String,
): Boolean {
    return try {
        File(outDir, fileName).writeText(produce())
        true
    } catch (e: Exception) {
        System.err.println("Failed to generate $fileName: ${e.message}")
        val live = runCatching { Net.get(BASE_URL + fileName) }.getOrNull()
        if (live != null) log("Reusing previously published $fileName.")
        else log("No live fallback for $fileName; writing an empty payload.")
        File(outDir, fileName).writeText(live ?: emptyFallback())
        false
    }
}

private fun recentFile(
    window: RecentWindow,
    now: LocalDate,
    generatedAtMs: Long,
    picks: List<RecentEpisodePick>,
) = RecentFile(
    generatedAtMs = generatedAtMs,
    window = window.name,
    coverageStart = window.coverageStart(now).toString(),
    coverageEnd = now.toString(),
    picks = picks.map { it.resolved() },
)

private fun acclaimedFile(generatedAtMs: Long, items: List<AcclaimedItem>) = AcclaimedFile(
    generatedAtMs = generatedAtMs,
    coverageLabel = ACCLAIMED_COVERAGE,
    picks = items.map { it.resolved() },
)

private fun RecentEpisodePick.resolved(): ResolvedRecentPick {
    val p = Itunes.resolve(podcastTitle)
    return ResolvedRecentPick(
        pick = this,
        podcastId = p?.podcastId,
        podcastTitle = p?.title,
        podcastAuthor = p?.author,
        feedUrl = p?.feedUrl,
        artworkUrl = p?.artworkUrl,
        podcastDescription = p?.description,
    )
}

private fun AcclaimedItem.resolved(): ResolvedAcclaimedPick {
    val p = Itunes.resolve(podcastTitle)
    return ResolvedAcclaimedPick(
        pick = this,
        podcastId = p?.podcastId,
        podcastTitle = p?.title,
        podcastAuthor = p?.author,
        feedUrl = p?.feedUrl,
        artworkUrl = p?.artworkUrl,
        podcastDescription = p?.description,
    )
}

private fun log(message: String) = println("[generator] $message")
