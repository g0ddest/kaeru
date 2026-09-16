package app.kaeru.ui.tv.details

import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performSemanticsAction
import app.kaeru.domain.model.AnimeStatus
import app.kaeru.ui.common.details.DetailsUiState
import app.kaeru.ui.common.theme.KaeruTvTheme
import app.kaeru.ui.tv.tvPreviewAnime
import app.kaeru.ui.tv.tvPreviewEntry
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The other half of «the layout does not fit the screen»: the season grid on the title card.
 *
 * Every tile the D-pad can reach has to be whole on the panel when it is focused — not the artwork
 * with the number under it somewhere past the bottom edge. The grid here carries the number inside
 * the tile rather than under it, so the tile *is* the caption, and this is the assertion that keeps
 * it that way as the screen above it grows.
 *
 * The same harness as [TvTitleActionsTest]: the real screen, at the television's own qualifiers.
 */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w960dp-h540dp-television-notnight-mdpi")
class TvTitleGridBoundsTest {
    @get:Rule val compose = createComposeRule()

    /** Nothing readable sits closer than this to an edge a television may crop. */
    private val safeMargin = 16f

    /** A finished four-episode show: one grid row, so every tile is composed and reachable. */
    private fun show() {
        val anime = tvPreviewAnime(1, "Фрирен", status = AnimeStatus.RELEASED, episodes = 4, aired = 4)
        compose.setContent {
            KaeruTvTheme {
                TvTitleScreen(
                    state = DetailsUiState(
                        entry = tvPreviewEntry(anime, watched = 2),
                        anime = anime,
                        refreshing = false,
                    ),
                    onRetry = {},
                    onStatus = {},
                    onPlay = { _, _ -> },
                    onLoadTranslations = {},
                    onPickTranslation = {},
                    onMarkWatched = {},
                    onMarkUnwatched = {},
                )
            }
        }
        compose.waitForIdle()
    }

    private fun focusTile(episode: Int) {
        compose.onNodeWithText("$episode").performSemanticsAction(SemanticsActions.RequestFocus)
        compose.waitForIdle()
    }

    private fun assertTileOnPanel(episode: Int) {
        val tile = compose.onNodeWithText("$episode").fetchSemanticsNode()
        val panel = compose.onRoot().fetchSemanticsNode().size.height.toFloat()
        val top = tile.positionInRoot.y
        val bottom = top + tile.size.height
        assertTrue(
            "tile $episode runs from $top to $bottom on a panel $panel tall",
            top >= safeMargin && bottom <= panel - safeMargin,
        )
    }

    @Test
    fun `the first tile of the season is whole on the panel when it is focused`() {
        show()

        focusTile(1)

        assertTileOnPanel(1)
    }

    @Test
    fun `the last tile of the season is whole on the panel when it is focused`() {
        show()

        focusTile(4)

        assertTileOnPanel(4)
    }
}
