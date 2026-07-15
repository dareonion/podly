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

/**
 * Calls Claude for the recent-episode and acclaimed lists. The prompts moved
 * here verbatim from the app's `AiRecommender`; the app no longer builds them.
 *
 * Unlike on-device, there's no mobile radio to keep alive, so this could relax
 * the streaming/effort caps — but the proven on-device params already produce
 * good lists, so we keep them (minus the mobile-only MEDIUM effort override).
 */
class RecsClient(apiKey: String) {
    private val client = AnthropicOkHttpClient.builder().apiKey(apiKey).build()

    fun recentEpisodes(window: RecentWindow): List<RecentEpisodePick> =
        parseArray(ask(recentPrompt(window), webSearch = true))

    fun acclaimed(): List<AcclaimedItem> =
        parseArray(ask(acclaimedPrompt(), webSearch = false))

    private fun ask(prompt: String, webSearch: Boolean): String {
        val builder = MessageCreateParams.builder()
            .model("claude-opus-4-8")
            .maxTokens(if (webSearch) 32000L else 16000L)
            .thinking(
                ThinkingConfigAdaptive.builder()
                    .display(ThinkingConfigAdaptive.Display.SUMMARIZED)
                    .build()
            )
            .addUserMessage(prompt)
        if (webSearch) {
            builder.addTool(WebSearchTool20260209.builder().maxUses(WEB_SEARCH_MAX_USES).build())
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

    private fun recentPrompt(window: RecentWindow): String = buildString {
        appendLine("You are an expert podcast critic and curator. Today's date is ${LocalDate.now()}.")
        appendLine(
            "Find the most worthwhile individual podcast episodes released in ${window.dateRange}. These " +
                "episodes are more recent than your training data, so you must use web search to find " +
                "real, specific ones — do not rely on memory. Spend your budget of up to " +
                "$WEB_SEARCH_MAX_USES searches running ONE focused search for standout recent episodes " +
                "in each of these areas: (1) news and politics; (2) narrative and investigative " +
                "storytelling; (3) interviews and conversations; (4) science, technology, and health; " +
                "(5) business, economics, and money; (6) culture, society, and history; (7) comedy and " +
                "casual chat shows. Use any remaining searches for a broad best-of-the-period roundup."
        )
        appendLine(
            "From each search's results take the 2-3 strongest episodes — ones that were widely " +
                "discussed, critically praised, deeply reported, exceptionally useful, unusually moving, " +
                "culturally important, or (for the comedy and casual category) genuinely funny or fun to " +
                "listen to. Only include an episode when the search results give you its " +
                "real, specific title: never invent a placeholder like \"recent episode\", and never " +
                "list a whole show or limited series as if it were a single episode. Your final list " +
                "must contain at least 10 episodes (ideally 12-15): take the two strongest from each of " +
                "the seven areas — that alone is about 14 — and include at least one lighter comedic or " +
                "casual pick rather than only serious ones. Every episode must be real and verifiable " +
                "from your searches — never invent titles to reach the count; if one area is thin, take " +
                "more from a stronger area or run another roundup search. Do not re-search to verify titles."
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
        // Stays at 9 to remain under Anthropic's server-side tool-loop limit (~10),
        // beyond which the turn pauses with pause_turn and never emits the final JSON.
        private const val WEB_SEARCH_MAX_USES = 9L

        /** Tolerates code fences or stray prose around the JSON array. */
        inline fun <reified T> parseArray(raw: String): List<T> {
            val start = raw.indexOf('[')
            val end = raw.lastIndexOf(']')
            if (start == -1 || end <= start) throw IOException("No JSON array in AI response")
            return json.decodeFromString(raw.substring(start, end + 1))
        }
    }
}
