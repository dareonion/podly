package com.podly.network.ai

import com.anthropic.client.okhttp.AnthropicOkHttpClient
import com.anthropic.errors.AnthropicIoException
import com.anthropic.errors.SseException
import com.anthropic.models.messages.MessageCreateParams
import com.anthropic.models.messages.OutputConfig
import com.anthropic.models.messages.ThinkingConfigAdaptive
import com.anthropic.models.messages.WebSearchTool20260209
import com.podly.data.AiProvider
import com.podly.data.SettingsRepository
import com.podly.data.db.EpisodeDao
import com.podly.data.db.PodcastDao
import com.podly.network.Http
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
import java.time.LocalDate
import java.util.concurrent.TimeUnit

@Serializable
data class AiRecommendation(
    val title: String,
    val author: String? = null,
    val reason: String,
)

/** An acclaimed podcast, or a specific episode of one when [episodeTitle] is set. */
@Serializable
data class AiAcclaimedPick(
    val podcastTitle: String,
    val episodeTitle: String? = null,
    val author: String? = null,
    val accolade: String,
)

/** A recommended entry-point episode within a single podcast. */
@Serializable
data class AiEpisodePick(
    val episodeTitle: String,
    val reason: String,
)

enum class RecentEpisodeWindow(val label: String) {
    TWO_WEEKS("2 weeks"),
    MONTH("Month"),
    THREE_MONTHS("3 months"),
}

/** A high-signal recent individual episode across all podcasts. */
@Serializable
data class AiRecentEpisodePick(
    val podcastTitle: String,
    val episodeTitle: String,
    val author: String? = null,
    val reason: String,
    val publishedApprox: String? = null,
)

/**
 * Builds a profile of the user's listening from the local DB and asks the
 * configured provider (Claude or OpenAI) for podcast recommendations.
 */
