package app.kaeru.ui.tv.settings

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.hasScrollAction
import app.kaeru.domain.model.Account
import app.kaeru.domain.model.Quality
import app.kaeru.ui.common.settings.SettingsUiState
import app.kaeru.ui.common.theme.KaeruTvTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * The screen the viewer reported as not fitting on a television, asked the two questions a
 * composition can answer and a preview cannot: does the remote start somewhere sensible, and is
 * the bottom of the page reachable at all.
 */
@RunWith(RobolectricTestRunner::class)
class TvSettingsScreenTest {
    @get:Rule val compose = createComposeRule()

    private val state = SettingsUiState(
        accountLoading = false,
        account = Account(id = 1, nickname = "vitaliy", avatarUrl = null),
        autoplayNext = true,
        defaultQuality = Quality.P720,
        watchedThreshold = 0.9f,
        studiosChosen = true,
    )

    private fun show() = compose.setContent {
        KaeruTvTheme {
            TvSettingsScreen(
                state = state,
                onSignOut = {},
                onAutoplay = {},
                onQuality = {},
                onThreshold = {},
                onStudioUp = {},
                onStudioDown = {},
                onStudioRemove = {},
                onStudiosReset = {},
                onRetryAccount = {},
            )
        }
    }

    @Test
    fun `the remote starts on the autoplay switch, not on the red button above it`() {
        show()

        compose.onNodeWithText("Следующая серия автоматически").assertIsFocused()
    }

    @Test
    fun `the last row of the page can be reached`() {
        // The fault on the real television: four sections are more than a 540dp panel holds, and
        // everything below the fold was unreachable. Every row is an item now, so the list can be
        // taken to the last of them.
        show()

        compose.onNode(hasScrollAction()).performScrollToNode(hasText("SHIZA Project"))
        compose.onNodeWithText("SHIZA Project").assertIsDisplayed()

        compose.onNode(hasScrollAction()).performScrollToNode(hasText("О приложении"))
        compose.onNodeWithText("О приложении").assertIsDisplayed()
    }

    @Test
    fun `every studio in the list has a row of its own`() {
        show()

        listOf("AniLibria", "AniDUB", "Crunchyroll", "SHIZA Project").forEach { studio ->
            compose.onNode(hasScrollAction()).performScrollToNode(hasText(studio))
            compose.onNodeWithText(studio).assertIsDisplayed()
        }
    }
}
