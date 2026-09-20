package app.kaeru.ui.common.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.kaeru.domain.model.Account
import app.kaeru.domain.model.Quality
import app.kaeru.domain.playback.TranslationRanker
import app.kaeru.domain.repository.AccountRepository
import app.kaeru.domain.repository.AuthRepository
import app.kaeru.domain.settings.SettingsStore
import app.kaeru.domain.settings.TranslationPriorityEditor
import app.kaeru.domain.settings.WATCHED_THRESHOLD_RANGE
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * The settings screen's state, and the writes behind every control on it.
 *
 * **Why the screen shows its own answer.** Each control writes through to the store and then shows
 * what the viewer just chose, without waiting for the store to say it back. A switch that flicks
 * back for a frame while a file is written is a switch that looks broken.
 *
 * **The invariant that makes it safe:** this view model is the only writer of these settings, and
 * every setter normalises its value the same way the store would, so an override can never mask
 * somebody else's news and can never differ from what the store ends up holding. If a second
 * writer ever appears — a sync, another screen — the overrides have to be reconciled against the
 * store instead of trusted, and this comment is the thread to pull.
 *
 * **What it does not do.** It shows no progress and reports no error for a setting. A preference
 * write is local and cannot be refused; the only thing here that can fail is asking Shikimori for
 * the nickname, and the answer to that failure is the nickname already cached.
 */