class AiRecommender(
    private val settingsRepository: SettingsRepository,
    private val podcastDao: PodcastDao,
    private val episodeDao: EpisodeDao,
) {
    /** AI completions routinely exceed Http.client's 60s read timeout. */
    private val openAiClient = Http.client.newBuilder()
        .readTimeout(10, TimeUnit.MINUTES)
        .build()

    suspend fun recommend(): List<AiRecommendation> = parseRecommendations(ask(buildPrompt()))

    suspend fun acclaimed(): List<AiAcclaimedPick> = parseAcclaimed(ask(buildAcclaimedPrompt()))

    suspend fun whereToStart(
        podcastTitle: String,
        author: String?,
        episodeTitles: List<String>,
    ): List<AiEpisodePick> =
        parseEpisodePicks(ask(buildWhereToStartPrompt(podcastTitle, author, episodeTitles)))

    // Episodes this recent are past the model's training cutoff, so it needs
    // web search to find real ones — without it the model returns an empty list.
    suspend fun recentEpisodes(window: RecentEpisodeWindow): List<AiRecentEpisodePick> =
        parseRecentEpisodePicks(ask(buildRecentEpisodesPrompt(window), webSearch = true))

    private suspend fun ask(prompt: String, webSearch: Boolean = false): String {
        val settings = settingsRepository.current()
        val call: suspend () -> String = when (settings.aiProvider) {
            AiProvider.CLAUDE -> {
                val key = settings.anthropicApiKey
                if (key.isBlank()) throw IllegalStateException("Add your Anthropic API key in Settings first.")
                ({ askClaude(key, prompt, webSearch) })
            }
            AiProvider.OPENAI -> {
                val key = settings.openAiApiKey
                if (key.isBlank()) throw IllegalStateException("Add your OpenAI API key in Settings first.")
                ({ askOpenAi(key, prompt) })
            }
        }
        // Phone radios abort long-lived sockets (power save, network handoff);
        // those surface as transient I/O errors, so retry before giving up.
        var lastError: Throwable? = null
        repeat(3) { attempt ->
            if (attempt > 0) delay(2_000L * attempt)
            try {
                return call()
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

    private suspend fun buildPrompt(): String {
        val subscribed = podcastDao.subscribedPodcastsOnce()
        val recent = episodeDao.recentlyPlayed(15)
        return buildString {
            appendLine("You are a podcast recommendation engine.")
            if (subscribed.isEmpty() && recent.isEmpty()) {
                appendLine("The listener is new and has no subscriptions yet; recommend broadly appealing, high-quality podcasts across a few genres.")
            } else {
                appendLine("The listener subscribes to these podcasts:")
                subscribed.forEach { appendLine("- ${it.title} by ${it.author}") }
                if (recent.isNotEmpty()) {
                    appendLine("Recently played episodes:")
                    recent.forEach { appendLine("- \"${it.title}\" from ${it.podcastTitle}") }
                }
            }
            appendLine()
            appendLine(
                "Recommend exactly 10 podcasts they do not already subscribe to. " +
                    "Respond with ONLY a JSON array, no prose and no code fences, where each element is " +
                    "{\"title\": string, \"author\": string, \"reason\": string}. " +
                    "Keep each reason to one sentence tied to their listening taste."
            )
        }
    }

    private fun buildAcclaimedPrompt(): String = buildString {
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

    private fun buildWhereToStartPrompt(
        podcastTitle: String,
        author: String?,
        episodeTitles: List<String>,
    ): String = buildString {
        appendLine("You are a podcast expert. Today's date is ${LocalDate.now()}.")
        appendLine(
            "A listener wants to get into the podcast \"$podcastTitle\"" +
                (author?.takeIf { it.isNotBlank() }?.let { " by $it" } ?: "") +
                " and wants to know which episodes to start with."
        )
        val titles = episodeTitles.take(MAX_TITLES_IN_PROMPT)
        if (titles.isNotEmpty()) {
            appendLine("The show has ${episodeTitles.size} episodes. Episode titles, newest first:")
            titles.forEach { appendLine("- $it") }
            if (episodeTitles.size > titles.size) {
                appendLine("(the ${episodeTitles.size - titles.size} oldest episodes are omitted)")
            }
        }
        appendLine()
        appendLine(
            "Recommend the 8 best entry-point episodes (fewer if the show has fewer): the most " +
                "acclaimed, most recommended, and most beloved episodes that work without prior " +
                "context. If the show is serialized and best experienced from the beginning, " +
                "start the list with the first episode and say so. " +
                "Respond with ONLY a JSON array, no prose and no code fences, where each element " +
                "is {\"episodeTitle\": string, \"reason\": string}. " +
                "Copy each episodeTitle exactly as it appears in the list above. " +
                "Keep each reason to one sentence."
        )
    }

    private fun buildRecentEpisodesPrompt(window: RecentEpisodeWindow): String {
        val dateRange = when (window) {
            RecentEpisodeWindow.TWO_WEEKS -> "the past 2 weeks"
            RecentEpisodeWindow.MONTH -> "the past month"
            RecentEpisodeWindow.THREE_MONTHS -> "the past 3 months"
        }
        return buildString {
            appendLine("You are an expert podcast critic and curator. Today's date is ${LocalDate.now()}.")
            appendLine(
                "Find the most worthwhile individual podcast episodes released in $dateRange. These " +
                    "episodes are more recent than your training data, so you must use web search to find " +
                    "real, specific ones — do not rely on memory. Spend your budget of up to " +
                    "$WEB_SEARCH_MAX_USES searches running ONE focused search for standout recent episodes " +
                    "in each of these areas: (1) news and politics; (2) narrative and investigative " +
                    "storytelling; (3) interviews and conversations; (4) science, technology, and health; " +
                    "(5) business, economics, and money; (6) culture, society, and history. Use any " +
                    "remaining searches for a broad best-of-the-period roundup."
            )
            appendLine(
                "From each search's results take the 1-2 strongest episodes — ones that were widely " +
                    "discussed, critically praised, deeply reported, exceptionally useful, unusually moving, " +
                    "or culturally important. Only include an episode when the search results give you its " +
                    "real, specific title: never invent a placeholder like \"recent episode\", and never " +
                    "list a whole show or limited series as if it were a single episode. Quality matters " +
                    "more than quantity — return up to 12 episodes, but a shorter list of genuine, " +
                    "verifiable ones is far better than a padded list. Do not re-search to verify titles."
            )
            appendLine()
            appendLine(
                "When you are done searching, respond with ONLY a JSON array as your final message — no " +
                    "prose, no code fences, and no citation markers or footnotes outside the array. Each " +
                    "element is " +
                    "{\"podcastTitle\": string, \"episodeTitle\": string, " +
                    "\"author\": string or null, \"reason\": string, \"publishedApprox\": string or null}. " +
                    "Keep each reason to one sentence explaining why the episode is worth listening to."
            )
        }
    }

    private suspend fun askClaude(apiKey: String, prompt: String, webSearch: Boolean): String =
        withContext(Dispatchers.IO) {
            val client = AnthropicOkHttpClient.builder().apiKey(apiKey).build()
            try {
                val builder = MessageCreateParams.builder()
                    .model("claude-opus-4-8")
                    // Web-search calls also pull tool results into context and reason
                    // over them, so give the response room beyond the JSON itself.
                    .maxTokens(if (webSearch) 24000L else 16000L)
                    // Summarized display makes thinking stream as deltas; the default
                    // ("omitted") keeps the stream silent for the whole thinking phase,
                    // long enough for phone radios to abort the idle socket.
                    .thinking(
                        ThinkingConfigAdaptive.builder()
                            .display(ThinkingConfigAdaptive.Display.SUMMARIZED)
                            .build()
                    )
                    .addUserMessage(prompt)
                // Server-side web search lets the model look up real episodes that
                // are newer than its training cutoff. Cap the number of searches:
                // uncapped, the model runs many sequential searches and the streaming
                // request stretches to many minutes, long enough that a phone radio
                // drops the connection mid-flight (surfaces as "Unable to resolve host").
                if (webSearch) {
                    builder.addTool(
                        WebSearchTool20260209.builder().maxUses(WEB_SEARCH_MAX_USES).build()
                    )
                    // At the default (high) effort the model deliberates at length over
                    // each search result, stretching the call past what a mobile network
                    // tolerates. Medium effort keeps it to a couple of minutes.
                    builder.outputConfig(
                        OutputConfig.builder().effort(OutputConfig.Effort.MEDIUM).build()
                    )
                }
                val params = builder.build()
                // Stream so bytes keep flowing while the model thinks; mobile
                // networks drop idle connections, which surfaced as "Request failed"
                // on long non-streaming calls.
                val text = StringBuilder()
                client.messages().createStreaming(params).use { stream ->
                    stream.stream().forEach { event ->
                        event.contentBlockDelta().ifPresent { deltaEvent ->
                            deltaEvent.delta().text().ifPresent { text.append(it.text()) }
                        }
                    }
                }
                text.toString()
            } finally {
                client.close()
            }
        }

    private suspend fun askOpenAi(apiKey: String, prompt: String): String =
        withContext(Dispatchers.IO) {
            val body = buildJsonObject {
                put("model", "gpt-5")
                put("messages", buildJsonArray {
                    add(buildJsonObject {
                        put("role", "user")
                        put("content", prompt)
                    })
                })
            }
            val request = Request.Builder()
                .url("https://api.openai.com/v1/chat/completions")
                .header("Authorization", "Bearer $apiKey")
                .post(body.toString().toRequestBody("application/json".toMediaType()))
                .build()
            openAiClient.newCall(request).execute().use { response ->
                val text = response.body?.string() ?: throw IOException("Empty response")
                if (!response.isSuccessful) throw IOException("OpenAI HTTP ${response.code}: ${text.take(200)}")
                Http.json.parseToJsonElement(text)
                    .jsonObject["choices"]!!.jsonArray[0]
                    .jsonObject["message"]!!.jsonObject["content"]!!.jsonPrimitive.content
            }
        }

    companion object {
        /** Tolerates code fences or stray prose around the JSON array. */
        fun parseRecommendations(raw: String): List<AiRecommendation> = decodeArray(raw)

        fun parseAcclaimed(raw: String): List<AiAcclaimedPick> = decodeArray(raw)

        fun parseEpisodePicks(raw: String): List<AiEpisodePick> = decodeArray(raw)

        fun parseRecentEpisodePicks(raw: String): List<AiRecentEpisodePick> = decodeArray(raw)

        private const val MAX_TITLES_IN_PROMPT = 1000

        /**
         * Cap on server-side web searches per recent-episodes call. Keeps the
         * streaming request short enough to finish before a mobile radio drops the
         * connection, and under the server-side tool-loop limit (~10) that would
         * otherwise end the turn with `pause_turn` and no final JSON.
         */
        private const val WEB_SEARCH_MAX_USES = 8L

        private inline fun <reified T> decodeArray(raw: String): List<T> {
            val start = raw.indexOf('[')
            val end = raw.lastIndexOf(']')
            if (start == -1 || end <= start) throw IOException("No JSON array in AI response")
            return Http.json.decodeFromString(raw.substring(start, end + 1))
        }
    }
}
