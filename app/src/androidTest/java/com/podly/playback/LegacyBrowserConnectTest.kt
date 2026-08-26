package com.podly.playback

import android.content.ComponentName
import android.content.Context
import android.media.browse.MediaBrowser
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

        val children = CountDownLatch(1)
        val loaded = AtomicReference<List<MediaBrowser.MediaItem>?>(null)
        instrumentation.runOnMainSync {
            browser.get().subscribe(
                root,
                object : MediaBrowser.SubscriptionCallback() {
                    override fun onChildrenLoaded(parentId: String, items: MutableList<MediaBrowser.MediaItem>) {
                        loaded.set(items.toList())
                        children.countDown()
                    }
                    override fun onError(parentId: String) = children.countDown()
                },
            )
        }
        assertTrue("root children did not load within 10 s", children.await(10, TimeUnit.SECONDS))
        val items = loaded.get()
        assertNotNull("onLoadChildren reported an error", items)
        assertEquals(
            listOf(MediaIds.NODE_CONTINUE, MediaIds.NODE_PLAYLISTS, MediaIds.NODE_LIBRARY, MediaIds.NODE_DOWNLOADS),
            items!!.map { it.mediaId },
        )

        instrumentation.runOnMainSync { browser.get().disconnect() }
    }
}
