package app.kaeru.ui.tv.details

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import app.kaeru.domain.model.AnimeStatus
import app.kaeru.ui.common.details.DetailsUiState
import app.kaeru.ui.common.theme.KaeruTvTheme
import app.kaeru.ui.tv.tvPreviewAnime
import app.kaeru.ui.tv.tvPreviewEntry
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The way into an episode's actions that does not depend on holding OK.
 *
 * Long press is the quicker way in and the one the home screen already uses, but it is invisible
 * and it is the remote's to honour — so the panel has a control of its own, and these are the
 * assertions that say a viewer can reach both marks without ever holding a button.
 */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w960dp-h540dp-television-notnight-mdpi")
class TvTitleActionsTest {
    @get:Rule val compose = createComposeRule()

    private val marked = mutableListOf<Int>()
    private val unmarked = mutableListOf<Int>()

    /** A finished four-episode show, watched through [watched]. */
    private fun show(watched: Int) {
        val anime = tvPreviewAnime(1, "Фрирен", status = AnimeStatus.RELEASED, episodes = 4, aired = 4)
        compose.setContent {
            KaeruTvTheme {
                TvTitleScreen(
                    state = DetailsUiState(
                        entry = tvPreviewEntry(anime, watched = watched),
                        anime = anime,
                        refreshing = false,
                    ),
                    onRetry = {},
                    onStatus = {},
                    onPlay = { _, _ -> },
                    onLoadTranslations = {},
                    onPickTranslation = {},
                    onMarkWatched = { marked += it },
                    onMarkUnwatched = { unmarked += it },
                )
            }
        }
    }

    @Test
    fun `the episode actions have a control of their own, not only a long press`() {
        show(watched = 4)

        compose.onNodeWithText("Ещё").assertIsDisplayed()
    }

    /** Everything watched: the panel opens on the first episode, and offers to take its mark off. */
    @Test
    fun `a watched episode can be un-marked without holding OK`() {
        show(watched = 4)

        compose.onNodeWithText("Ещё").performClick()
        compose.onNodeWithText("1 серия").assertIsDisplayed()
        compose.onNodeWithText("Отметить непросмотренной").performClick()

        assertEquals(listOf(1), unmarked)
        assertEquals(emptyList<Int>(), marked)
    }

    /** With episodes still ahead, the panel opens on the one the watch button offers. */
    @Test
    fun `an unwatched episode is offered the other mark from the same control`() {
        show(watched = 2)

        compose.onNodeWithText("Ещё").performClick()
        compose.onNodeWithText("3 серия").assertIsDisplayed()
        compose.onNodeWithText("Отметить просмотренной").performClick()

        assertEquals(listOf(3), marked)
        assertEquals(emptyList<Int>(), unmarked)
    }
}
