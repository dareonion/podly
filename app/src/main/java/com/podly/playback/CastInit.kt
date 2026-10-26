package com.podly.playback

import android.content.Context
import android.util.Log
import androidx.annotation.MainThread
import androidx.annotation.OptIn
import androidx.media3.cast.Cast
import androidx.media3.common.util.UnstableApi
import com.google.android.gms.common.ConnectionResult
import com.google.android.gms.common.GoogleApiAvailability

private const val TAG = "PodlyCast"

/**
 * Starts loading the Cast SDK through Media3's [Cast] singleton, once per
 * process, and returns it; null when Play Services can't provide Cast.
 *
 * [PlaybackService] (for its RemoteCastPlayer) and the cast button both come
 * through here, so they share one load and fail together. Media3 remembers a
 * failed load for the life of the process; a button that set itself up some
 * other way could then connect a receiver while no Cast player exists, and the
 * audio would stay on the phone.
 *
 * Must run on the main thread — off it the Cast SDK throws and casting
 * silently never initialises. The load itself (a Play Services Dynamite
 * module, ~700ms observed) runs on a background executor. The manifest's
 * [CastOptionsProvider] still supplies the options: the Cast SDK prefers it
 * over the default provider Media3 passes in. A failed load is logged by
 * Media3 under the "Cast" tag.
 */
@MainThread
@OptIn(UnstableApi::class)
internal fun initCast(context: Context): Cast? {
    val available = runCatching {
        GoogleApiAvailability.getInstance()
            .isGooglePlayServicesAvailable(context) == ConnectionResult.SUCCESS
    }.getOrDefault(false)
    if (!available) return null
    return runCatching {
        Cast.getSingletonInstance(context.applicationContext).also { cast ->
            if (cast.needsInitialization()) cast.initialize()
        }
    }.onFailure { Log.w(TAG, "Cast unavailable; playback stays local", it) }.getOrNull()
}
