package com.podly.ui.player

import android.util.Log
import android.view.View
import androidx.annotation.OptIn
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.media3.cast.MediaRouteButtonFactory
import androidx.media3.common.util.UnstableApi
import androidx.mediarouter.app.MediaRouteButton
import com.google.common.util.concurrent.FutureCallback
import com.google.common.util.concurrent.Futures
import com.podly.playback.initCast

private const val TAG = "PodlyCast"

/**
 * The standard Cast icon and device picker.
 *
 * Set up through Media3's [MediaRouteButtonFactory], which waits for the Cast
 * load [initCast] started instead of blocking the main thread on it, and fails
 * whenever the Cast player can't exist either. Until the load answers, the
 * button holds its space but stays hidden: without a route selector it would
 * open an empty picker. Both the button and the picker dialog it opens need
 * Theme.Podly to be AppCompat-based (see themes.xml) and the host Activity to
 * be a FragmentActivity.
 */
@OptIn(UnstableApi::class)
@Composable
fun CastButton(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val castAvailable = remember(context) { initCast(context) != null }
    if (!castAvailable) return

    AndroidView(
        modifier = modifier,
        factory = { ctx ->
            MediaRouteButton(ctx).also { button ->
                button.visibility = View.INVISIBLE
                Futures.addCallback(
                    MediaRouteButtonFactory.setUpMediaRouteButton(ctx, button),
                    object : FutureCallback<Void?> {
                        override fun onSuccess(result: Void?) {
                            button.visibility = View.VISIBLE
                        }

                        override fun onFailure(t: Throwable) {
                            Log.w(TAG, "Cast button unavailable", t)
                            button.visibility = View.GONE
                        }
                    },
                    ContextCompat.getMainExecutor(ctx),
                )
            }
        },
    )
}
