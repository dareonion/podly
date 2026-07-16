package com.podly.network

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException
import java.util.concurrent.TimeUnit

object Http {
    val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .followRedirects(true)
        .followSslRedirects(true)
        .build()

    val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
        explicitNulls = false
    }

    /**
     * The https variant of a cleartext URL, or null if it's already https. Old feeds
     * (and iTunes search results) are often still registered as http://, which Android
     * blocks — most of those hosts serve the same content over https.
     */
    fun httpsUpgradeOrNull(url: String): String? =
        if (url.startsWith("http://", ignoreCase = true)) "https://" + url.substring(7) else null

    suspend fun get(url: String, headers: Map<String, String> = emptyMap()): String {
        httpsUpgradeOrNull(url)?.let { upgraded ->
            try {
                return getOnce(upgraded, headers)
            } catch (_: IOException) {
                // Fall through to the original URL: on-device it fails as blocked
                // cleartext, the accurate error for a genuinely http-only host.
            }
        }
        return getOnce(url, headers)
    }

    private suspend fun getOnce(url: String, headers: Map<String, String>): String =
        withContext(Dispatchers.IO) {
            val request = Request.Builder().url(url).apply {
                header("User-Agent", "Podly/1.0")
                headers.forEach { (k, v) -> header(k, v) }
            }.build()
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) throw IOException("HTTP ${response.code} for $url")
                response.body?.string() ?: throw IOException("Empty body for $url")
            }
        }

    /** Result of a conditional GET: [body] is null iff the server said 304. */
    data class ConditionalResult(
        val notModified: Boolean,
        val body: String?,
        val etag: String?,
        val lastModified: String?,
    )

    /** GET with If-None-Match / If-Modified-Since so unchanged feeds cost one 304. */
    suspend fun getConditional(
        url: String,
        etag: String? = null,
        lastModified: String? = null,
    ): ConditionalResult {
        httpsUpgradeOrNull(url)?.let { upgraded ->
            try {
                return getConditionalOnce(upgraded, etag, lastModified)
            } catch (_: IOException) {
                // See get(): report the http-only failure, not the upgrade attempt's.
            }
        }
        return getConditionalOnce(url, etag, lastModified)
    }

    private suspend fun getConditionalOnce(
        url: String,
        etag: String?,
        lastModified: String?,
    ): ConditionalResult = withContext(Dispatchers.IO) {
        val request = Request.Builder().url(url).apply {
            header("User-Agent", "Podly/1.0")
            etag?.let { header("If-None-Match", it) }
            lastModified?.let { header("If-Modified-Since", it) }
        }.build()
        client.newCall(request).execute().use { response ->
            when {
                response.code == 304 -> ConditionalResult(true, null, etag, lastModified)
                !response.isSuccessful -> throw IOException("HTTP ${response.code} for $url")
                else -> {
                    val body = response.body?.string()
                        ?.takeIf { it.isNotBlank() } ?: throw IOException("Empty body for $url")
                    ConditionalResult(
                        notModified = false,
                        body = body,
                        etag = response.header("ETag"),
                        lastModified = response.header("Last-Modified"),
                    )
                }
            }
        }
    }
}
