package com.podly.generator

import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException
import java.util.concurrent.TimeUnit

/** Shared HTTP client for iTunes lookups and reading the currently-published files. */
object Net {
    val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .build()

    /** GET [url] as text, or throw on a non-2xx / empty response. */
    fun get(url: String): String {
        val request = Request.Builder()
            .url(url)
            .header("User-Agent", "Podly-Generator/1.0")
            .build()
        client.newCall(request).execute().use { response ->
            val body = response.body?.string()
            if (!response.isSuccessful) throw IOException("HTTP ${response.code} for $url")
            return body?.takeIf { it.isNotBlank() } ?: throw IOException("Empty body for $url")
        }
    }
}
