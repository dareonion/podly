package com.podly.network.ai

import com.anthropic.client.okhttp.AnthropicOkHttpClient
import com.anthropic.models.messages.MessageCreateParams
import com.anthropic.models.messages.ThinkingConfigAdaptive
import com.podly.data.AiProvider
import com.podly.data.SettingsRepository
import com.podly.data.db.EpisodeDao
import com.podly.data.db.PodcastDao
import com.podly.network.Http
import kotlinx.coroutines.Dispatchers
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

@Serializable
data class AiRecommendation(
    val title: String,
    val author: String? = null,
    val reason: String,
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
    suspend fun recommend(): List<AiRecommendation> {
        val settings = settingsRepository.current()
        val prompt = buildPrompt()
        val rawText = when (settings.aiProvider) {
            AiProvider.CLAUDE -> {
                val key = settings.anthropicApiKey
                if (key.isBlank()) throw IllegalStateException("Add your Anthropic API key in Settings first.")
                askClaude(key, prompt)
            }
            AiProvider.OPENAI -> {
                val key = settings.openAiApiKey
                if (key.isBlank()) throw IllegalStateException("Add your OpenAI API key in Settings first.")
                askOpenAi(key, prompt)
            }
        }
        return parseRecommendations(rawText)
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

    private suspend fun askClaude(apiKey: String, prompt: String): String =
        withContext(Dispatchers.IO) {
            val client = AnthropicOkHttpClient.builder().apiKey(apiKey).build()
            try {
                val params = MessageCreateParams.builder()
                    .model("claude-opus-4-8")
                    .maxTokens(16000L)
                    .thinking(ThinkingConfigAdaptive.builder().build())
                    .addUserMessage(prompt)
                    .build()
                val response = client.messages().create(params)
                response.content()
                    .mapNotNull { block -> block.text().map { it.text() }.orElse(null) }
                    .joinToString("\n")
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
            Http.client.newCall(request).execute().use { response ->
                val text = response.body?.string() ?: throw IOException("Empty response")
                if (!response.isSuccessful) throw IOException("OpenAI HTTP ${response.code}: ${text.take(200)}")
                Http.json.parseToJsonElement(text)
                    .jsonObject["choices"]!!.jsonArray[0]
                    .jsonObject["message"]!!.jsonObject["content"]!!.jsonPrimitive.content
            }
        }

    companion object {
        /** Tolerates code fences or stray prose around the JSON array. */
        fun parseRecommendations(raw: String): List<AiRecommendation> {
            val start = raw.indexOf('[')
            val end = raw.lastIndexOf(']')
            if (start == -1 || end <= start) throw IOException("No JSON array in AI response")
            return Http.json.decodeFromString(raw.substring(start, end + 1))
        }
    }
}
