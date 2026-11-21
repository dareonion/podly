package com.podly.playback

import android.app.Application
import android.content.Context
import androidx.core.net.toUri
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.sun.net.httpserver.HttpServer
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.FileNotFoundException
import java.io.IOException
import java.net.InetSocketAddress
import java.util.concurrent.atomic.AtomicInteger
import okhttp3.OkHttpClient
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

// Plain Application: PodlyApp's onCreate spins up WorkManager, which these tests don't need.
@Config(application = Application::class)
@RunWith(AndroidJUnit4::class)
class CarArtworkTest {

    @get:Rule
    val temporaryFolder = TemporaryFolder()

    private val context: Context = ApplicationProvider.getApplicationContext()
    private var server: HttpServer? = null

    @After
    fun stopServer() {
        server?.stop(0)
    }

    @Test
    fun tailnetHostsAreMagicDnsNamesAndTailscaleAddresses() {
        assertTrue(CarArtwork.isTailnetHost("box.tail1234.ts.net"))
        assertTrue(CarArtwork.isTailnetHost("BOX.TAIL1234.TS.NET."))
        assertTrue(CarArtwork.isTailnetHost("100.64.0.1"))
        assertTrue(CarArtwork.isTailnetHost("100.127.255.254"))
        assertFalse(CarArtwork.isTailnetHost("100.128.0.1"))
        assertFalse(CarArtwork.isTailnetHost("100.63.0.1"))
        assertFalse(CarArtwork.isTailnetHost("example.com"))
        assertFalse(CarArtwork.isTailnetHost("ts.net.example.com"))
        assertFalse(CarArtwork.isTailnetHost(null))
    }

    @Test
    fun providerUrisStandOnlyForTailnetUrls() {
        val url = "https://box.tail1234.ts.net/feeds/art.png?v=2&x=a b"
        val uri = CarArtwork.contentUri("com.podly.artwork", url)
        assertEquals("content", uri.scheme)
        assertEquals(url, CarArtwork.sourceUrl(uri))
        assertNull(CarArtwork.sourceUrl(CarArtwork.contentUri("com.podly.artwork", "https://example.com/a.png")))
        assertNull(CarArtwork.sourceUrl(CarArtwork.contentUri("com.podly.artwork", "file://box.tail1234.ts.net/a.png")))
    }

    @Test
    fun onlyAndroidAutoGetsProviderUrisAndOnlyForTailnetCovers() {
        val tailnet = item("a", "https://box.tail1234.ts.net/art.png")
        val public = item("b", "https://example.com/art.png")
        val none = item("c", null)
        val items = listOf(tailnet, public, none)

        val forAuto = CarArtwork.forBrowser(context, CarArtwork.ANDROID_AUTO_PACKAGE, items)
        assertEquals("content", forAuto[0].mediaMetadata.artworkUri?.scheme)
        assertEquals(
            "https://box.tail1234.ts.net/art.png",
            CarArtwork.sourceUrl(forAuto[0].mediaMetadata.artworkUri!!),
        )
        assertEquals("a", forAuto[0].mediaId)
        assertEquals(public, forAuto[1])
        assertEquals(none, forAuto[2])

        assertEquals(items, CarArtwork.forBrowser(context, "com.android.systemui", items))
    }

    @Test
    fun aCoverIsFetchedOnceThenServedFromTheCache() {
        val hits = AtomicInteger()
        val body = ByteArray(1000) { it.toByte() }
        val url = serve { hits.incrementAndGet(); 200 to body }
        val root = temporaryFolder.newFolder()

        val first = CarArtwork.cached(root, url, OkHttpClient())
        val second = CarArtwork.cached(root, url, OkHttpClient())
        assertArrayEquals(body, first.readBytes())
        assertEquals(first, second)
        assertEquals(1, hits.get())
        // Nothing half-written left behind.
        assertEquals(listOf(first.name), first.parentFile!!.list()!!.toList())
    }

    @Test(expected = FileNotFoundException::class)
    fun aFailedFetchWithNothingCachedIsNotFound() {
        val url = serve { 404 to ByteArray(0) }
        CarArtwork.cached(temporaryFolder.newFolder(), url, OkHttpClient())
    }

    @Test
    fun anOldCopyStillServesWhenTheRefetchFails() {
        val hits = AtomicInteger()
        var status = 200
        val url = serve { hits.incrementAndGet(); status to byteArrayOf(1, 2, 3) }
        val root = temporaryFolder.newFolder()
        val file = CarArtwork.cached(root, url, OkHttpClient())
        val weekOld = System.currentTimeMillis() - 8L * 24 * 3600 * 1000
        file.setLastModified(weekOld)
        status = 500
        assertArrayEquals(byteArrayOf(1, 2, 3), CarArtwork.cached(root, url, OkHttpClient()).readBytes())
        assertEquals("a week-old copy is refetched", 2, hits.get())
    }

    @Test
    fun anEmptyRefetchDoesNotReplaceAnOldCopy() {
        val hits = AtomicInteger()
        var body = byteArrayOf(4, 5, 6)
        val url = serve { hits.incrementAndGet(); 200 to body }
        val root = temporaryFolder.newFolder()
        val file = CarArtwork.cached(root, url, OkHttpClient())
        file.setLastModified(System.currentTimeMillis() - 8L * 24 * 3600 * 1000)
        body = ByteArray(0)
        assertArrayEquals(byteArrayOf(4, 5, 6), CarArtwork.cached(root, url, OkHttpClient()).readBytes())
        assertEquals("the empty answer was fetched, and refused", 2, hits.get())
    }

    @Test
    fun requestsRightAfterAFailureFailWithoutFetchingAgain() {
        val hits = AtomicInteger()
        val url = serve { hits.incrementAndGet(); 503 to ByteArray(0) }
        val root = temporaryFolder.newFolder()
        repeat(3) {
            try {
                CarArtwork.cached(root, url, OkHttpClient())
            } catch (_: FileNotFoundException) {
            }
        }
        assertEquals(1, hits.get())
    }

    @Test(expected = FileNotFoundException::class)
    fun anEmptyCoverIsNotStored() {
        val url = serve { 200 to ByteArray(0) }
        CarArtwork.cached(temporaryFolder.newFolder(), url, OkHttpClient())
    }

    @Test(expected = IOException::class)
    fun anOversizedCoverIsRefused() {
        CarArtwork.copyCapped(ByteArrayInputStream(ByteArray(11)), ByteArrayOutputStream(), maxBytes = 10)
    }

    private fun serve(respond: () -> Pair<Int, ByteArray>): String {
        val s = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        s.createContext("/") { exchange ->
            val (status, bytes) = respond()
            exchange.sendResponseHeaders(status, if (bytes.isEmpty()) -1 else bytes.size.toLong())
            exchange.responseBody.use { it.write(bytes) }
        }
        s.start()
        server = s
        return "http://127.0.0.1:${s.address.port}/art.png"
    }

    private fun item(id: String, art: String?) = MediaItem.Builder()
        .setMediaId(id)
        .setMediaMetadata(MediaMetadata.Builder().setTitle(id).setArtworkUri(art?.toUri()).build())
        .build()
}
