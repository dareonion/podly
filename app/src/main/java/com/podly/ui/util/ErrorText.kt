package com.podly.ui.util

import java.net.SocketTimeoutException
import java.net.UnknownHostException

private const val NO_NETWORK = "No internet connection. Check your network and try again."
private const val TIMED_OUT = "The request timed out. Try again."
private const val GENERIC = "Something went wrong. Try again."

/** Pulls the human sentence out of a provider's JSON error body. */
private val JSON_MESSAGE = Regex("\"message\"\\s*:\\s*\"((?:[^\"\\\\]|\\\\.)*)\"")

/**
 * One short, actionable sentence for a failure.
 *
 * Provider SDKs hand back their raw HTTP body — an Anthropic 400 arrives as a
 * whole JSON blob ending in a request_id — and OkHttp reports a dead network as
 * `UnknownHostException`. Rendering either verbatim puts a wall of machine text
 * on screen with no next step, so unwrap the JSON and name the cases a user can
 * actually act on.
 */
fun friendlyError(t: Throwable): String {
    val chain = generateSequence(t) { it.cause }.take(5).toList()
    chain.forEach { link ->
        when (link) {
            is UnknownHostException -> return NO_NETWORK
            is SocketTimeoutException -> return TIMED_OUT
        }
    }
    return friendlyErrorText(chain.mapNotNull { it.message }.distinct().joinToString(": "))
}

/** The text-only half of [friendlyError], split out so the mapping is JVM-testable. */
fun friendlyErrorText(raw: String): String {
    val unwrapped = (JSON_MESSAGE.find(raw)?.groupValues?.get(1) ?: raw).trim()
    if (unwrapped.isEmpty()) return GENERIC
    // Classify against the whole body, not just the unwrapped sentence: the
    // machine-readable "type" ("rate_limit_error") sits outside the message.
    val lower = "$raw $unwrapped".lowercase()
    return when {
        "unable to resolve host" in lower || "no address associated" in lower -> NO_NETWORK
        "timed out" in lower || "timeout" in lower -> TIMED_OUT
        "credit balance is too low" in lower ->
            "That account is out of API credits. Add credits for the key in Settings, or switch provider."
        "authentication_error" in lower || "invalid x-api-key" in lower || "incorrect api key" in lower ->
            "That API key was rejected. Check the key in Settings."
        "permission_error" in lower ->
            "That API key isn't allowed to use this model. Check the key in Settings."
        "rate_limit" in lower -> "Rate limited by the API. Try again in a minute."
        "overloaded" in lower -> "The service is busy right now. Try again in a moment."
        else -> unwrapped.take(200)
    }
}
