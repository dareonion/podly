package com.podly.network.ai

import com.anthropic.client.okhttp.AnthropicOkHttpClient
import com.anthropic.models.messages.MessageCreateParams
import com.podly.data.AiProvider
import com.podly.network.Http
import com.podly.ui.util.friendlyError
import java.io.IOException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Request

/** What a key check found; [ok] decides how Settings colours the line. */
data class KeyCheckResult(val ok: Boolean, val message: String)

/**
 * Answers "is this key any good?" without waiting for a feature to fail.
 *
 * Two stages, because they cost differently. [validate] hits the models
 * endpoint, which returns metadata and bills no tokens, so it separates a
 * rejected key from an accepted one for free — but it cannot see the credit
 * balance, since only inference consumes credits. [checkCredits] sends the
 * smallest possible message (max_tokens = 1) and is the only way to catch the
 * "credit balance is too low" state before it surfaces mid-feature; it is a
 * separate, explicit tap because it costs a fraction of a cent.
 */
object ApiKeyCheck {

    /** Free: proves the key is accepted, says nothing about credits. */
    suspend fun validate(provider: AiProvider, apiKey: String): KeyCheckResult =
        withContext(Dispatchers.IO) {
            if (apiKey.isBlank()) return@withContext KeyCheckResult(false, "No key entered.")
            runCatching {
                when (provider) {
                    AiProvider.CLAUDE -> {
                        val client = AnthropicOkHttpClient.builder().apiKey(apiKey).build()
                        try {
                            client.models().list()
                        } finally {
                            client.close()
                        }
                    }
                    AiProvider.OPENAI -> {
                        val request = Request.Builder()
                            .url("https://api.openai.com/v1/models")
                            .header("Authorization", "Bearer $apiKey")
                            .build()
                        Http.client.newCall(request).execute().use { response ->
                            if (!response.isSuccessful) {
                                throw IOException(
                                    "${response.code}: ${response.body?.string().orEmpty()}"
                                )
                            }
                        }
                    }
                }
            }.fold(
                onSuccess = {
                    KeyCheckResult(true, "Key accepted. This check is free, so it can't see credits.")
                },
                onFailure = { KeyCheckResult(false, friendlyError(it)) },
            )
        }

    /** Paid, barely: one token on the model the app actually uses. */
    suspend fun checkCredits(apiKey: String): KeyCheckResult = withContext(Dispatchers.IO) {
        if (apiKey.isBlank()) return@withContext KeyCheckResult(false, "No key entered.")
        val client = AnthropicOkHttpClient.builder().apiKey(apiKey).build()
        try {
            // No thinking config: Opus 4.8 runs without it when omitted, so a
            // one-token ceiling is legal and the call stays as small as possible.
            client.messages().create(
                MessageCreateParams.builder()
                    .model(AiRecommender.CLAUDE_MODEL)
                    .maxTokens(1L)
                    .addUserMessage("hi")
                    .build()
            )
            KeyCheckResult(true, "Key works and the account has credits.")
        } catch (e: Exception) {
            KeyCheckResult(false, friendlyError(e))
        } finally {
            client.close()
        }
    }
}
