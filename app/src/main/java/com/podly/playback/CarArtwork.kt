package com.podly.playback

import android.content.ContentProvider
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.database.Cursor
import android.net.Uri
import android.os.ParcelFileDescriptor
import androidx.core.net.toUri
import androidx.media3.common.MediaItem
import com.podly.network.Http
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.FileNotFoundException
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

/**
 * Show and episode covers hosted on the tailnet, for Android Auto.
 *
 * Tailscale's per-app VPN on the phone covers Podly but leaves Android Auto
 * (`com.google.android.projection.gearhead`) out, so a cover on a `*.ts.net` host never
 * loaded in the car while public https covers did (Desktop Head Unit, 2026-11-20). Those
 * covers go to Android Auto as `content://` URIs instead, which [CarArtworkProvider]
 * answers by fetching the image over Podly's own connection; Android's guidance for car
 * artwork is content URIs anyway. Every other cover, and every other browser, keeps the
 * feed's URL.
 */
internal object CarArtwork {
    const val ANDROID_AUTO_PACKAGE = "com.google.android.projection.gearhead"
    private const val MAX_BYTES = 4L * 1024 * 1024
    private const val MAX_FILES = 200
    private val FRESH_MS = TimeUnit.DAYS.toMillis(7)

    // Short, because the provider fetches on a binder thread while the car waits; no
    // redirects, so a fetch never leaves the tailnet host it was checked against.
    private val client: OkHttpClient by lazy {
        Http.client.newBuilder()
            .callTimeout(10, TimeUnit.SECONDS)
            .followRedirects(false)
            .followSslRedirects(false)
            .build()
    }
    private val locks = ConcurrentHashMap<String, Any>()
    /** When each URL last failed, so the requests queued behind a failure fail with it. */
    private val failedAt = ConcurrentHashMap<String, Long>()
    private val RETRY_AFTER_MS = TimeUnit.MINUTES.toMillis(1)

    /** A MagicDNS name or a Tailscale address (100.64.0.0/10). */
    fun isTailnetHost(host: String?): Boolean {
        val h = host?.lowercase()?.trimEnd('.') ?: return false
        if (h.endsWith(".ts.net")) return true
        val parts = h.split('.')
        if (parts.size != 4) return false
        val octets = parts.map { part -> part.toIntOrNull()?.takeIf { it in 0..255 } ?: return false }
        return octets[0] == 100 && octets[1] in 64..127
    }

    private fun isTailnetUrl(uri: Uri): Boolean =
        uri.scheme?.lowercase() in setOf("http", "https") && isTailnetHost(uri.host)

    fun authority(context: Context) = "${context.packageName}.artwork"

    fun contentUri(authority: String, url: String): Uri =
        Uri.Builder().scheme("content").authority(authority).appendPath("art")
            .appendQueryParameter("u", url).build()

    /** The tailnet URL a provider URI stands for; null for anything else. */
    fun sourceUrl(uri: Uri): String? =
        uri.getQueryParameter("u")?.takeIf { isTailnetUrl(it.toUri()) }

    /**
     * [items] as [packageName] should see them: for Android Auto, tailnet covers become
     * provider URIs it is granted to read. Runs per browse request, so the grant always
     * covers what was just served.
     */
    fun forBrowser(context: Context, packageName: String, items: List<MediaItem>): List<MediaItem> {
        if (packageName != ANDROID_AUTO_PACKAGE) return items
        val authority = authority(context)
        return items.map { item ->
            val art = item.mediaMetadata.artworkUri
            if (art == null || !isTailnetUrl(art)) return@map item
            val local = contentUri(authority, art.toString())
            context.grantUriPermission(packageName, local, Intent.FLAG_GRANT_READ_URI_PERMISSION)
            item.buildUpon()
                .setMediaMetadata(item.mediaMetadata.buildUpon().setArtworkUri(local).build())
                .build()
        }
    }

