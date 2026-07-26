package com.podly.generator

import com.anthropic.client.okhttp.AnthropicOkHttpClient
import com.anthropic.errors.AnthropicIoException
import com.anthropic.errors.SseException
import com.anthropic.models.messages.MessageCreateParams
import com.anthropic.models.messages.ThinkingConfigAdaptive
import com.anthropic.models.messages.WebSearchTool20260209
import java.io.IOException
import java.time.LocalDate

/**
 * Retries [block] on transient stream/IO errors (the same set the app's
 * AiRecommender retries); anything else — 4xx, parse failures — propagates
 * immediately so a bad request isn't paid for three times.
 */
internal fun <T> retryTransient(sleep: (Long) -> Unit = Thread::sleep, block: () -> T): T {
    var lastError: Throwable? = null
    repeat(3) { attempt ->
        if (attempt > 0) sleep(2_000L * attempt)
        try {
            return block()
        } catch (e: AnthropicIoException) {
            lastError = e
        } catch (e: SseException) {
            lastError = e
        } catch (e: IOException) {
            lastError = e
        }
    }
    throw lastError!!
}

/** The recent-episode windows, mirroring the app's `RecentEpisodeWindow`. */
enum class RecentWindow(
    val fileName: String,
    val dateRange: String,
) {
    // `name` (TWO_WEEKS / MONTH / THREE_MONTHS) is emitted as the file's `window`
    // field and must match the app's RecentEpisodeWindow enum names.
    TWO_WEEKS("recent-2weeks.json", "the past 2 weeks"),
    MONTH("recent-month.json", "the past month"),
    THREE_MONTHS("recent-3months.json", "the past 3 months");

    /** Coverage start for [end] (the run date), used for the displayed time span. */
    fun coverageStart(end: LocalDate): LocalDate = when (this) {
        TWO_WEEKS -> end.minusWeeks(2)
        MONTH -> end.minusMonths(1)
        THREE_MONTHS -> end.minusMonths(3)
    }
}

/** A topic area searched with its own small API call so every genre contributes. */
internal data class TopicArea(val label: String, val political: Boolean = false)

internal val TOPIC_AREAS = listOf(
    TopicArea("narrative and investigative storytelling"),
    TopicArea("science, technology, and health"),
    TopicArea("culture, society, and history"),
    TopicArea("interviews and conversations"),
    TopicArea("business, economics, and money"),
    TopicArea("comedy and casual chat shows"),
    TopicArea("sports and games"),
    TopicArea("arts and entertainment (music, film, TV, and books)"),
    TopicArea("news and politics", political = true),
)

/**
 * Round-robins across each area's picks so the list interleaves genres, drops
 * duplicate episodes, and keeps at most [maxPerShow] episodes per podcast. The
 * variety rules live here, in code, because prompt-level rules proved unreliable.
 */
internal fun mergeAreaPicks(
    perArea: List<List<RecentEpisodePick>>,
    maxPerShow: Int = 2,
): List<RecentEpisodePick> {
    fun norm(s: String) = s.lowercase().replace(Regex("[^a-z0-9]"), "")
    val seenEpisodes = mutableSetOf<String>()
    val perShow = mutableMapOf<String, Int>()
    val merged = mutableListOf<RecentEpisodePick>()
    for (round in 0 until (perArea.maxOfOrNull { it.size } ?: 0)) {
        for (area in perArea) {
            val pick = area.getOrNull(round) ?: continue
            if (!seenEpisodes.add(norm(pick.podcastTitle) + "|" + norm(pick.episodeTitle))) continue
            val show = norm(pick.podcastTitle)
            val count = perShow.getOrDefault(show, 0)
            if (count >= maxPerShow) continue
            perShow[show] = count + 1
            merged += pick
        }
    }
    return merged
}

/**
 * Calls Claude for the recent-episode and acclaimed lists.
 *
 * Recent episodes are gathered with one small web-search call per [TOPIC_AREAS]
 * entry and merged in [mergeAreaPicks]: a single mega-prompt asking for 20+
 * episodes across every genre reliably under-delivered (3-7 picks, mostly
 * political), while a focused "3 episodes about X" ask is consistently honored.
 * Only the politics area may return political episodes, so the merged list's
 * political share is capped structurally rather than by instruction.
 */
class RecsClient(apiKey: String) {
    private val client = AnthropicOkHttpClient.builder().apiKey(apiKey).build()

    fun recentEpisodes(window: RecentWindow): List<RecentEpisodePick> {
        val perArea = TOPIC_AREAS.map { area ->
            runCatching { parseArray<RecentEpisodePick>(ask(areaPrompt(window, area), webSearch = true)) }
                .onSuccess { println("[generator] ${window.name} / ${area.label}: ${it.size} picks") }
                .onFailure { System.err.println("[generator] ${window.name} / ${area.label} failed: ${it.message}") }
                .getOrDefault(emptyList())
        }
        val merged = mergeAreaPicks(perArea)
        // Better to keep the previously published list (Main's fallback) than to
        // deploy a near-empty one when most area calls came back thin or broken.
        if (merged.size < MIN_FRESH_PICKS) {
            throw IOException("Only ${merged.size} picks for ${window.name}; not worth publishing")
        }
        return merged
    }

