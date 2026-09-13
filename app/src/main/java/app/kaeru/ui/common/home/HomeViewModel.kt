package app.kaeru.ui.common.home

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.kaeru.domain.discover.Season
import app.kaeru.domain.feed.HomeFeedBuilder
import app.kaeru.domain.model.Anime
import app.kaeru.domain.model.HomeFeed
import app.kaeru.domain.playback.PlaybackPreferences
import app.kaeru.domain.repository.DiscoverRepository
import app.kaeru.domain.repository.LibraryRepository
import app.kaeru.ui.common.errorMessageOrNull
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.time.Clock
import java.time.ZoneId
import javax.inject.Inject

private data class RefreshState(val active: Boolean = false, val error: String? = null)

/**
 * The discovery rows as the view model keeps them.
 *
 * Seasons are held in a map rather than one selected list so that a chip the viewer has already
 * visited comes back with no wait at all: the repository would answer from its own cache in a
 * millisecond, but a millisecond is still a frame where the row is empty, and a switcher that
 * blinks on every press feels broken.
 */
private data class DiscoverState(
    val season: Season,
    val popularNow: List<Anime>? = null,
    val loadingNow: Boolean = true,
    val seasonal: Map<Season, List<Anime>> = emptyMap(),
    val loading: Set<Season> = emptySet(),
) {
    fun toUiState() = DiscoverUiState(
        season = season,
        popularNow = popularNow,
        seasonal = seasonal[season],
        loadingNow = loadingNow,
        loadingSeasonal = season in loading,
    )
}

@HiltViewModel
class HomeViewModel @Inject constructor(
    private val repository: LibraryRepository,
    private val discover: DiscoverRepository,
    private val feedBuilder: HomeFeedBuilder,
    private val clock: Clock,
    prefs: PlaybackPreferences,
) : ViewModel() {
    private val refreshState = MutableStateFlow(RefreshState())
    private var refreshJob: Job? = null

    // The zone is the device's rather than the clock's: a season is which quarter the viewer is
    // living in, and on four evenings a year that differs from the one in UTC.
    private val openedIn: Season = Season.current(clock.instant(), ZoneId.systemDefault())

    // Both rows start out loading, so the first frame is the shape of the screen rather than a gap.
    private val discoverState = MutableStateFlow(DiscoverState(season = openedIn, loading = setOf(openedIn)))
    private var nowJob: Job? = null
    private val seasonJobs = mutableMapOf<Season, Job>()

    val uiState: StateFlow<HomeUiState> = combine(
        repository.observeLibrary(),
        refreshState,
        prefs.watchedThreshold,
        discoverState,
    ) { entries, refresh, threshold, discovered ->
        HomeUiState(
            feed = feedBuilder.build(entries, clock.instant()),
            isLoading = false,
            isRefreshing = refresh.active,
            errorMessage = refresh.error,
            watchedThreshold = threshold,
            discover = discovered.toUiState(),
        )
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.Eagerly,
        initialValue = HomeUiState(feed = HomeFeed.EMPTY, discover = discoverState.value.toUiState()),
    )

    init {
        syncLibrary()
        // Not forced: reopening the home screen should cost the catalogue nothing for six hours.
        loadPopularNow(force = false)
        loadSeason(openedIn, force = false)
    }

    /**
     * The viewer asked for all of this again, so every row goes past its cache.
     *
     * The seasons they are not looking at are forgotten, so a chip pressed after a pull shows what
     * the pull was for rather than what was on screen before it. The one they *are* looking at is
     * kept until its replacement lands: taking the row away for the length of a request would
     * answer a request for fresher titles by showing none.
     */
    fun refresh() {
        syncLibrary()
        loadPopularNow(force = true)
        val showing = discoverState.value.season
        discoverState.update { state -> state.copy(seasonal = state.seasonal.filterKeys { it == showing }) }
        loadSeason(showing, force = true)
    }

    /** A chip was pressed. A season already read is shown at once; a new one is fetched. */
    fun selectSeason(season: Season) {
        if (season == discoverState.value.season) return
        discoverState.update { it.copy(season = season) }
        loadSeason(season, force = false)
    }

    private fun syncLibrary() {
        if (refreshJob?.isActive == true) return
        refreshJob = viewModelScope.launch {
            refreshState.value = RefreshState(active = true)
            val result = repository.refresh()
            refreshState.value = RefreshState(active = false, error = result.errorMessageOrNull())
        }
    }

    private fun loadPopularNow(force: Boolean) {
        if (!force && nowJob?.isActive == true) return
        nowJob?.cancel()
        nowJob = viewModelScope.launch {
            discoverState.update { it.copy(loadingNow = true) }
            // A failure leaves the row absent. It is not this screen's error to report: the rows
            // above answer the question the viewer opened the app with, and a banner about the
            // catalogue would be about something they did not ask for.
            val titles = discover.popularNow(force).getOrNull()
            discoverState.update { it.copy(popularNow = titles, loadingNow = false) }
        }
    }

    private fun loadSeason(season: Season, force: Boolean) {
        val state = discoverState.value
        if (!force && (season in state.seasonal || seasonJobs[season]?.isActive == true)) return
        seasonJobs[season]?.cancel()
        seasonJobs[season] = viewModelScope.launch {
            discoverState.update { it.copy(loading = it.loading + season) }
            val titles = discover.seasonal(season, force).getOrNull()
            discoverState.update {
                it.copy(
                    // A season that could not be read is left out rather than remembered empty,
                    // so pressing its chip again is a retry.
                    seasonal = if (titles != null) it.seasonal + (season to titles) else it.seasonal - season,
                    loading = it.loading - season,
                )
            }
        }
    }
}
