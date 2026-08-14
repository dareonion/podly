package com.podly.ui.player

import android.view.ContextThemeWrapper
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import androidx.mediarouter.app.MediaRouteButton
import com.google.android.gms.cast.framework.CastButtonFactory
import com.google.android.gms.common.ConnectionResult
import com.google.android.gms.common.GoogleApiAvailability
import com.podly.R

/**
 * The standard Cast icon and device picker.
 *
 * [MediaRouteButton] hides itself while no Cast receiver is on the network, so
 * this occupies no visible space until there is somewhere to cast to. It is an
 * AppCompat view and cannot inflate against Theme.Podly (a framework Material
 * theme), hence the ContextThemeWrapper.
 */
@Composable
fun CastButton(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val playServicesAvailable = remember(context) {
        GoogleApiAvailability.getInstance()
            .isGooglePlayServicesAvailable(context) == ConnectionResult.SUCCESS
    }
    if (!playServicesAvailable) return

    AndroidView(
        modifier = modifier,
        factory = { ctx ->
            MediaRouteButton(ContextThemeWrapper(ctx, R.style.Theme_Podly_Cast)).also { button ->
                CastButtonFactory.setUpMediaRouteButton(ctx.applicationContext, button)
            }
        },
    )
}
