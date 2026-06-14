package com.podly.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.StarBorder
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue

@Composable
fun EpisodeNoteDialog(
    title: String,
    initialNote: String?,
    initialRating: Int?,
    onSave: (note: String?, rating: Int?) -> Unit,
    onDismiss: () -> Unit,
) {
    var note by remember(title, initialNote) { mutableStateOf(initialNote.orEmpty()) }
    var rating by remember(title, initialRating) { mutableStateOf(initialRating?.coerceIn(1, 5)) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            androidx.compose.foundation.layout.Column {
                Text("Rating")
                Row(horizontalArrangement = Arrangement.Start) {
                    (1..5).forEach { value ->
                        IconButton(onClick = { rating = if (rating == value) null else value }) {
                            Icon(
                                if ((rating ?: 0) >= value) Icons.Filled.Star else Icons.Filled.StarBorder,
                                "$value star rating",
                            )
                        }
                    }
                }
                OutlinedTextField(
                    value = note,
                    onValueChange = { note = it },
                    label = { Text("Quick note") },
                    minLines = 3,
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    onSave(note.trim().takeIf { it.isNotEmpty() }, rating)
                },
            ) {
                Text("Save")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancel")
            }
        },
    )
}
