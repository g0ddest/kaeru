package app.kaeru.ui.common.details

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.kaeru.domain.connectivity.Connectivity
import app.kaeru.domain.download.DownloadPolicy
import app.kaeru.domain.download.DownloadQualityChoice
import app.kaeru.domain.download.DownloadRepository
import app.kaeru.domain.download.DownloadState
import app.kaeru.domain.download.EpisodeDownload
import app.kaeru.domain.error.DownloadLimitReached
import app.kaeru.domain.model.Anime
import app.kaeru.domain.model.LibraryEntry
import app.kaeru.domain.model.ListStatus
import app.kaeru.domain.model.Quality
import app.kaeru.domain.model.Translation
import app.kaeru.domain.model.WatchState
import app.kaeru.domain.playback.MarkEpisodeUnwatched
import app.kaeru.domain.playback.MarkEpisodeWatched
import app.kaeru.domain.playback.PlaybackPreferences
import app.kaeru.domain.playback.RankedTranslation
import app.kaeru.domain.playback.ResolveEpisodeStream
import app.kaeru.domain.playback.UnwatchedOutcome
import app.kaeru.domain.repository.LibraryRepository
import app.kaeru.domain.repository.WatchStateRepository
import app.kaeru.domain.settings.SettingsStore
import app.kaeru.ui.common.design.downloadLimitLabel
import app.kaeru.ui.common.design.formatBytes
import app.kaeru.ui.common.design.pluralEpisodes
import app.kaeru.ui.common.errorMessageOrNull
import app.kaeru.ui.common.toUserMessage
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.time.Clock
import javax.inject.Inject

data class DetailsUiState(
    val entry: LibraryEntry? = null,
    /** Card data for anime that is not (yet) in the user's list; equals `entry.anime` otherwise. */
    val anime: Anime? = null,
    val refreshing: Boolean = true,
    /** A write to the viewer's list is in flight, whichever control started it. */
    val updatingStatus: Boolean = false,
    val errorMessage: String? = null,
    /**
     * The dub whose save produced [errorMessage], so «Повторить» repeats that pick rather than
     * reloading the anime, which is not what failed.
     */
    val failedPick: Translation? = null,
    /** How much of an episode counts as watched; decides which episode the main button offers. */
    val watchedThreshold: Float = 0.9f,
    /**
     * The dubs this anime has, ranked, once the chooser has asked for them; each says whether it
     * is one this viewer keeps choosing.
     */
    val translations: List<RankedTranslation> = emptyList(),
    val loadingTranslations: Boolean = false,
    /** A dub is being written; the dub control says so and the chooser stops accepting taps. */
    val savingTranslation: Boolean = false,
    /** Why the dub list could not be read; shown inside the chooser, not over the screen. */
    val translationsError: String? = null,
    /** Every download of this title, in episode order, in whatever state each one is in. */
    val downloads: List<EpisodeDownload> = emptyList(),
    /** There is no network: the strip is on the screen, and nothing that needs Kodik will work. */
    val offline: Boolean = false,
    /** The height new downloads take, as the settings have it. What the sheet's chips open on. */
    val downloadQuality: Quality? = DownloadPolicy.DEFAULT.quality,
    /**
     * What one episode is assumed to weigh, for the sheet's «Скачать 3 серии (~1,2 ГБ)».
     *
     * The average of what this device has actually downloaded, which is the only honest estimate
     * available: it already accounts for the height the viewer downloads at and the length of this
     * kind of episode. Until there is one, the same 400 MB the limit check uses.
     */
    val episodeEstimate: Long = DownloadPolicy.FALLBACK_ESTIMATE,
    /**
     * The last download the storage limit refused, in the words the snackbar says.
     *
     * Kept apart from [errorMessage] because the two offer different ways out: a failed load is
     * «Повторить», and a limit reached is «Загрузки», where the space actually is.
     */
    val storageMessage: String? = null,
    /**
     * What the last un-mark took away, or null when there is nothing to put back.
     *
     * The whole outcome rather than the episode number, because those are two different things to
     * restore: the snackbar names the episode, and «Отменить» has to give back the count that stood
     * before — which is usually higher, since un-marking the fifth episode of seven un-marks the
     * sixth and seventh with it. Set only on a write that actually went through: a refusal is
     * [errorMessage], and offering to undo something that did not happen would be the app arguing
     * with itself.
     */
    val unwatched: UnwatchedOutcome? = null,
)

/** What the download engine and the network say about this title, read as one value. */
private data class DownloadSnapshot(
    val rows: List<EpisodeDownload> = emptyList(),
    val estimate: Long = DownloadPolicy.FALLBACK_ESTIMATE,
    val quality: Quality? = DownloadPolicy.DEFAULT.quality,
    val offline: Boolean = false,
)

