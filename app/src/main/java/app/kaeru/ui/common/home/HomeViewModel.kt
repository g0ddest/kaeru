package app.kaeru.ui.common.home

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.kaeru.domain.feed.HomeFeedBuilder
import app.kaeru.domain.model.HomeFeed
import app.kaeru.domain.playback.PlaybackPreferences
import app.kaeru.domain.repository.LibraryRepository
import app.kaeru.ui.common.errorMessageOrNull
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.time.Clock
import javax.inject.Inject

private data class RefreshState(val active: Boolean = false, val error: String? = null)

@HiltViewModel
class HomeViewModel @Inject constructor(
    private val repository: LibraryRepository,
    private val feedBuilder: HomeFeedBuilder,
    private val clock: Clock,
    prefs: PlaybackPreferences,
) : ViewModel() {
    private val refreshState = MutableStateFlow(RefreshState())
    private var refreshJob: Job? = null

    val uiState: StateFlow<HomeUiState> = combine(
        repository.observeLibrary(),
        refreshState,
        prefs.watchedThreshold,
    ) { entries, refresh, threshold ->
        HomeUiState(
            feed = feedBuilder.build(entries, clock.instant()),
            isLoading = false,
            isRefreshing = refresh.active,
            errorMessage = refresh.error,
            watchedThreshold = threshold,
        )
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.Eagerly,
        initialValue = HomeUiState(feed = HomeFeed.EMPTY),
    )

    init { refresh() }

    fun refresh() {
        if (refreshJob?.isActive == true) return
        refreshJob = viewModelScope.launch {
            refreshState.value = RefreshState(active = true)
            val result = repository.refresh()
            refreshState.value = RefreshState(active = false, error = result.errorMessageOrNull())
        }
    }
}
