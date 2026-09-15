package app.kaeru.ui.common.downloads

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.kaeru.domain.connectivity.Connectivity
import app.kaeru.domain.download.DownloadPolicy
import app.kaeru.domain.download.DownloadRepository
import app.kaeru.domain.download.EpisodeDownload
import app.kaeru.domain.model.Anime
import app.kaeru.domain.model.Quality
import app.kaeru.domain.repository.LibraryRepository
import app.kaeru.domain.settings.SettingsStore
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * Everything on the device, grouped by title, and the rules that decide what goes on it next.
 *
 * It depends on four things and deliberately not on the player, the feed or anything that resolves
 * a stream: this screen shows what is already downloaded and changes the policy, and every one of
 * those is a local question.
 */
@HiltViewModel
class DownloadsViewModel @Inject constructor(
    private val downloads: DownloadRepository,
    private val settings: SettingsStore,
    private val library: LibraryRepository,
    private val connectivity: Connectivity,
) : ViewModel() {

    /** Catalogue cards for the titles being downloaded, as they arrive out of Room. */
    private val cards = MutableStateFlow<Map<Int, Anime>>(emptyMap())

    /** Titles already being watched for a card, so a second episode does not start a second watch. */
    private val watched = mutableSetOf<Int>()

    /** Titles already asked of Shikimori, so a title it does not know is asked for once, not forever. */
    private val asked = mutableSetOf<Int>()

    val uiState: StateFlow<DownloadsUiState> = combine(
        downloads.observeAll(),
        downloads.usedBytes,
        settings.downloadPolicy,
        cards,
    ) { rows, used, policy, known ->
        DownloadsUiState(
            usedBytes = used,
            limitBytes = policy.limitBytes,
            titles = group(rows, known),
            policy = policy,
            loading = false,
        )
    }.stateIn(viewModelScope, SharingStarted.Eagerly, DownloadsUiState())

    init {
        viewModelScope.launch {
            downloads.observeAll()
                .map { rows -> rows.map { it.animeId }.toSet() }
                .distinctUntilChanged()
                .collect { ids -> ids.forEach(::follow) }
        }
    }

    // --- the policy controls ----------------------------------------------------------------

    fun setQuality(quality: Quality?) = change { it.copy(quality = quality) }

    fun setWifiOnly(enabled: Boolean) = change { it.copy(wifiOnly = enabled) }

    fun setDeleteWatched(enabled: Boolean) = change { it.copy(deleteWatched = enabled) }

    fun setLimit(bytes: Long?) = change { it.copy(limitBytes = bytes) }

    // --- taking things away -------------------------------------------------------------------

    fun remove(animeId: Int, episode: Int) {
        viewModelScope.launch { downloads.remove(animeId, episode) }
    }

    fun removeTitle(animeId: Int) {
        viewModelScope.launch { downloads.removeAll(animeId) }
    }

    fun removeAll() {
        viewModelScope.launch { downloads.removeAll() }
    }

    /**
     * Writes the whole policy back, and only when something in it actually changed.
     *
     * The policy is one value rather than four settings — the limit check needs the quality it is
     * about to estimate for — so every control here reads the live one and hands back a copy.
     * Pressing the chip that is already chosen writes nothing: DataStore would emit again, and the
     * chips would flicker through a state nobody asked for.
     */
    private fun change(transform: (DownloadPolicy) -> DownloadPolicy) {
        viewModelScope.launch {
            val current = settings.downloadPolicy.first()
            val next = transform(current)
            if (next != current) settings.setDownloadPolicy(next)
        }
    }

    /**
     * Keeps this title's card up to date, and asks Shikimori for it once if Room has none.
     *
     * The card and the network are read together so that a title that could not be named while the
     * phone was offline is asked for the moment the network comes back — without that, an episode
     * downloaded on a plane would stay «Тайтл №404» until the screen was opened again.
     */
    private fun follow(animeId: Int) {
        if (!watched.add(animeId)) return
        viewModelScope.launch {
            combine(library.observeAnimeDetails(animeId), connectivity.online, ::Pair)
                .collect { (anime, online) ->
                    if (anime != null) {
                        cards.update { it + (animeId to anime) }
                        return@collect
                    }
                    cards.update { it - animeId }
                    // Once per title per session, whatever the answer. A title Shikimori has never
                    // heard of would otherwise be asked for on every emission of every row.
                    if (online && asked.add(animeId)) library.refreshAnime(animeId)
                }
        }
    }

    /**
     * Downloads grouped under the title they belong to, newest change first.
     *
     * Newest first because that is the order the viewer put them there in, and because the title
     * they are most likely here to act on is the one they were last downloading. Episodes inside a
     * title go the other way — in episode order — since that is the order they will be watched in.
     */
    private fun group(rows: List<EpisodeDownload>, known: Map<Int, Anime>): List<DownloadedTitle> = rows
        .groupBy { it.animeId }
        .map { (animeId, episodes) ->
            val anime = known[animeId]
            DownloadedTitle(
                animeId = animeId,
                title = anime?.title ?: "Тайтл №$animeId",
                posterUrl = anime?.posterUrl,
                episodes = episodes.sortedBy { it.episode },
                bytes = episodes.sumOf { it.bytes },
            )
        }
        .sortedByDescending { title -> title.episodes.maxOf { it.updatedAt } }
}
