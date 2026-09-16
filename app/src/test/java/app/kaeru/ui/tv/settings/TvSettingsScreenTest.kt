package app.kaeru.ui.tv.settings

import androidx.compose.ui.input.key.Key
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.hasScrollAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.pressKey
import app.kaeru.domain.model.Account
import app.kaeru.domain.model.Quality
import app.kaeru.ui.common.settings.SettingsUiState
import app.kaeru.ui.common.theme.KaeruTvTheme
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The screen the viewer reported as not fitting on a television, asked the two questions a
 * composition can answer and a preview cannot: does the remote start somewhere sensible, and is
 * the bottom of the page reachable at all.
 */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w960dp-h540dp-television-notnight-mdpi")
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

    @Test
    fun `the D-pad walks the whole page, down to the last line of it`() {
        // The fault the viewer reported: the rows below the fold could not be reached. A D-pad
        // scrolls by moving focus and nothing else, so every row has to be a stop — including the
        // version at the bottom, which is why that one is focusable.
        show()

        repeat(40) { compose.onRoot().performKeyInput { pressKey(Key.DirectionDown) } }

        compose.onNodeWithText("Kaeru", substring = true).assertIsDisplayed().assertIsFocused()
    }

    @Test
    fun `the list has scrolled by the time the D-pad reaches the bottom`() {
        show()

        repeat(40) { compose.onRoot().performKeyInput { pressKey(Key.DirectionDown) } }

        compose.onNode(hasScrollAction()).assert(
            SemanticsMatcher("the list has scrolled away from the top") { node ->
                val range = node.config.getOrNull(SemanticsProperties.VerticalScrollAxisRange)
                range != null && range.value() > 0f
            },
        )
    }

    /**
     * And it is not enough for the last row to be *reachable*: it has to land somewhere a
     * television actually draws. A panel crops about five per cent of its own picture, so a row the
     * D-pad brings flush against the bottom edge of the list is a row half of whose viewers never
     * see it. The safe area therefore sits outside the scrolling viewport, the way it does on the
     * home rows and both poster grids, rather than as content padding inside it.
     */
    @Test
    fun `the last row of the page lands clear of the edge the panel crops`() {
        show()

        repeat(40) { compose.onRoot().performKeyInput { pressKey(Key.DirectionDown) } }

        val row = compose.onNodeWithText("Kaeru", substring = true).fetchSemanticsNode()
        val panel = compose.onRoot().fetchSemanticsNode().size.height.toFloat()
        val top = row.positionInRoot.y
        val bottom = top + row.size.height
        assertTrue(
            "the last row runs from $top to $bottom on a panel $panel tall",
            top >= SAFE_MARGIN && bottom <= panel - SAFE_MARGIN,
        )
    }

    private companion object {
        /** Nothing readable sits closer than this to an edge a television may crop. */
        const val SAFE_MARGIN = 16f
    }
}
