package com.podly.ui.playlists

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.podly.data.chat.ChatImporter
import com.podly.ui.UiMessages
import com.podly.ui.util.formatDate
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

/**
 * Runs chat imports in the app's scope: one is dozens of page and feed fetches, and
 * leaving the Playlists page (or switching back to ChatGPT) mustn't cancel it
 * halfway. Its progress and result outlive the screen too.
 */
class ChatImportRunner(
    private val importer: ChatImporter,
    private val scope: CoroutineScope,
    private val messages: UiMessages,
) {
    private val _status = MutableStateFlow<String?>(null)
    /** Progress, then the result; shown on the Playlists page. */
    val status: StateFlow<String?> = _status

    private val _running = MutableStateFlow(false)
    val running: StateFlow<Boolean> = _running

    private val _shared = MutableStateFlow<String?>(null)
    /** Text shared to Podly from another app, waiting for the import dialog to pick it up. */
    val shared: StateFlow<String?> = _shared

    fun offerShared(text: String) {
        _shared.value = text
    }

    fun takeShared(): String? = _shared.value.also { _shared.value = null }

    fun start(input: String) {
        if (_running.value) return
        _running.value = true
        scope.launch {
            try {
                val result = importer.import(input, formatDate(System.currentTimeMillis()).orEmpty()) { _status.value = it }
                _status.value = describe(result)
                messages.post("Created \"${result.name}\"")
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _status.value = "Chat import failed: ${e.message ?: "unknown error"}"
            } finally {
                _running.value = false
            }
        }
    }

    private fun describe(result: ChatImporter.Result) = buildString {
        append("Created \"${result.name}\": ${result.episodes} episodes")
        if (result.shows > 0) append(" and ${result.shows} shows")
        if (result.missed.isNotEmpty()) {
            val shown = result.missed.take(10)
            append("\nNot found: ${shown.joinToString("; ")}")
            if (result.missed.size > shown.size) append(" (+${result.missed.size - shown.size} more)")
        }
    }
}

/** Where a ChatGPT link (or a chat's text) is pasted, or lands when shared to Podly. */
@Composable
fun ChatImportDialog(
    text: String,
    running: Boolean,
    onTextChange: (String) -> Unit,
    onImport: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Import a ChatGPT chat") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(
                    "Paste the chat's share link (in ChatGPT: Share, then Copy link), or share the " +
                        "chat to Podly. Its episodes become a playlist, each with the chat's reason, and " +
                        "the shows it recommends are listed with it.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                OutlinedTextField(
                    value = text,
                    onValueChange = onTextChange,
                    label = { Text("Share link, or the chat's text") },
                    minLines = 2,
                    maxLines = 8,
                    modifier = Modifier.fillMaxWidth(),
                )
                if (running) {
                    Text(
                        "Another chat is still importing; this one can start when it finishes.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            }
        },
        confirmButton = {
            TextButton(enabled = text.isNotBlank() && !running, onClick = onImport) { Text("Import") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}
