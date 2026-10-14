package com.podly.ui.playlists

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.PlaylistAdd
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import com.podly.AppGraph
import com.podly.data.PicksImportFile
import com.podly.data.PicksImportResult
import com.podly.network.Http
import com.podly.ui.appViewModel
import com.podly.ui.util.formatDate
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Picks JSON → playlist, from a file or pasted straight from a chatbot. */
class PicksImportViewModel(private val graph: AppGraph) : ViewModel() {

    suspend fun importPicks(text: String): PicksImportResult =
        graph.picksImporter.import(
            json = text,
            fallbackName = "Imported picks · ${formatDate(System.currentTimeMillis())}",
        )

    /** One previously pasted picks JSON, reloadable into the paste box. */
    data class PasteEntry(val fileName: String, val label: String)

    private val pasteDir get() = File(graph.appContext.filesDir, "picks_pastes")

    suspend fun pasteHistory(): List<PasteEntry> = withContext(Dispatchers.IO) {
        pasteDir.listFiles().orEmpty()
            .sortedByDescending { it.name }
            .map { file ->
                val date = file.name.removeSuffix(".json").toLongOrNull()?.let { formatDate(it) }
                val name = runCatching {
                    Http.json.decodeFromString<PicksImportFile>(file.readText()).name
                }.getOrNull()?.takeIf { it.isNotBlank() }
                PasteEntry(file.name, listOfNotNull(name ?: "Pasted picks", date).joinToString(" · "))
            }
    }

    suspend fun readPaste(fileName: String): String = withContext(Dispatchers.IO) {
        File(pasteDir, fileName).readText()
    }

    /** Records a paste before import (kept even if the import fails), pruned to the newest [MAX_PASTES]. */
    suspend fun savePaste(text: String): Unit = withContext(Dispatchers.IO) {
        pasteDir.mkdirs()
        File(pasteDir, "${System.currentTimeMillis()}.json").writeText(text)
        pasteDir.listFiles().orEmpty()
            .sortedByDescending { it.name }
            .drop(MAX_PASTES)
            .forEach { it.delete() }
    }

    private companion object {
        const val MAX_PASTES = 20
    }
}

/**
 * The Playlists page's Import action. Lived in Settings until 2026-10; it belongs
 * beside the playlists it creates. [onStatus] receives progress and the result,
 * which can run to several lines when picks are skipped.
 */
@Composable
fun PicksImportButton(onStatus: (String) -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val viewModel = appViewModel { PicksImportViewModel(it) }
    var menuOpen by remember { mutableStateOf(false) }
    var pasting by remember { mutableStateOf(false) }
    var pasted by remember { mutableStateOf("") }
    var history by remember { mutableStateOf<List<PicksImportViewModel.PasteEntry>>(emptyList()) }

    // Shared by the file-import and paste paths; readText runs on IO.
    fun runImport(readText: suspend () -> String) {
        scope.launch {
            onStatus("Importing picks...")
            runCatching {
                val text = withContext(Dispatchers.IO) { readText() }
                withContext(Dispatchers.IO) { viewModel.importPicks(text) }
            }.onSuccess { result ->
                onStatus(
                    buildString {
                        append("Created \"${result.name}\": ${result.saved} of ${result.total} picks matched")
                        if (result.missed.isNotEmpty()) {
                            val shown = result.missed.take(10)
                            append("\nSkipped: ${shown.joinToString("; ")}")
                            if (result.missed.size > shown.size) {
                                append(" (+${result.missed.size - shown.size} more)")
                            }
                        }
                    },
                )
            }.onFailure {
                onStatus("Import failed: ${it.message ?: "unknown error"}")
            }
        }
    }

    val fileLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri == null) {
            onStatus("Import canceled")
            return@rememberLauncherForActivityResult
        }
        runImport {
            context.contentResolver.openInputStream(uri)?.bufferedReader()?.use { it.readText() }
                ?: error("Could not open picks file")
        }
    }

    TextButton(onClick = { menuOpen = true }) {
        Icon(Icons.AutoMirrored.Filled.PlaylistAdd, null)
        Text(" Import picks")
        DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
            DropdownMenuItem(
                text = { Text("From a JSON file") },
                onClick = {
                    menuOpen = false
                    fileLauncher.launch(arrayOf("application/json", "text/*", "*/*"))
                },
            )
            DropdownMenuItem(
                text = { Text("Paste JSON") },
                onClick = {
                    menuOpen = false
                    pasted = "" // fresh box each open; earlier pastes live in the history below
                    pasting = true
                    scope.launch { history = viewModel.pasteHistory() }
                },
            )
        }
    }

    if (pasting) {
        AlertDialog(
            onDismissRequest = { pasting = false },
            title = { Text("Paste picks JSON") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(
                        "Each pick is matched against its show's feed; picks that can't be " +
                            "found are skipped.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    OutlinedTextField(
                        value = pasted,
                        onValueChange = { pasted = it },
                        label = { Text("Picks JSON") },
                        minLines = 6,
                        maxLines = 10,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    if (history.isNotEmpty()) {
                        Text(
                            "Previous pastes",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        history.take(5).forEach { entry ->
                            TextButton(onClick = {
                                scope.launch { pasted = viewModel.readPaste(entry.fileName) }
                            }) {
                                Text(entry.label, maxLines = 1)
                            }
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(
                    enabled = pasted.isNotBlank(),
                    onClick = {
                        pasting = false
                        val text = pasted
                        runImport {
                            viewModel.savePaste(text)
                            text
                        }
                    },
                ) { Text("Import") }
            },
            dismissButton = {
                TextButton(onClick = { pasting = false }) { Text("Cancel") }
            },
        )
    }
}
