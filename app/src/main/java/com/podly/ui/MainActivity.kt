package com.podly.ui

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.QueueMusic
import androidx.compose.material.icons.filled.Explore
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.LibraryMusic
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import com.podly.appGraph
import androidx.core.content.ContextCompat
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.podly.ui.discover.DiscoverScreen
import com.podly.ui.episode.EpisodeDetailScreen
import com.podly.ui.history.HistoryScreen
import com.podly.ui.library.LibraryScreen
import com.podly.ui.player.MiniPlayer
import com.podly.ui.player.PlayerScreen
import com.podly.ui.playlists.PlaylistDetailScreen
import com.podly.ui.playlists.PlaylistsScreen
import com.podly.ui.podcast.PodcastDetailScreen
import com.podly.ui.settings.SettingsScreen
import com.podly.ui.theme.PodlyTheme

private data class Tab(val route: String, val label: String, val icon: ImageVector)

private val TABS = listOf(
    Tab("library", "Library", Icons.Filled.LibraryMusic),
    Tab("history", "History", Icons.Filled.History),
    Tab("discover", "Discover", Icons.Filled.Explore),
    Tab("playlists", "Playlists", Icons.AutoMirrored.Filled.QueueMusic),
    Tab("settings", "Settings", Icons.Filled.Settings),
)

class MainActivity : ComponentActivity() {

    private val notificationPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) {}

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        requestNotificationPermission()
        setContent {
            PodlyTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    PodlyApp()
                }
            }
        }
    }

    private fun requestNotificationPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
            != PackageManager.PERMISSION_GRANTED
        ) {
            notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }
}

@Composable
private fun PodlyApp() {
    val navController = rememberNavController()
    val backStackEntry by navController.currentBackStackEntryAsState()
    val currentRoute = backStackEntry?.destination?.route

    val snackbarHostState = remember { SnackbarHostState() }
    val messages = LocalContext.current.appGraph.messages
    LaunchedEffect(Unit) {
        messages.messages.collect { snackbarHostState.showSnackbar(it) }
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbarHostState) },
        bottomBar = {
            Column {
                if (currentRoute != "player") {
                    MiniPlayer(onOpenPlayer = { navController.navigate("player") })
                }
                NavigationBar {
                    TABS.forEach { tab ->
                        NavigationBarItem(
                            selected = currentRoute == tab.route,
                            onClick = {
                                navController.navigate(tab.route) {
                                    popUpTo(navController.graph.findStartDestination().id) {
                                        saveState = true
                                    }
                                    launchSingleTop = true
                                    restoreState = true
                                }
                            },
                            icon = { Icon(tab.icon, tab.label) },
                            label = { Text(tab.label) },
                        )
                    }
                }
            }
        },
    ) { innerPadding ->
        NavHost(
            navController = navController,
            startDestination = "library",
            modifier = Modifier.padding(innerPadding),
        ) {
            composable("library") {
                LibraryScreen(
                    onOpenPodcast = { navController.navigate("podcast/$it") },
                    onOpenEpisode = { navController.navigate("episode/$it") },
                )
            }
            composable("discover") {
                DiscoverScreen(
                    onOpenPodcast = { navController.navigate("podcast/$it") },
                    onOpenPlaylist = { navController.navigate("playlist/$it") },
                )
            }
            composable("history") { HistoryScreen() }
            composable("playlists") {
                PlaylistsScreen(onOpenPlaylist = { navController.navigate("playlist/$it") })
            }
            composable("settings") { SettingsScreen() }
            composable("podcast/{podcastId}") { entry ->
                val podcastId = entry.arguments?.getString("podcastId") ?: return@composable
                PodcastDetailScreen(
                    podcastId,
                    onOpenEpisode = { navController.navigate("episode/$it") },
                )
            }
            composable("playlist/{playlistId}") { entry ->
                val playlistId =
                    entry.arguments?.getString("playlistId")?.toLongOrNull() ?: return@composable
                PlaylistDetailScreen(
                    playlistId,
                    onOpenEpisode = { navController.navigate("episode/$it") },
                )
            }
            composable("episode/{episodeId}") { entry ->
                val episodeId = entry.arguments?.getString("episodeId") ?: return@composable
                EpisodeDetailScreen(
                    episodeId,
                    onOpenPodcast = { navController.navigate("podcast/$it") },
                )
            }
            composable("player") { PlayerScreen() }
        }
    }
}