    fun acclaimed(): List<AcclaimedItem> =
        parseArray(ask(acclaimedPrompt(), webSearch = false))

    private fun ask(prompt: String, webSearch: Boolean): String {
        val builder = MessageCreateParams.builder()
            .model("claude-opus-4-8")
            .maxTokens(if (webSearch) 12000L else 16000L)
            .thinking(
                ThinkingConfigAdaptive.builder()
                    .display(ThinkingConfigAdaptive.Display.SUMMARIZED)
                    .build()
            )
            .addUserMessage(prompt)
        if (webSearch) {
            builder.addTool(WebSearchTool20260209.builder().maxUses(AREA_WEB_SEARCHES).build())
        }
        val params = builder.build()
        // Stream so bytes keep flowing through the long web-search turn. Streams
        // still drop mid-turn occasionally ("Stream failed"); restart the request
        // rather than letting Main fall back to an empty payload.
        return retryTransient {
            val text = StringBuilder()
            client.messages().createStreaming(params).use { stream ->
                stream.stream().forEach { event ->
                    event.contentBlockDelta().ifPresent { deltaEvent ->
                        deltaEvent.delta().text().ifPresent { text.append(it.text()) }
                    }
                }
            }
            text.toString()
        }
    }

    private fun areaPrompt(window: RecentWindow, area: TopicArea): String = buildString {
        appendLine("You are an expert podcast critic and curator. Today's date is ${LocalDate.now()}.")
        appendLine(
            "Find the 3 most worthwhile individual podcast episodes about ${area.label} released in " +
                "${window.dateRange}. These episodes are more recent than your training data, so you " +
                "must use web search (you have up to $AREA_WEB_SEARCHES searches) to find real, " +
                "specific ones — do not rely on memory."
        )
        if (!area.political) {
            appendLine(
                "Skip episodes centered on politics or current political news, whoever the host or " +
                    "guest is — politics is covered separately."
            )
        }
        appendLine(
            "Choose episodes that were widely discussed, critically praised, deeply reported, " +
                "exceptionally useful, unusually moving, or genuinely fun to listen to. Only include " +
                "an episode when the search results give you its real, specific title: never invent a " +
                "placeholder like \"recent episode\", and never list a whole show or limited series " +
                "as if it were a single episode. If your searches surface fewer than 3 verifiable " +
                "episodes, return just the ones you can verify (even an empty array) rather than " +
                "inventing any. Do not re-search to verify titles."
        )
        appendLine()
        appendLine(
            "When you are done searching, respond with ONLY a JSON array as your final message — no " +
                "prose, no code fences, and no citation markers or footnotes outside the array. Each " +
                "element is " +
                "{\"podcastTitle\": string, \"episodeTitle\": string, " +
                "\"author\": string or null, \"reason\": string, \"publishedApprox\": string or null}. " +
                "Use the episode's exact published title when the search results show it (not a " +
                "paraphrase), and set publishedApprox to its release date as YYYY-MM-DD whenever you " +
                "can determine it. Keep each reason to one sentence explaining why the episode is worth listening to."
        )
    }

    private fun acclaimedPrompt(): String = buildString {
        appendLine("You are an expert on podcast awards and criticism. Today's date is ${LocalDate.now()}.")
        appendLine(
            "List the most acclaimed podcasts and specific podcast episodes from roughly the last 12 months: " +
                "winners and nominees of major awards (the Ambies, Peabody Awards, Pulitzer Prize for Audio " +
                "Reporting, duPont-Columbia Awards, Signal Awards, Webby podcast categories, British Podcast " +
                "Awards) and entries on prominent critics' best-of-the-year lists."
        )
        appendLine(
            "Include a mix of whole podcasts (new shows or standout seasons) and specific single episodes. " +
                "Only include real podcasts you are confident exist."
        )
        appendLine()
        appendLine(
            "Recommend exactly 12 items. Respond with ONLY a JSON array, no prose and no code fences, " +
                "where each element is {\"podcastTitle\": string, \"episodeTitle\": string or null, " +
                "\"author\": string, \"accolade\": string}. " +
                "Use null for episodeTitle when recommending the whole podcast. " +
                "Keep each accolade to one sentence naming the award, nomination, or list and its year."
        )
    }

    companion object {
        // Per-area budget; must stay well under Anthropic's server-side tool-loop
        // limit (~10), beyond which the turn pauses and never emits the final JSON.
        private const val AREA_WEB_SEARCHES = 2L

        // Below this a run isn't worth deploying over the previously published list.
        private const val MIN_FRESH_PICKS = 12

        /** Tolerates code fences or stray prose around the JSON array. */
        inline fun <reified T> parseArray(raw: String): List<T> {
            val start = raw.indexOf('[')
            val end = raw.lastIndexOf(']')
            if (start == -1 || end <= start) throw IOException("No JSON array in AI response")
            return json.decodeFromString(raw.substring(start, end + 1))
        }
    }
}
