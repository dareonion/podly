package com.podly.ui.components

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

/**
 * An inline failure message with a way out of it.
 *
 * Every error surface in the app used to be a bare red paragraph that stayed
 * pinned to the screen until you navigated away, so it needs both actions:
 * [onRetry] to re-run the thing that failed, [onDismiss] to clear it.
 */
@Composable
fun ErrorNotice(
    message: String,
    modifier: Modifier = Modifier,
    onRetry: (() -> Unit)? = null,
    onDismiss: (() -> Unit)? = null,
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 4.dp),
    ) {
        Text(
            message,
            color = MaterialTheme.colorScheme.error,
            style = MaterialTheme.typography.bodyMedium,
        )
        if (onRetry != null || onDismiss != null) {
            Row {
                onRetry?.let { TextButton(onClick = it) { Text("Retry") } }
                onDismiss?.let { TextButton(onClick = it) { Text("Dismiss") } }
            }
        }
    }
}
