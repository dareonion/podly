package com.podly.ui

import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow

/**
 * App-wide one-shot user-visible messages (mostly failures from fire-and-forget
 * actions), shown as snackbars by MainActivity.
 */
class UiMessages {
    private val _messages = MutableSharedFlow<String>(extraBufferCapacity = 16)
    val messages: SharedFlow<String> = _messages

    fun post(message: String) {
        _messages.tryEmit(message)
    }
}
