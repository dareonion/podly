package com.podly.ui.radio

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.podly.AppGraph
import com.podly.data.db.PodcastEntity
import com.podly.radio.RadioProfile
import com.podly.radio.RadioProfiles
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

data class RadioCardState(
    val profile: RadioProfile = RadioProfiles.YOU,
    val profiles: List<RadioProfile> = RadioProfiles.ALL,
    val isPlaying: Boolean = false,
    val nowPlayingTitle: String? = null,
    val nowPlayingShow: String? = null,
    val backlogCount: Int = 0,
    val poolCount: Int = 0,
    val selectedShows: Set<String> = emptySet(),
) {
    /** True when the session belongs to the profile the card is showing. */
    val isActive: Boolean get() = isPlaying

    /** Non-null when radio cannot start, and why. */
    val emptyReason: String?
        get() = when {
            profile.restrictBacklogToSelectedShows && selectedShows.isEmpty() && poolCount == 0 ->
                "No shows chosen for ${profile.displayName} yet."
            backlogCount == 0 && poolCount == 0 ->
                "Everything has been played or was skipped recently."
            else -> null
        }
}

class RadioViewModel(private val graph: AppGraph) : ViewModel() {

    @OptIn(ExperimentalCoroutinesApi::class)
    val state: StateFlow<RadioCardState> =
        graph.radioProfiles.activeProfileId.flatMapLatest { profileId ->
            val profile = RadioProfiles.byId(profileId)
            combine(
                graph.radio.backlogCount(profile.id),
                graph.radio.poolCount(profile.id),
                graph.radioProfiles.selectedShows(profile.id),
                graph.radioSession.active,
                graph.player.state,
            ) { backlog, pool, shows, session, player ->
                RadioCardState(
                    profile = profile,
                    isPlaying = session != null && player.radioProfileId != null,
                    nowPlayingTitle = player.title,
                    nowPlayingShow = player.podcastTitle,
                    backlogCount = backlog,
                    poolCount = pool,
                    selectedShows = shows,
                )
            }
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), RadioCardState())

    val subscribedShows: StateFlow<List<PodcastEntity>> = graph.podcasts.subscribedPodcasts()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    fun selectProfile(profileId: String) = viewModelScope.launch {
        graph.radioProfiles.setActiveProfileId(profileId)
    }

    fun start() {
        graph.player.startRadio(state.value.profile.id)
    }

    fun next() = graph.player.skipRadio()

    fun stop() = graph.player.stopRadio()

    fun setSelectedShows(profileId: String, podcastIds: Set<String>) = viewModelScope.launch {
        graph.radioProfiles.setSelectedShows(profileId, podcastIds)
    }

    /** Escape hatch: a cooldown that silently empties the pool reads as a bug. */
    fun clearCooldowns() = viewModelScope.launch {
        graph.radio.clearCooldowns(state.value.profile.id)
    }
}
