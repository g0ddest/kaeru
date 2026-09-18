package app.kaeru.ui.common.settings

import app.kaeru.domain.model.Account
import app.kaeru.domain.model.Quality
import app.kaeru.domain.playback.TranslationRanker

/**
 * Everything the settings screen draws.
 *
 * There is no «saving» flag and no per-setting error. A setting here is a local preference: the
 * write cannot be refused, and the view model normalises a value before writing it so it cannot
 * come back different either. A control that showed a spinner after a press would be inventing a
 * doubt the viewer does not have.
 *
 * The defaults are the values an unconfigured store answers with, so the first frame — before
 * DataStore has spoken — is already the truth rather than a blank waiting to be filled.
 */
data class SettingsUiState(
    /** Nobody can be named yet: neither the cache nor Shikimori has answered. */
    val accountLoading: Boolean = true,
    val account: Account? = null,
    val autoplayNext: Boolean = true,
    /** Whether leaving the app with an episode playing folds it into a floating window. */
    val pipOnLeave: Boolean = true,
    /** Whether the app looks in the background for a new episode and says when it finds one. */
    val newEpisodes: Boolean = true,
    /** null is «Авто»: whatever the source offers best. */
    val defaultQuality: Quality? = null,
    val watchedThreshold: Float = 0.9f,
    /** The dub order in force: the viewer's own if they set one, otherwise the app's. */
    val studios: List<String> = TranslationRanker.DEFAULT_STUDIOS,
    /** [studios] is the viewer's own list, so there is something for «Сбросить» to undo. */
    val studiosChosen: Boolean = false,
    /** A Kodik key typed in by hand; empty means the app uses the public one. */
    val kodikToken: String = "",
) {
    /**
     * Taking the last studio away would empty the stored list, which reads back as «never chosen»
     * — the same state a reset leaves, reached by a control that does not say so. The reset button
     * is the labelled way there, so the last row keeps its name.
     */
    val canRemoveStudio: Boolean get() = studios.size > 1
}
