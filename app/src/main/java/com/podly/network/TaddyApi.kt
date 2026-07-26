package com.podly.network

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException

/** One episode from Taddy's GraphQL API. `datePublished`/`duration` are in seconds. */
@Serializable
data class TaddyEpisode(
    val name: String? = null,
    val description: String? = null,
    val audioUrl: String? = null,
    val datePublished: Long = 0,
    val duration: Long? = null,
    val guid: String? = null,
    val imageUrl: String? = null,
)

@Serializable
private data class TaddyError(val message: String? = null, val code: String? = null)

@Serializable
private data class TaddyResponse(
    val data: TaddyData? = null,
    val errors: List<TaddyError> = emptyList(),
)

@Serializable
private data class TaddyData(val getPodcastSeries: TaddySeries? = null)

@Serializable
private data class TaddySeries(val episodes: List<TaddyEpisode> = emptyList())

/**
 * Taddy podcast API (GraphQL). Used to look up a show's recent episodes — including
 * ones that have rolled off its RSS feed — by feed URL. Creds (a numeric user id and
 * an API key from taddy.org) are user-supplied; auth is the `X-USER-ID` / `X-API-KEY`
 * headers.
 */
class TaddyApi {
    /**
     * Latest [limit] episodes for [feedUrl] from Taddy's archive (newest first).
     * Throws on HTTP/GraphQL errors so the caller can fall back to another provider.
     */
    suspend fun episodesByFeedUrl(
        userId: String,
        apiKey: String,
        feedUrl: String,
        limit: Int = 25,
    ): List<TaddyEpisode> = withContext(Dispatchers.IO) {
        // Pass the feed URL as a variable so it needn't be escaped into the query.
        val query = "query(\$url:String!){getPodcastSeries(rssUrl:\$url){" +
            "episodes(sortOrder:LATEST,limitPerPage:$limit){" +
            "name description audioUrl datePublished duration guid imageUrl}}}"
        val payload = buildJsonObject {
            put("query", query)
            putJsonObject("variables") { put("url", feedUrl) }
        }
        val request = Request.Builder()
            .url("https://api.taddy.org")
            .header("X-USER-ID", userId)
            .header("X-API-KEY", apiKey)
            .header("User-Agent", "Podly/1.0")
            .post(payload.toString().toRequestBody("application/json".toMediaType()))
            .build()
        Http.client.newCall(request).execute().use { response ->
            val text = response.body?.string() ?: throw IOException("Empty Taddy response")
            if (!response.isSuccessful) throw IOException("Taddy HTTP ${response.code}: ${text.take(200)}")
            parse(text)
        }
    }

    companion object {
        /**
         * Extracts the episode list from a Taddy GraphQL response body. GraphQL errors
         * arrive as HTTP 200 + an `errors` array (e.g. API_KEY_INVALID for bad creds),
         * so they must throw here — an empty list means "Taddy doesn't know this feed".
         */
        fun parse(json: String): List<TaddyEpisode> {
            val response = Http.json.decodeFromString<TaddyResponse>(json)
            response.errors.firstOrNull()?.let {
                throw IOException("Taddy ${it.code ?: "error"}: ${it.message ?: "unknown error"}")
            }
            return response.data?.getPodcastSeries?.episodes.orEmpty()
        }
    }
}
