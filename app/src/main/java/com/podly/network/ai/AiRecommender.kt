package com.podly.network.ai

import com.anthropic.client.okhttp.AnthropicOkHttpClient
import com.anthropic.errors.AnthropicIoException
import com.anthropic.errors.SseException
import com.anthropic.models.messages.MessageCreateParams
import com.anthropic.models.messages.ThinkingConfigAdaptive
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

    suspend fun whereToStart(
        podcastTitle: String,
        author: String?,
        episodeTitles: List<String>,
    ): List<AiEpisodePick> =
        parseEpisodePicks(ask(buildWhereToStartPrompt(podcastTitle, author, episodeTitles)))

    private suspend fun ask(prompt: String): String {
        val settings = settingsRepository.current()
        val call: suspend () -> String = when (settings.aiProvider) {
            AiProvider.CLAUDE -> {
                val key = settings.anthropicApiKey
                if (key.isBlank()) throw IllegalStateException("Add your Anthropic API key in Settings first.")
                ({ askClaude(key, prompt) })
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

    private suspend fun askClaude(apiKey: String, prompt: String): String =
        withContext(Dispatchers.IO) {
            val client = AnthropicOkHttpClient.builder().apiKey(apiKey).build()
            try {
                val params = MessageCreateParams.builder()
                    .model("claude-opus-4-8")
                    .maxTokens(16000L)
                    // Summarized display makes thinking stream as deltas; the default
                    // ("omitted") keeps the stream silent for the whole thinking phase,
                    // long enough for phone radios to abort the idle socket.
                    .thinking(
                        ThinkingConfigAdaptive.builder()
                            .display(ThinkingConfigAdaptive.Display.SUMMARIZED)
                            .build()
                    )
                    .addUserMessage(prompt)
                    .build()
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

        fun parseEpisodePicks(raw: String): List<AiEpisodePick> = decodeArray(raw)

        private const val MAX_TITLES_IN_PROMPT = 1000

        private inline fun <reified T> decodeArray(raw: String): List<T> {
            val start = raw.indexOf('[')
            val end = raw.lastIndexOf(']')
            if (start == -1 || end <= start) throw IOException("No JSON array in AI response")
            return Http.json.decodeFromString(raw.substring(start, end + 1))
        }
    }
}
