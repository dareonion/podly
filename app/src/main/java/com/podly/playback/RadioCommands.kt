package com.podly.playback

import android.os.Bundle
import androidx.media3.session.SessionCommand

/**
 * Session commands for radio. The UI, the notification and (later) Android Auto all
 * drive radio through these rather than through player commands, because starting or
 * skipping a station is a session-level action, not a transport one.
 */
object RadioCommands {
    const val ACTION_START = "com.podly.radio.START"
    const val ACTION_SKIP = "com.podly.radio.SKIP"
    const val ACTION_STOP = "com.podly.radio.STOP"
    const val EXTRA_PROFILE_ID = "com.podly.radio.PROFILE_ID"

    val START = SessionCommand(ACTION_START, Bundle.EMPTY)
    val SKIP = SessionCommand(ACTION_SKIP, Bundle.EMPTY)
    val STOP = SessionCommand(ACTION_STOP, Bundle.EMPTY)
}