@HiltViewModel
class DetailsViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val repository: LibraryRepository,
    private val prefs: PlaybackPreferences,
    private val streams: ResolveEpisodeStream,
    private val watchStates: WatchStateRepository,
    private val markEpisodeWatched: MarkEpisodeWatched,
    private val markEpisodeUnwatched: MarkEpisodeUnwatched,
    private val clock: Clock,
    private val downloads: DownloadRepository,
    private val settings: SettingsStore,
    private val connectivity: Connectivity,
) : ViewModel() {
    private val animeId: Int = checkNotNull(savedStateHandle["animeId"])
    private val work = MutableStateFlow(DetailsUiState())

    /**
     * The cached dub list no longer matches what this anime remembers, so the next open re-reads it.
     *
     * Not part of [DetailsUiState] because no screen renders it: it decides whether a call happens,
     * not what is drawn. Touched only from the main dispatcher, like every other method here.
     */
    private var translationsStale = false

    /**
     * Everything read off the download engine, in one arm of the combine below.
     *
     * Every download rather than this title's, because the estimate is an average over all of
     * them: this title may have none yet, and a screen that estimated only from itself would offer
     * «~400 МБ» to a viewer whose device already knows an episode is 320.
     */
    private val downloadState = combine(
        downloads.observeAll(), settings.downloadPolicy, connectivity.online,
    ) { all, policy, online ->
        DownloadSnapshot(
            rows = all.filter { it.animeId == animeId }.sortedBy { it.episode },
            estimate = estimate(all),
            quality = policy.quality,
            offline = !online,
        )
    }

    val uiState: StateFlow<DetailsUiState> = combine(
        repository.observeAnime(animeId),
        repository.observeAnimeDetails(animeId),
        work,
        prefs.watchedThreshold,
        downloadState,
    ) { entry, details, state, threshold, device ->
        state.copy(
            entry = entry,
            anime = entry?.anime ?: details,
            watchedThreshold = threshold,
            downloads = device.rows,
            offline = device.offline,
            downloadQuality = device.quality,
            episodeEstimate = device.estimate,
        )
    }.stateIn(viewModelScope, SharingStarted.Eagerly, DetailsUiState())

    init { refresh() }

    fun refresh() {
        viewModelScope.launch {
            work.value = work.value.copy(refreshing = true, errorMessage = null, failedPick = null)
            val result = repository.refreshAnime(animeId)
            work.value = work.value.copy(refreshing = false, errorMessage = result.errorMessageOrNull())
        }
    }

    fun setStatus(status: ListStatus) {
        viewModelScope.launch {
            work.value = work.value.copy(updatingStatus = true, errorMessage = null, failedPick = null)
            val result = repository.setStatus(animeId, status)
            work.value = work.value.copy(updatingStatus = false, errorMessage = result.errorMessageOrNull())
        }
    }

    /**
     * Fetches the dubs on offer, the first time the chooser is opened.
     *
     * The catalogue call is a network round trip that most visits to this screen never need, so
     * nothing asks for it until the viewer opens the chooser. A list already in hand is not asked
     * for again; a list that failed is, because the retry inside the chooser calls this — and so is
     * a list that [pickTranslation] has since made stale.
     */
    fun loadTranslations() {
        val current = work.value
        if (current.loadingTranslations) return
        if (current.translations.isNotEmpty() && !translationsStale) return
        // Cleared before the call, not after: a pick that lands while this one is in flight marks
        // the answer stale again, and the open after that re-reads rather than trusting it.
        translationsStale = false
        viewModelScope.launch {
            work.value = work.value.copy(loadingTranslations = true, translationsError = null)
            val result = streams.translations(animeId)
            work.value = work.value.copy(
                loadingTranslations = false,
                translations = result.getOrDefault(emptyList()),
                translationsError = result.errorMessageOrNull(),
            )
        }
    }

    /**
     * Remembers a dub for this anime, so the next press of the watch button uses it.
     *
     * The memory rides on the same `WatchState` row that carries the playback position, so the
     * episode and the position already in it are preserved: changing the voice must not rewind the
     * show. When there is no row yet, one is started at the episode the watch button offers, at
     * position zero — which is where that press would start anyway.
     */
    fun pickTranslation(translation: Translation) {
        viewModelScope.launch {
            work.value = work.value.copy(savingTranslation = true, errorMessage = null, failedPick = null)
            val remembered = watchStates.observe(animeId).first()
            val row = remembered?.copy(
                translationId = translation.id,
                translationTitle = translation.title,
                kodikSeason = translation.season,
                updatedAt = clock.instant(),
            ) ?: WatchState(
                animeId = animeId,
                episode = nextEpisode(),
                positionMs = 0,
                durationMs = 0,
                translationId = translation.id,
                translationTitle = translation.title,
                kodikSeason = translation.season,
                updatedAt = clock.instant(),
            )
            val failure = save(row)
            work.value = work.value.copy(
                savingTranslation = false,
                errorMessage = failure,
                failedPick = translation.takeIf { failure != null },
                // The list in hand was ranked for an anime that remembered nothing, and this anime
                // now remembers something. The tick that moves onto the picked row is the only mark
                // the list should carry, so the habit chips come off at once; the order is put right
                // by the re-read the next open now makes.
                translations = if (failure == null) {
                    work.value.translations.map { it.copy(oftenChosen = false) }
                } else {
                    work.value.translations
                },
            )
            if (failure == null) translationsStale = true
        }
    }

    /**
     * «Повторить» on the message the screen is showing.
     *
     * Not everything that lands in [DetailsUiState.errorMessage] is a failed load: a dub that
     * could not be written leaves one too, and reloading the anime would report success while
     * quietly leaving the dub unchanged. So the retry repeats whatever actually failed.
     */
    fun retry() {
        val pick = work.value.failedPick
        if (pick != null) pickTranslation(pick) else refresh()
    }

    /**
     * Counts an episode as watched without playing it.
     *
     * Shikimori owns that number, and [MarkEpisodeWatched] is the one place that raises it: it
     * picks a planned or shelved anime back up first, adds one that is in no list at all, and
     * sends nothing when the count is already high enough.
     */
    fun markWatched(episode: Int) {
        // The episode the last un-mark took off is a different request wearing the same words:
        // it means «put it back», and putting it back means the count that stood before, not this
        // one episode. Both «Отменить» and the television's own panel arrive here.
        undoneBy(episode)?.let { taken -> return restoreWatched(taken) }
        viewModelScope.launch {
            work.value = work.value.copy(updatingStatus = true, errorMessage = null, failedPick = null)
            val result = markEpisodeWatched(animeId, episode)
            work.value = work.value.copy(updatingStatus = false, errorMessage = result.errorMessageOrNull())
        }
    }

    /** The un-mark this episode would undo, while there is still one to undo. */
    private fun undoneBy(episode: Int): UnwatchedOutcome? = work.value.unwatched?.takeIf { it.episode == episode }

    /** Puts back everything one un-mark took: the count it lowered and the positions it cleared. */
    private fun restoreWatched(taken: UnwatchedOutcome) {
        viewModelScope.launch {
            work.value = work.value.copy(updatingStatus = true, errorMessage = null, failedPick = null)
            val result = markEpisodeUnwatched.restore(animeId, taken)
            work.value = work.value.copy(
                updatingStatus = false,
                errorMessage = result.errorMessageOrNull(),
                // Gone on success, kept on failure: a «Отменить» that could not be written is one
                // the viewer may want to press again.
                unwatched = work.value.unwatched.takeIf { result.isFailure },
            )
        }
    }

    /**
     * Takes the watched mark back off an episode.
     *
     * Shikimori's count comes down to the episode before this one, and the positions this device
     * remembers from here on go with it, so «Продолжить» offers the episode again rather than the
     * one after it. Nothing is asked first: the snackbar's «Отменить» is the confirmation, and it
     * is a better one — it comes after the viewer has seen what happened.
     */
    fun markUnwatched(episode: Int) {
        viewModelScope.launch {
            work.value = work.value.copy(
                updatingStatus = true, errorMessage = null, failedPick = null, unwatched = null,
            )
            val result = markEpisodeUnwatched(animeId, episode)
            work.value = work.value.copy(
                updatingStatus = false,
                errorMessage = result.errorMessageOrNull(),
                unwatched = result.getOrNull(),
            )
        }
    }

    /** «Отменить» on that snackbar: everything the un-mark took, back where it was. */
    fun undoUnwatched() {
        restoreWatched(work.value.unwatched ?: return)
    }

    /**
     * The snackbar has said its piece, so the same episode is not announced twice.
     *
     * It also ends the undo: the count the un-mark lowered is only worth restoring while the
     * viewer can still see what happened to it. The television never calls this, and that is the
     * point — it has no snackbar, so its panel is the undo and has to stay one.
     *
     * [episode] is the one the snackbar was actually showing, not whatever this state currently
     * holds. A second un-mark landing before the first snackbar's effect is torn down replaces
     * [DetailsUiState.unwatched] with a new snapshot, and that first snackbar's own close — or the
     * cancellation of the effect behind it, superseded by the new one — must not take the
     * replacement down with it. Compared rather than trusted blind, so only a shown-and-gone
     * report for the episode still on screen actually clears it.
     */
    fun unwatchedMessageShown(episode: Int) {
        work.value = work.value.copy(unwatched = work.value.unwatched?.takeUnless { it.episode == episode })
    }

    /**
     * Keeps these episodes on the device, at the height the sheet was left on.
     *
     * One at a time and in order, because that is the order they will be watched in and the engine
     * takes them in the order they arrive. The first refusal stops the rest: a limit that refused
     * the third episode will refuse the fourth, and five identical snackbars about it would be the
     * app arguing with itself.
     *
     * @param quality a height for these downloads only; null takes the one in the settings.
     */
    fun download(episodes: List<Int>, quality: DownloadQualityChoice? = null) {
        if (episodes.isEmpty()) return
        viewModelScope.launch {
            // The controls that start a download are off with no network; this is the guard behind
            // them. Resolving a link is the first thing an enqueue does, and with nothing to
            // resolve against it would leave a row on the screen that dies a moment later.
            if (!connectivity.online.first()) return@launch
            refusedBatch(episodes.size)?.let { refusal ->
                work.value = work.value.copy(storageMessage = refusal)
                return@launch
            }
            for (episode in episodes.sorted()) {
                val failure = downloads.enqueue(animeId, episode, quality).exceptionOrNull() ?: continue
                work.value = work.value.copy(storageMessage = storageMessage(failure))
                return@launch
            }
        }
    }

    /**
     * Why this many episodes will not fit, or null when they will.
     *
     * The engine checks one episode at a time, and it has to: it is told about one episode at a
     * time. That check is no use to a batch, because what it measures — bytes actually on disk —
     * barely moves while ten requests are being queued, so ten episodes that will not fit are all
     * accepted and the device runs past a limit that never evicts anything.
     *
     * So the whole batch is weighed here, against the same estimate the button on the sheet showed,
     * and refused as a batch. The message says how many *would* fit, because that is the number the
     * viewer needs to go back and tick.
     */
    private suspend fun refusedBatch(count: Int): String? {
        if (count <= 1) return null
        val policy = settings.downloadPolicy.first()
        val limit = policy.limitBytes ?: return null
        val estimate = uiState.value.episodeEstimate
        val used = downloads.usedBytes.first()
        if (policy.fits(used, estimate * count)) return null
        val room = if (estimate <= 0) 0 else ((limit - used) / estimate).coerceAtLeast(0)
        // The limit reads as the round number the viewer chose from four of them, as it does on
        // «Загрузки»; what is used reads to a tenth, because that is a measurement.
        val sizes = "${formatBytes(used)} из ${downloadLimitLabel(limit)}"
        return if (room <= 0) {
            "Лимит места исчерпан: $sizes. Освободите место в настройках"
        } else {
            "Не хватит места: занято $sizes. Поместится только ${pluralEpisodes(room.toInt())}"
        }
    }

    /** Gives one episode's space back. */
    fun removeDownload(episode: Int) {
        viewModelScope.launch { downloads.remove(animeId, episode) }
    }

    /** The snackbar has been shown, so the next refusal is news again rather than a repeat. */
    fun storageMessageShown() {
        work.value = work.value.copy(storageMessage = null)
    }

    /**
     * Why a download was refused, in words that name the numbers.
     *
     * «Лимит места исчерпан» on its own leaves the viewer with nothing to act on: the two sizes
     * are what tell them whether to delete one episode or to raise the limit.
     */
    private fun storageMessage(failure: Throwable): String = when (failure) {
        is DownloadLimitReached ->
            "Лимит места исчерпан: ${formatBytes(failure.usedBytes)} из ${downloadLimitLabel(failure.limitBytes)}. " +
                "Освободите место в настройках"
        else -> failure.toUserMessage()
    }

    /** The episode the watch button offers, read fresh rather than from the last composition. */
    private suspend fun nextEpisode(): Int =
        repository.observeAnime(animeId).first()?.nextEpisode(prefs.watchedThreshold.first()) ?: 1

    /**
     * Writes the row, and says what went wrong if it could not be written.
     *
     * [WatchStateRepository.save] signals by throwing — a logout mid-screen, a disk failure — and
     * a dub the app silently forgot is a dub the viewer picks again next episode, so the failure
     * is worth a line on the screen.
     */
    private suspend fun save(row: WatchState): String? = try {
        watchStates.save(row)
        null
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (failure: Exception) {
        failure.toUserMessage()
    }
}

/**
 * What one episode is likely to weigh, from what this device has already downloaded.
 *
 * The mean rather than the median or the largest: the number is a hint under a button, not a
 * guarantee, and a mean over a handful of episodes of the same show at the same height is as close
 * as any of them. A device with nothing finished yet has nothing to average, so it takes the same
 * 400 MB the limit check assumes — deliberately on the high side.
 */
private fun estimate(all: List<EpisodeDownload>): Long {
    val finished = all.filter { it.state == DownloadState.COMPLETED && it.bytes > 0 }
    if (finished.isEmpty()) return DownloadPolicy.FALLBACK_ESTIMATE
    return finished.sumOf { it.bytes } / finished.size
}
