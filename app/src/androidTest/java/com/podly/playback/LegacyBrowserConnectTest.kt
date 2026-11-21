package com.podly.playback

import android.annotation.SuppressLint
import android.content.ComponentName
import android.content.Context
import android.media.browse.MediaBrowser
import android.os.Bundle
import androidx.media3.session.legacy.MediaBrowserCompat
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Connects to [PlaybackService] the way Android Auto, Bluetooth AVRCP and the
 * system's media-resumption code do: through the platform's legacy
 * [MediaBrowser], not a Media3 controller.
 *
 * Media3's legacy stub answers that handshake by blocking the main thread in
 * `onGetRoot` until the app's `onGetLibraryRoot` future completes, and it
 * observes completion through a callback posted back to the main thread — so a
 * future that finishes on any other thread never gets to open the latch and the
 * process ANRs (`executing service PlaybackService, waited 200003ms`). This
 * test fails in ten seconds instead.
 */
@RunWith(AndroidJUnit4::class)
class LegacyBrowserConnectTest {

    @Test
    fun legacyBrowserConnectsAndLoadsRootChildren() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext.applicationContext
        val connected = CountDownLatch(1)
        val failure = AtomicReference<String?>(null)
        val browser = AtomicReference<MediaBrowser>()

        instrumentation.runOnMainSync {
            val b = MediaBrowser(
                context,
                ComponentName(context, PlaybackService::class.java),
                object : MediaBrowser.ConnectionCallback() {
                    override fun onConnected() = connected.countDown()
                    override fun onConnectionFailed() {
                        failure.set("onConnectionFailed")
                        connected.countDown()
                    }
                    override fun onConnectionSuspended() {
                        failure.set("onConnectionSuspended")
                        connected.countDown()
                    }
                },
                null,
            )
            browser.set(b)
            b.connect()
        }

        assertTrue(
            "legacy MediaBrowser did not connect within 10 s — main thread deadlocked in onGetRoot?",
            connected.await(10, TimeUnit.SECONDS),
        )
        assertEquals(null, failure.get())
        val root = browser.get().root
        assertEquals(MediaIds.ROOT, root)

        val rootItems = load(browser.get(), root)
        // This week appears only once a digest has synced, so it depends on the phone's data.
        assertEquals(
            listOf(MediaIds.NODE_CONTINUE, MediaIds.NODE_PODCASTS, MediaIds.NODE_LIBRARY),
            rootItems.map { it.mediaId }.filter { it != MediaIds.NODE_WEEKLY },
        )
        // Android Auto shows four root tabs at most.
        assertTrue(rootItems.size <= 4)
        assertTrue(rootItems.all { it.isBrowsable })

        // A show and its episodes, through the same legacy conversion Android Auto gets.
        val shows = load(browser.get(), MediaIds.NODE_PODCASTS)
        assertTrue(shows.all { it.isBrowsable && !it.isPlayable && it.mediaId?.startsWith(MediaIds.PODCAST_PREFIX) == true })
        // Reads the phone's own library, so a show can legitimately be empty (a feed
        // that never refreshed); some show among the first few should not be.
        val opened = shows.take(5).map { show ->
            load(browser.get(), show.mediaId!!).also { episodes ->
                // show-ep/ ids, which play on through the show.
                assertTrue(episodes.all { it.isPlayable && it.mediaId?.let(MediaIds::showEpisodeIdOrNull) != null })
            }
        }
        assertTrue("no show among the first five has episodes", shows.isEmpty() || opened.any { it.isNotEmpty() })

        instrumentation.runOnMainSync { browser.get().disconnect() }

        // Searching for a show's own title finds it, through the legacy search handshake
        // Android Auto uses: results arrive only once the service notifies for that query.
        shows.firstOrNull()?.let { show ->
            val title = show.description.title.toString()
            assertTrue(
                "searching \"$title\" did not find it",
                legacySearch(title).any { it.mediaId == show.mediaId },
            )
        }
    }

    /**
     * A search through the support-library browser protocol. The platform MediaBrowser
     * has no search; Media3's copy of the compat browser is library-internal, but it
     * speaks the same protocol Android Auto does.
     */
    @SuppressLint("RestrictedApi")
    private fun legacySearch(query: String): List<MediaBrowserCompat.MediaItem> {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext.applicationContext
        val connected = CountDownLatch(1)
        val browser = AtomicReference<MediaBrowserCompat>()
        instrumentation.runOnMainSync {
            val b = MediaBrowserCompat(
                context,
                ComponentName(context, PlaybackService::class.java),
                object : MediaBrowserCompat.ConnectionCallback() {
                    override fun onConnected() = connected.countDown()
                    override fun onConnectionFailed() = connected.countDown()
                },
                null,
            )
            browser.set(b)
            b.connect()
        }
        assertTrue("compat browser did not connect within 10 s", connected.await(10, TimeUnit.SECONDS))
        assertTrue("compat browser failed to connect", browser.get().isConnected)

        val done = CountDownLatch(1)
        val found = AtomicReference<List<MediaBrowserCompat.MediaItem>?>(null)
        instrumentation.runOnMainSync {
            browser.get().search(
                query,
                Bundle.EMPTY,
                object : MediaBrowserCompat.SearchCallback() {
                    override fun onSearchResult(query: String, extras: Bundle?, items: List<MediaBrowserCompat.MediaItem>) {
                        found.set(items.toList())
                        done.countDown()
                    }
                    override fun onError(query: String, extras: Bundle?) = done.countDown()
                },
            )
        }
        assertTrue("search for \"$query\" did not answer within 10 s", done.await(10, TimeUnit.SECONDS))
        instrumentation.runOnMainSync { browser.get().disconnect() }
        val items = found.get()
        assertNotNull("search for \"$query\" reported an error", items)
        return items!!
    }

    private fun load(browser: MediaBrowser, parentId: String): List<MediaBrowser.MediaItem> {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val done = CountDownLatch(1)
        val loaded = AtomicReference<List<MediaBrowser.MediaItem>?>(null)
        instrumentation.runOnMainSync {
            browser.subscribe(
                parentId,
                object : MediaBrowser.SubscriptionCallback() {
                    override fun onChildrenLoaded(parentId: String, items: MutableList<MediaBrowser.MediaItem>) {
                        loaded.set(items.toList())
                        done.countDown()
                    }
                    override fun onError(parentId: String) = done.countDown()
                },
            )
        }
        assertTrue("$parentId children did not load within 10 s", done.await(10, TimeUnit.SECONDS))
        instrumentation.runOnMainSync { browser.unsubscribe(parentId) }
        val items = loaded.get()
        assertNotNull("onLoadChildren reported an error for $parentId", items)
        return items!!
    }
}
