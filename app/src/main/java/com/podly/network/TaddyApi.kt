package com.podly.network

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonPrimitive
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

/** A series as Taddy knows it; [rssUrl] may differ from the URL we looked up. */
@Serializable
data class TaddySeries(
    val uuid: String? = null,
    val name: String? = null,
    val rssUrl: String? = null,
    val episodes: List<TaddyEpisode> = emptyList(),
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

/**
 * Taddy podcast API (GraphQL). Used to look up a show's recent episodes — including
 * ones that have rolled off its RSS feed — by feed URL or by name. Creds (a numeric
 * user id and an API key from taddy.org) are user-supplied; auth is the `X-USER-ID` /
 * `X-API-KEY` headers.
 */
class TaddyApi {
    /**
     * The series (with its latest [limit] episodes, newest first) for [feedUrl], or
     * null if Taddy doesn't index that URL. Throws on HTTP/GraphQL errors so the
     * caller can fall back to another provider.
     */
    suspend fun seriesByFeedUrl(
        userId: String,
        apiKey: String,
        feedUrl: String,
        limit: Int = 25,
    ): TaddySeries? = lookup(userId, apiKey, "rssUrl", "String", JsonPrimitive(feedUrl), limit)

    /** As [seriesByFeedUrl], but by show name (Taddy picks the most popular match). */
    suspend fun seriesByName(
        userId: String,
        apiKey: String,
        name: String,
        limit: Int = 25,
    ): TaddySeries? = lookup(userId, apiKey, "name", "String", JsonPrimitive(name), limit)

    /** The feed Taddy has on file for an Apple Podcasts id, or null if it has none. */
    suspend fun feedByItunesId(userId: String, apiKey: String, itunesId: Long): DirectoryFeed? =
        // Taddy types iTunes ids as Float ("to support 64-bit integers"); declaring
        // the variable Int is a GraphQL error, not a miss.
        lookup(userId, apiKey, "itunesId", "Float", JsonPrimitive(itunesId), episodeLimit = 0)
            ?.let { series ->
                series.rssUrl?.ifBlank { null }?.let { DirectoryFeed(feedUrl = it, title = series.name) }
            }

    private suspend fun lookup(
        userId: String,
        apiKey: String,
        argName: String,
        argType: String,
        argValue: JsonPrimitive,
        episodeLimit: Int,
    ): TaddySeries? = withContext(Dispatchers.IO) {
        val payload = buildJsonObject {
            put("query", query(argName, argType, episodeLimit))
            putJsonObject("variables") { put("v", argValue) }
        }
        val request = Request.Builder()
            .url("https://api.taddy.org")
            .header("X-USER-ID", userId)
            .header("X-API-KEY", apiKey)
            .header("User-Agent", "Podly/1.0")
            .post(payload.toString().toRequestBody("application/json".toMediaType()))
            .build()
        Http.client.newCall(request).execute().use { response ->
            val text = response.body.string()
            if (!response.isSuccessful) throw IOException("Taddy HTTP ${response.code}: ${text.take(200)}")
            parse(text)
        }
    }

    companion object {
        /**
         * Passes the lookup value as a variable so it needn't be escaped into the
         * query. Taddy requires every type's selection to include `uuid` ("The type
         * PodcastSeries is required to return the property uuid").
         */
        fun query(argName: String, argType: String, episodeLimit: Int): String {
            val episodes = if (episodeLimit > 0) {
                "episodes(sortOrder:LATEST,limitPerPage:$episodeLimit){" +
                    "uuid name description audioUrl datePublished duration guid imageUrl}"
            } else {
                ""
            }
            return "query(\$v:$argType!){getPodcastSeries($argName:\$v){uuid name rssUrl $episodes}}"
        }

        /**
         * Extracts the series from a Taddy GraphQL response body; null means Taddy
         * doesn't know the show. GraphQL errors arrive as HTTP 200 + an `errors`
         * array (e.g. API_KEY_INVALID for bad creds), so they must throw here rather
         * than read as "not found".
         */
        fun parse(json: String): TaddySeries? {
            val response = Http.json.decodeFromString<TaddyResponse>(json)
            response.errors.firstOrNull()?.let {
                throw IOException("Taddy ${it.code ?: "error"}: ${it.message ?: "unknown error"}")
            }
            return response.data?.getPodcastSeries
        }
    }
}