    /**
     * The cached copy of [url] under [cacheRoot], fetched first when missing or a week
     * old (an old copy still serves if the refetch fails). One fetch per URL at a time,
     * written to a temporary file and renamed only once complete.
     */
    fun cached(cacheRoot: File, url: String, httpClient: OkHttpClient = client): File {
        val dir = File(cacheRoot, "car-artwork").apply { mkdirs() }
        val file = File(dir, sha256(url))
        fun fresh() = file.length() > 0 && System.currentTimeMillis() - file.lastModified() < FRESH_MS
        if (fresh()) return file
        synchronized(locks.getOrPut(url) { Any() }) {
            if (fresh()) return file
            // A cover just found unreachable: a screenful of rows sharing it would
            // otherwise wait out one timeout each, in turn, on binder threads.
            val lastFailure = failedAt[url]
            if (lastFailure != null && System.currentTimeMillis() - lastFailure < RETRY_AFTER_MS) {
                if (file.length() > 0) return file
                throw FileNotFoundException("cover unavailable (failed moments ago)")
            }
            val tmp = File.createTempFile("fetch", ".tmp", dir)
            try {
                val request = Request.Builder().url(url).header("User-Agent", "Podly/1.0").build()
                httpClient.newCall(request).execute().use { response ->
                    if (!response.isSuccessful) throw IOException("HTTP ${response.code}")
                    response.body.byteStream().use { input -> tmp.outputStream().use { copyCapped(input, it, MAX_BYTES) } }
                }
                if (tmp.length() == 0L) throw IOException("empty cover")
                if (!tmp.renameTo(file)) throw IOException("could not store the cover")
                failedAt.remove(url)
            } catch (e: IOException) {
                failedAt[url] = System.currentTimeMillis()
                if (file.length() > 0) return file
                throw FileNotFoundException("cover unavailable: ${e.message}")
            } finally {
                tmp.delete()
            }
            prune(dir)
        }
        return file
    }

    internal fun copyCapped(input: InputStream, output: OutputStream, maxBytes: Long) {
        val buffer = ByteArray(16 * 1024)
        var total = 0L
        while (true) {
            val read = input.read(buffer)
            if (read < 0) break
            total += read
            if (total > maxBytes) throw IOException("cover over $maxBytes bytes")
            output.write(buffer, 0, read)
        }
    }

    /** Keeps the newest [MAX_FILES] covers. */
    private fun prune(dir: File) {
        val files = dir.listFiles { f -> !f.name.endsWith(".tmp") } ?: return
        if (files.size <= MAX_FILES) return
        files.sortedByDescending { it.lastModified() }.drop(MAX_FILES).forEach { it.delete() }
    }

    private fun sha256(text: String): String =
        MessageDigest.getInstance("SHA-256").digest(text.toByteArray())
            .joinToString("") { "%02x".format(it) }
}

/**
 * Serves [CarArtwork] covers to the apps they are granted to (not exported). Only URIs
 * standing for a tailnet http(s) URL are answered.
 */
class CarArtworkProvider : ContentProvider() {
    override fun onCreate(): Boolean = true

    override fun getType(uri: Uri): String = "image/*"

    override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor {
        if (mode != "r") throw SecurityException("covers are read-only")
        val url = CarArtwork.sourceUrl(uri) ?: throw FileNotFoundException("not a tailnet cover")
        val cacheRoot = context?.cacheDir ?: throw FileNotFoundException("no context")
        return try {
            ParcelFileDescriptor.open(CarArtwork.cached(cacheRoot, url), ParcelFileDescriptor.MODE_READ_ONLY)
        } catch (_: FileNotFoundException) {
            // Pruned by another request between the lookup and the open: fetch it again.
            ParcelFileDescriptor.open(CarArtwork.cached(cacheRoot, url), ParcelFileDescriptor.MODE_READ_ONLY)
        }
    }

    override fun query(
        uri: Uri,
        projection: Array<out String>?,
        selection: String?,
        selectionArgs: Array<out String>?,
        sortOrder: String?,
    ): Cursor? = null

    override fun insert(uri: Uri, values: ContentValues?): Uri? = null
    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int = 0
    override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?): Int = 0
}
