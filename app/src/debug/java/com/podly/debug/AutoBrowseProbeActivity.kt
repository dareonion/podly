package com.podly.debug

import android.app.Activity
import android.content.ComponentName
import android.media.browse.MediaBrowser
import android.os.Bundle
import android.util.Log
import com.podly.playback.PlaybackService

/**
 * Debug harness that connects to [PlaybackService] as a *legacy* framework
 * [MediaBrowser] — the same interface Android Auto uses — and logs the
 * root -> children handshake. Lets us reproduce the Auto browse flow over adb:
 *
 *   adb shell am start -n com.podly/.debug.AutoBrowseProbeActivity
 *   adb logcat -s PodlyProbe:V PodlyAuto:V
 */
class AutoBrowseProbeActivity : Activity() {

    private lateinit var browser: MediaBrowser

    private val connectionCallback = object : MediaBrowser.ConnectionCallback() {
        override fun onConnected() {
            val root = browser.root
            Log.i(TAG, "onConnected root='$root' sessionToken=${browser.sessionToken}")
            browser.subscribe(root, subscriptionCallback)
        }

        override fun onConnectionSuspended() {
            Log.w(TAG, "onConnectionSuspended")
        }

        override fun onConnectionFailed() {
            Log.e(TAG, "onConnectionFailed")
        }
    }

    private val subscriptionCallback = object : MediaBrowser.SubscriptionCallback() {
        override fun onChildrenLoaded(parentId: String, children: List<MediaBrowser.MediaItem>) {
            Log.i(TAG, "onChildrenLoaded parent='$parentId' count=${children.size}")
            children.take(5).forEach { item ->
                Log.i(
                    TAG,
                    "  child id='${item.mediaId}' title='${item.description.title}' " +
                        "browsable=${item.isBrowsable} playable=${item.isPlayable}",
                )
            }
            if (children.size > 5) Log.i(TAG, "  … and ${children.size - 5} more")
            // Drill exactly one level into the root's browsable folders to exercise the
            // episode-loading DB queries too (guard against deeper recursion / loops).
            if (parentId == ROOT_ID) {
                children.filter { it.isBrowsable }.forEach { browser.subscribe(it.mediaId!!, this) }
            }
        }

        override fun onError(parentId: String) {
            Log.e(TAG, "onError loading children of '$parentId'")
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Log.i(TAG, "connecting MediaBrowser to PlaybackService")
        browser = MediaBrowser(
            this,
            ComponentName(this, PlaybackService::class.java),
            connectionCallback,
            null,
        )
        browser.connect()
    }

    override fun onDestroy() {
        if (::browser.isInitialized) browser.disconnect()
        super.onDestroy()
    }

    private companion object {
        const val TAG = "PodlyProbe"
        const val ROOT_ID = "root"
    }
}
