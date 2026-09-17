package app.kaeru.ui.mobile.home

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import app.kaeru.domain.model.HomeFeed
import app.kaeru.ui.common.home.HomeUiState
import app.kaeru.ui.common.theme.KaeruTheme
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The one-line row that says a newer version exists.
 *
 * It is the whole of what this app does unprompted about an update — no dialog, no badge, no
 * notification — so the two things worth checking are that it appears when there is something to
 * say, and that it says nothing at all when there is not.
 */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w360dp-h640dp-notnight-xhdpi")
class HomeScreenUpdateRowTest {
    @get:Rule val compose = createComposeRule()

    private fun show(state: HomeUiState, onUpdate: () -> Unit = {}) = compose.setContent {
        KaeruTheme {
            HomeScreen(
                state = state,
                onRefresh = {},
                onPlay = { _, _ -> },
                onAnime = {},
                onSettings = {},
                onSearch = {},
                onSeason = {},
                onRetrySeason = {},
                onUpdate = onUpdate,
            )
        }
    }

    private fun state(updateVersion: String? = null, offline: Boolean = false) = HomeUiState(
        feed = HomeFeed.EMPTY,
        isLoading = false,
        offline = offline,
        updateVersion = updateVersion,
    )

    @Test
    fun `a newer version is named in one line`() {
        show(state(updateVersion = "0.4.0"))

        compose.onNodeWithText("Доступна версия 0.4.0").assertIsDisplayed()
    }

    @Test
    fun `a device on the newest version is told nothing`() {
        show(state())

        assertTrue(
            "the row should not be drawn with nothing to say",
            compose.onAllNodesWithText("Доступна версия", substring = true).fetchSemanticsNodes().isEmpty(),
        )
    }

    @Test
    fun `the row leads to the updates screen`() {
        var opened = false
        show(state(updateVersion = "0.4.0"), onUpdate = { opened = true })

        compose.onNodeWithText("Доступна версия 0.4.0").performClick()

        assertTrue("the row did not open the updates screen", opened)
    }

    /**
     * Both facts at once, and the network first: an update is not going anywhere, and «нет сети»
     * is the one that explains why the rest of the screen looks the way it does.
     */
    @Test
    fun `offline the row sits under the strip about the network`() {
        show(state(updateVersion = "0.4.0", offline = true))

        val offline = compose.onNodeWithText("Нет сети", substring = true).fetchSemanticsNode()
        val update = compose.onNodeWithText("Доступна версия 0.4.0").fetchSemanticsNode()
        assertTrue(
            "the update row is at ${update.positionInRoot.y}, the offline strip at ${offline.positionInRoot.y}",
            update.positionInRoot.y > offline.positionInRoot.y,
        )
    }
}
