package com.dodoznq.helora.presentation.viewmodel

import androidx.lifecycle.ViewModel
import com.dodoznq.helora.data.jellyfin.JellyfinRepository
import com.dodoznq.helora.data.navidrome.NavidromeRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.StateFlow

/**
 * Exposes only the login state of Navidrome and Jellyfin for Home's dashboard entry tiles.
 *
 * Home used to obtain the full NavidromeDashboardViewModel and JellyfinDashboardViewModel just
 * to read isLoggedIn. Both of those run sync work in their init block on every creation
 * (WorkManager enqueue, network calls) regardless of login state, and the actual dashboard
 * screens already create their own separate instance when opened. This ViewModel reads the
 * same underlying repositories with no side effects.
 */
@HiltViewModel
class HomeIntegrationsViewModel @Inject constructor(
    navidromeRepository: NavidromeRepository,
    jellyfinRepository: JellyfinRepository
) : ViewModel() {
    val isNavidromeLoggedIn: StateFlow<Boolean> = navidromeRepository.isLoggedInFlow
    val isJellyfinLoggedIn: StateFlow<Boolean> = jellyfinRepository.isLoggedInFlow
}
