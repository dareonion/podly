package com.podly.ui

import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.podly.AppGraph
import com.podly.appGraph

/** Creates a ViewModel wired to the app's dependency graph. */
@Composable
inline fun <reified VM : ViewModel> appViewModel(
    key: String? = null,
    crossinline create: (AppGraph) -> VM,
): VM {
    val graph = LocalContext.current.appGraph
    return viewModel(
        key = key,
        factory = viewModelFactory { initializer { create(graph) } },
    )
}
