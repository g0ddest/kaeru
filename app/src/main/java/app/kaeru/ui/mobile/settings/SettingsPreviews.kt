package app.kaeru.ui.mobile.settings

import androidx.compose.runtime.Composable
import androidx.compose.ui.tooling.preview.Preview
import app.kaeru.domain.model.Account
import app.kaeru.domain.model.Quality
import app.kaeru.domain.playback.TranslationRanker
import app.kaeru.ui.common.settings.SettingsUiState
import app.kaeru.ui.common.theme.KaeruTheme

private const val DARK = 0xFF0B0C10

/** Tall enough to hold the whole page, since the point of it is how the sections sit together. */
private const val PAGE = 1400

private val Kaeru = Account(181_712, "kaeru", "https://desu.shikimori.one/system/users/x160/181712.png")

@Composable
private fun Preview(state: SettingsUiState) = KaeruTheme {
    SettingsScreen(
        state = state,
        onBack = {},
        onSignOut = {},
        onAutoplay = {},
        onQuality = {},
        onThreshold = {},
        onStudioUp = {},
        onStudioDown = {},
        onStudioRemove = {},
        onStudioAdd = {},
        onStudiosReset = {},
        onKodikToken = {},
    )
}

/**
 * The screen as it is on a phone that has been used: a name, a list of studios somebody put in
 * that order, and a key typed in by hand. The avatar URL does not load in a preview, so what shows
 * is the fallback letter — which is also what a viewer with no picture gets.
 */
@Preview(showBackground = true, backgroundColor = DARK, widthDp = 360, heightDp = PAGE)
@Composable
private fun SettingsPreview() = Preview(
    SettingsUiState(
        accountLoading = false,
        account = Kaeru,
        autoplayNext = true,
        defaultQuality = Quality.P720,
        watchedThreshold = 0.85f,
        studios = listOf("AniLibria", "JAM", "Dream Cast", "SHIZA Project"),
        studiosChosen = true,
        kodikToken = "447d179e875efe44217f20d1ee2146be",
    ),
)

/** A phone opened for the first time: the app's own dub order, nothing chosen, no key. */
@Preview(showBackground = true, backgroundColor = DARK, widthDp = 360, heightDp = PAGE)
@Composable
private fun SettingsUntouchedPreview() = Preview(
    SettingsUiState(
        accountLoading = false,
        account = Account(181_712, "Фрирен", null),
        studios = TranslationRanker.DEFAULT_STUDIOS,
        studiosChosen = false,
    ),
)

/** The first moment of the screen: everything but the name is already there. */
@Preview(showBackground = true, backgroundColor = DARK, widthDp = 360, heightDp = 420)
@Composable
private fun SettingsLoadingPreview() = Preview(
    SettingsUiState(
        accountLoading = true,
        studios = TranslationRanker.DEFAULT_STUDIOS,
    ),
)

/** Signed in, but Shikimori could not be reached and nothing was cached to fall back on. */
@Preview(showBackground = true, backgroundColor = DARK, widthDp = 360, heightDp = 420)
@Composable
private fun SettingsNoAccountPreview() = Preview(
    SettingsUiState(
        accountLoading = false,
        account = null,
        studios = TranslationRanker.DEFAULT_STUDIOS,
    ),
)
