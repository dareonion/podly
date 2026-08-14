package com.podly.playback

import android.content.Context
import com.google.android.gms.cast.CastMediaControlIntent
import com.google.android.gms.cast.framework.CastOptions
import com.google.android.gms.cast.framework.OptionsProvider
import com.google.android.gms.cast.framework.SessionProvider
import com.google.android.gms.cast.framework.media.CastMediaOptions

/**
 * Cast SDK entry point; instantiated by name from the manifest meta-data.
 *
 * Uses the default media receiver (no registered receiver app), which plays
 * plain audio URLs — all a podcast needs.
 */
class CastOptionsProvider : OptionsProvider {

    override fun getCastOptions(context: Context): CastOptions =
        CastOptions.Builder()
            .setReceiverApplicationId(CastMediaControlIntent.DEFAULT_MEDIA_RECEIVER_APPLICATION_ID)
            .setCastMediaOptions(
                // PlaybackService's own MediaSession owns the notification and
                // lock-screen controls; the Cast SDK's would duplicate them.
                CastMediaOptions.Builder()
                    .setMediaSessionEnabled(false)
                    .setNotificationOptions(null)
                    .build()
            )
            .build()

    override fun getAdditionalSessionProviders(context: Context): List<SessionProvider>? = null
}