@HiltViewModel
class SettingsViewModel @Inject constructor(
    private val settings: SettingsStore,
    private val accounts: AccountRepository,
    private val auth: AuthRepository,
) : ViewModel() {

    /** What the viewer has chosen on this screen, ahead of the store having said it back. */
    private data class Overrides(
        val autoplay: Boolean? = null,
        val skipEnding: Boolean? = null,
        val pipOnLeave: Boolean? = null,
        val newEpisodes: Boolean? = null,
        val quality: Quality? = null,
        /** Quality's own null means «Авто», so whether an override exists is a separate fact. */
        val qualityChosen: Boolean = false,
        val threshold: Float? = null,
        val studios: List<String>? = null,
        val token: String? = null,
    )

    /** The eight settings, read together so one recomposition carries all of them. */
    private data class Stored(
        val studios: List<String>,
        val autoplay: Boolean,
        val quality: Quality?,
        val threshold: Float,
        val token: String?,
        val pipOnLeave: Boolean = true,
        val newEpisodes: Boolean = true,
        val skipEnding: Boolean = false,
    )

    private data class AccountState(val loaded: Boolean, val account: Account?)

    private val overrides = MutableStateFlow(Overrides())

    /**
     * Whether Android would currently let this app post anything, as the screen last saw it.
     *
     * Not a setting and not something this class can read — the permission belongs to the platform
     * and is changed in the system's own settings — so the screen reports it on every return and
     * this only holds the answer. It starts as «yes» so a screen opening on a phone that allows
     * notifications never flashes a warning before its first report.
     */
    private val notificationsAllowed = MutableStateFlow(true)

    /** True until the `whoami` in flight comes back, one way or the other. */
    private val asking = MutableStateFlow(true)

    /** The question currently out, so a second press of «Повторить» does not start a second one. */
    private var asked: Job? = null

    // Two steps, because `combine` is typed up to five flows and there are eight.
    private val stored = combine(
        combine(
            settings.preferredTranslations,
            settings.autoplayNext,
            settings.defaultQuality,
            settings.watchedThreshold,
            settings.kodikToken,
        ) { studios, autoplay, quality, threshold, token -> Stored(studios, autoplay, quality, threshold, token) },
        settings.pipOnLeave,
        settings.newEpisodeNotifications,
        settings.skipEnding,
    ) { playback, pipOnLeave, newEpisodes, skipEnding ->
        playback.copy(pipOnLeave = pipOnLeave, newEpisodes = newEpisodes, skipEnding = skipEnding)
    }

    private val accountState = accounts.account
        .map { AccountState(loaded = true, account = it) }
        .onStart { emit(AccountState(loaded = false, account = null)) }

    val uiState: StateFlow<SettingsUiState> =
        combine(stored, overrides, accountState, asking, notificationsAllowed) {
                settings, chosen, account, asking, allowed ->
            val studios = chosen.studios ?: settings.studios
            // Two halves of one answer. The switch is «on» only where both agree, and the line
            // under it appears whenever they do not: a setting that said «on» over a platform
            // dropping everything is the switch that lies, however it got into that state —
            // a refusal at the player, a permission revoked in system settings months later, or
            // an upgrade from a build that never asked.
            val wantsNewEpisodes = chosen.newEpisodes ?: settings.newEpisodes
            SettingsUiState(
                // An account nobody has named yet is a skeleton while there is still a question
                // outstanding, and «нет аккаунта» once there is not.
                accountLoading = !account.loaded || (account.account == null && asking),
                account = account.account,
                autoplayNext = chosen.autoplay ?: settings.autoplay,
                skipEnding = chosen.skipEnding ?: settings.skipEnding,
                pipOnLeave = chosen.pipOnLeave ?: settings.pipOnLeave,
                newEpisodes = wantsNewEpisodes && allowed,
                newEpisodesBlocked = wantsNewEpisodes && !allowed,
                defaultQuality = if (chosen.qualityChosen) chosen.quality else settings.quality,
                watchedThreshold = chosen.threshold ?: settings.threshold,
                studios = TranslationPriorityEditor.shown(studios, TranslationRanker.DEFAULT_STUDIOS),
                studiosChosen = studios.isNotEmpty(),
                kodikToken = chosen.token ?: settings.token.orEmpty(),
            )
        }.stateIn(viewModelScope, SharingStarted.Eagerly, SettingsUiState())

    init {
        refreshAccount()
    }

    /**
     * Asks Shikimori who this is. Called once when the screen opens, and again by the retry the
     * screen offers when nobody could be named at all.
     *
     * A second press while a question is still out is ignored rather than queued: two answers would
     * race to lower the skeleton, and the first one home would replace it with «Имя не загрузилось»
     * while the other was still on its way.
     *
     * The failure is deliberately dropped: a cached nickname is the right answer when Shikimori
     * cannot be reached, and a screen of local preferences is not the place for a network error.
     */
    fun refreshAccount() {
        if (asked?.isActive == true) return
        asking.value = true
        asked = viewModelScope.launch {
            accounts.refresh()
            asking.value = false
        }
    }

    fun setAutoplayNext(enabled: Boolean) {
        if (enabled == uiState.value.autoplayNext) return
        overrides.update { it.copy(autoplay = enabled) }
        viewModelScope.launch { settings.setAutoplayNext(enabled) }
    }

    fun setSkipEnding(enabled: Boolean) {
        if (enabled == uiState.value.skipEnding) return
        overrides.update { it.copy(skipEnding = enabled) }
        viewModelScope.launch { settings.setSkipEnding(enabled) }
    }

    fun setPipOnLeave(enabled: Boolean) {
        if (enabled == uiState.value.pipOnLeave) return
        overrides.update { it.copy(pipOnLeave = enabled) }
        viewModelScope.launch { settings.setPipOnLeave(enabled) }
    }

    /** What the screen reports after every return to it, and after the system's own dialog. */
    fun notificationsAllowed(granted: Boolean) {
        notificationsAllowed.value = granted
    }

    /**
     * Turning the new-episode check on or off.
     *
     * The screen is what asks for the notification permission before calling this with `true` on
     * Android 13 and later: a setting that says «on» while the system refuses to show anything
     * would be a switch that lies. A refusal simply never reaches here.
     *
     * Compared against what the setting wants rather than against what the switch shows. The two
     * differ exactly while the permission is missing, and a press that agrees with the stored
     * value is not a change however the row is drawn.
     */
    fun setNewEpisodes(enabled: Boolean) {
        val wanted = uiState.value.newEpisodes || uiState.value.newEpisodesBlocked
        if (enabled == wanted) return
        overrides.update { it.copy(newEpisodes = enabled) }
        viewModelScope.launch { settings.setNewEpisodeNotifications(enabled) }
    }

    fun setDefaultQuality(quality: Quality?) {
        if (quality == uiState.value.defaultQuality) return
        overrides.update { it.copy(quality = quality, qualityChosen = true) }
        viewModelScope.launch { settings.setDefaultQuality(quality) }
    }

    /**
     * Everything the store would do to this value happens here first — the same coercion, the same
     * refusal of a value that is not a number — so what the screen shows and what the store holds
     * cannot drift apart. The chips only ever offer values already inside the range.
     */
    fun setWatchedThreshold(fraction: Float) {
        if (!fraction.isFinite()) return
        val wanted = fraction.coerceIn(WATCHED_THRESHOLD_RANGE)
        if (wanted == uiState.value.watchedThreshold) return
        overrides.update { it.copy(threshold = wanted) }
        viewModelScope.launch { settings.setWatchedThreshold(wanted) }
    }

    /** Saved on submit and on leaving the field, so an unchanged one costs nothing. */
    fun setKodikToken(token: String) {
        val cleaned = token.trim()
        if (cleaned == uiState.value.kodikToken) return
        overrides.update { it.copy(token = cleaned) }
        viewModelScope.launch { settings.setKodikToken(cleaned.ifEmpty { null }) }
    }

    fun moveStudioUp(index: Int) = editStudios { TranslationPriorityEditor.moveUp(it, index) }

    fun moveStudioDown(index: Int) = editStudios { TranslationPriorityEditor.moveDown(it, index) }

    fun removeStudio(index: Int) = editStudios { TranslationPriorityEditor.remove(it, index) }

    fun addStudio(name: String) = editStudios { TranslationPriorityEditor.add(it, name) }

    /** Hands the order back to the app. Storing nothing is how the store says «never chosen». */
    fun resetStudios() {
        if (!uiState.value.studiosChosen) return
        overrides.update { it.copy(studios = emptyList()) }
        viewModelScope.launch { settings.setPreferredTranslations(emptyList()) }
    }

    /**
     * Signs the viewer out. Only the confirmation dialog calls this: every other control on the
     * screen writes a preference and nothing else.
     */
    fun signOut() {
        viewModelScope.launch { auth.logout() }
    }

    /**
     * Writes the whole order down, which is what turns the app's opening guess into the viewer's
     * own list the first time they touch it. An edit with nothing to do returns the list it was
     * given, and that identity is the signal to leave the store alone.
     */
    private fun editStudios(edit: (List<String>) -> List<String>) {
        val current = uiState.value.studios
        val next = edit(current)
        if (next === current) return
        overrides.update { it.copy(studios = next) }
        viewModelScope.launch { settings.setPreferredTranslations(next) }
    }
}
