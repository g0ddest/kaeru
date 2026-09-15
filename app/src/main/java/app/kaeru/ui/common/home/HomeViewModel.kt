package app.kaeru.ui.common.home

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.kaeru.domain.discover.Season
import app.kaeru.domain.feed.HomeFeedBuilder
import app.kaeru.domain.model.Anime
import app.kaeru.domain.model.HomeFeed
import app.kaeru.di.IoDispatcher
import app.kaeru.domain.playback.PlaybackPreferences
import app.kaeru.domain.playback.PrefetchTopCardStream
import app.kaeru.domain.repository.DiscoverRepository
import app.kaeru.domain.repository.LibraryRepository
import app.kaeru.ui.common.errorMessageOrNull
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
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
    val loadingNow: Boolean = false,
    val seasonal: Map<Season, List<Anime>> = emptyMap(),
    val loading: Set<Season> = emptySet(),
    /** Sticky: once a season has answered, the switcher has earned its place for the session. */
    val anySeasonLoaded: Boolean = false,
) {
    fun toUiState() = DiscoverUiState(
        season = season,
        popularNow = popularNow,
        seasonal = seasonal[season],
        loadingNow = loadingNow,
        loadingSeasonal = season in loading,
        anySeasonLoaded = anySeasonLoaded,
    )
}

@HiltViewModel
class HomeViewModel @Inject constructor(
    private val repository: LibraryRepository,
    private val discover: DiscoverRepository,
    private val feedBuilder: HomeFeedBuilder,
    private val clock: Clock,
    prefs: PlaybackPreferences,
    private val prefetchStream: PrefetchTopCardStream,
    @param:IoDispatcher private val io: CoroutineDispatcher,
) : ViewModel() {
    private val refreshState = MutableStateFlow(RefreshState())
    private var refreshJob: Job? = null

    // The zone is the device's rather than the clock's: a season is which quarter the viewer is
    // living in, and on four evenings a year that differs from the one in UTC.
    private val openedIn: Season = Season.current(clock.instant(), ZoneId.systemDefault())

    private val discoverState = MutableStateFlow(DiscoverState(season = openedIn))
    private var nowJob: Job? = null
    private val seasonJobs = mutableMapOf<Season, Job>()

    /** The card already prepared, so a screen that renders again costs the source nothing. */
    private var prepared: Pair<Int, Int>? = null
    private var prefetchJob: Job? = null

    val uiState: StateFlow<HomeUiState> = combine(
        repository.observeLibrary(),
        refreshState,
        prefs.watchedThreshold,
        discoverState,
    ) { entries, refresh, threshold, discovered ->
        HomeUiState(
            feed = feedBuilder.build(entries, clock.instant(), threshold),
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

    init { syncLibrary() }

    /**
     * Start the catalogue rows. Called by the screen that draws them, once — both home screens do.
     *
     * Not in `init`, which is for the library sync every reader of this view model wants. The
     * catalogue is four more requests on top of it, and a screen sharing this view model without
     * drawing a catalogue would put them in the rate limiter ahead of the sync it actually came
     * for. Both loads are guarded, so calling this again — coming back to the home screen, a
     * recomposition — costs nothing.
     *
     * Unforced: reopening the screen should cost the catalogue nothing for six hours.
     */
    fun loadDiscover() {
        loadPopularNow(force = false)
        loadSeason(discoverState.value.season, force = false)
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

    /**
     * Resolves the episode the top card offers, while the viewer is still reading it.
     *
     * Called by the screen once it has something to draw, because that is when the card exists.
     * At most one Kodik round trip per card: the same card asked for again does nothing, and a
     * card offering an episode that has not aired asks for nothing at all — the button under it
     * is unpressable, so there is nothing to be ready for.
     *
     * Everything about it is invisible. It writes nothing down, shows nothing and reports nothing;
     * a failure simply means the press that follows resolves the way it always did.
     */
    fun prefetchTopCard() {
        val top = uiState.value.feed.top ?: return
        val animeId = top.entry.anime.id
        if (top.episode <= 0 || top.episode > top.entry.anime.availableEpisodes) return
        val card = animeId to top.episode
        if (prepared == card || prefetchJob?.isActive == true) return
        prepared = card
        prefetchJob = viewModelScope.launch {
            withContext(io) { prefetchStream(animeId, top.episode) }
        }
    }

    /** A chip was pressed. A season already read is shown at once; a new one is fetched. */
    fun selectSeason(season: Season) {
        if (season == discoverState.value.season) return
        discoverState.update { it.copy(season = season) }
        loadSeason(season, force = false)
    }

    /**
     * «Повторить» under a season that would not load.
     *
     * Unforced on purpose: the repository does not cache failures, so there is no stale answer to
     * step around, and an unforced read still reaches the network. Forcing would also throw away a
     * perfectly good cached answer if the failure turned out to be somewhere else.
     */
    fun retrySeason() = loadSeason(discoverState.value.season, force = false)

    private fun syncLibrary() {
        if (refreshJob?.isActive == true) return
        refreshJob = viewModelScope.launch {
            refreshState.value = RefreshState(active = true)
            val result = repository.refresh()
            refreshState.value = RefreshState(active = false, error = result.errorMessageOrNull())
        }
    }

    private fun loadPopularNow(force: Boolean) {
        // Titles already in hand, or on their way, are the answer to an unforced ask — which is
        // what returning to the home screen is. A read that failed left this null, so coming back
        // after one is a retry.
        if (!force && (discoverState.value.popularNow != null || nowJob?.isActive == true)) return
        nowJob?.cancel()
        nowJob = viewModelScope.launch {
            discoverState.update { it.copy(loadingNow = true) }
            // A failure leaves the row absent. It is not this screen's error to report: the rows
            // above answer the question the viewer opened the app with, and a banner about the
            // catalogue would be about something they did not ask for.
            val titles = discover.popularNow(force).getOrNull()
            // A failure never takes away titles the viewer is already reading. Pull to refresh
            // going into a tunnel and the row would otherwise vanish from both layers at once,
            // since the repository does not cache failures either.
            discoverState.update { it.copy(popularNow = titles ?: it.popularNow, loadingNow = false) }
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
                    seasonal = when {
                        titles != null -> it.seasonal + (season to titles)
                        // A failed refresh keeps the row that is on screen, the same way the
                        // popular row does; only a read with nothing behind it drops the key.
                        force -> it.seasonal
                        // Left out rather than remembered empty, so pressing the chip is a retry.
                        else -> it.seasonal - season
                    },
                    loading = it.loading - season,
                    anySeasonLoaded = it.anySeasonLoaded || titles != null,
                )
            }
            seasonJobs -= season
        }
    }
}
