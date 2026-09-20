package app.kaeru.ui.tv.home

import androidx.compose.ui.input.key.Key
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.pressKey
import app.kaeru.domain.model.AnimeStatus
import app.kaeru.domain.model.FeedItem
import app.kaeru.domain.model.FeedKind
import app.kaeru.domain.model.HomeFeed
import app.kaeru.domain.model.WatchState
import app.kaeru.ui.common.home.HomeUiState
import app.kaeru.ui.common.theme.KaeruTvTheme
import app.kaeru.ui.tv.TV_PREVIEW_NOW
import app.kaeru.ui.tv.tvPreviewAnime
import app.kaeru.ui.tv.tvPreviewEntry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The update row on a television, where the question is not whether it is drawn but whether a
 * remote can reach it.
 *
 * It sits above the hero band rather than among the rows, which means the only way to it is the
 * D-pad leaving the list upwards from the first card. That is the press this checks — without it
 * the row is a line of text on a screen nobody can act on, and the settings page is the only way
 * to «Обновления» left on this device.
 */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w960dp-h540dp-television-notnight-mdpi")
class TvHomeUpdateRowTest {
    @get:Rule val compose = createComposeRule()

    private val title = "Фрирен, провожающая в последний путь"

    private fun continuing() = FeedItem(
        tvPreviewEntry(
            tvPreviewAnime(21, title, status = AnimeStatus.ONGOING, episodes = 12, aired = 7),
            watched = 6,
            watch = WatchState(21, 7, 600_000, 1_440_000, null, null, TV_PREVIEW_NOW),
        ),
        episode = 7,
        kind = FeedKind.CONTINUE,
    )

    private fun state(updateVersion: String?, offline: Boolean = false) = HomeUiState(
        feed = HomeFeed(continuing(), listOf(continuing()), emptyList(), emptyList(), emptyList(), emptyList()),
        isLoading = false,
        offline = offline,
        updateVersion = updateVersion,
    )

    private fun show(updateVersion: String?, onUpdate: (() -> Unit)? = {}, offline: Boolean = false) {
        compose.setContent {
            KaeruTvTheme {
                TvHomeScreen(
                    state = state(updateVersion, offline),
                    onRefresh = {},
                    onPlay = { _, _ -> },
                    onDetails = {},
                    onSeason = {},
                    onRetrySeason = {},
                    onSearch = {},
                    onUpdate = onUpdate,
                )
            }
        }
        compose.waitForIdle()
    }

    @Test
    fun `a newer version is named above the hero band`() {
        show("0.4.0")

        compose.onNodeWithText("Доступна версия 0.4.0").assertIsDisplayed()
    }

    @Test
    fun `a device on the newest version is told nothing`() {
        show(null)

        assertTrue(
            "the row should not be drawn with nothing to say",
            compose.onAllNodesWithText("Доступна версия", substring = true).fetchSemanticsNodes().isEmpty(),
        )
    }

    /** The whole point of it being focusable: a remote has to be able to land on it. */
    @Test
    fun `the D-pad reaches the row by going up from the first card`() {
        show("0.4.0")

        repeat(4) { compose.onRoot().performKeyInput { pressKey(Key.DirectionUp) } }

        compose.onNodeWithText("Доступна версия 0.4.0").assertIsFocused()
    }

    /**
     * It is above the band, so it starts inside the part of the picture a panel actually draws.
     *
     * The row itself spans the panel — its ground is a full-width band, as the offline strip's
     * is — so the sentence is what is measured sideways: that is the readable part, and the rail
     * is what it must clear.
     */
    @Test
    fun `the row is clear of the edges the panel crops`() {
        show("0.4.0")

        val row = compose.onNodeWithText("Доступна версия 0.4.0").fetchSemanticsNode()
        assertTrue("the row starts at ${row.positionInRoot.y}", row.positionInRoot.y >= SAFE_MARGIN)

        val text = compose
            .onAllNodesWithText("Доступна версия 0.4.0", useUnmergedTree = true)
            .fetchSemanticsNodes()
            .first()
        assertTrue("the sentence starts at ${text.positionInRoot.x}", text.positionInRoot.x >= RAIL)
    }

    /**
     * The one press this row exists for, made with a remote rather than with a mouse.
     *
     * `UpdateStrip` is built on foundation's `clickable` rather than on a tv-material component,
     * so that OK on a D-pad reaches it at all is worth asserting rather than assuming.
     */
    @Test
    fun `OK on the row opens the updates screen`() {
        var opened = false
        show("0.4.0", onUpdate = { opened = true })
        repeat(4) { compose.onRoot().performKeyInput { pressKey(Key.DirectionUp) } }

        compose.onRoot().performKeyInput { pressKey(Key.DirectionCenter) }

        assertTrue("the remote did not open the updates screen", opened)
    }

    /**
     * Only one line above the band, and the network is the one that gets it.
     *
     * Not a preference: everything above the rows has to come to one number or a focused poster
     * card loses its name off the bottom of the panel, and two lines would take that number out
     * of the title. The update is still there when the signal comes back.
     */
    @Test
    fun `offline, the network notice takes the line and the update waits`() {
        show("0.4.0", offline = true)

        compose.onNodeWithText("Нет сети", substring = true).assertIsDisplayed()
        assertTrue(
            "both notices cannot share the one line there is",
            compose.onAllNodesWithText("Доступна версия", substring = true).fetchSemanticsNodes().isEmpty(),
        )
    }

    @Test
    fun `the notice decision is the network first, then the update, then nothing`() {
        assertEquals(TvHomeNotice.Offline, tvHomeNotice(offline = true, updateVersion = "0.4.0"))
        assertEquals(TvHomeNotice.Offline, tvHomeNotice(offline = true, updateVersion = null))
        assertEquals(TvHomeNotice.Update("0.4.0"), tvHomeNotice(offline = false, updateVersion = "0.4.0"))
        assertNull(tvHomeNotice(offline = false, updateVersion = null))
    }

    private companion object {
        /** Nothing readable sits closer than this to an edge a television may crop. */
        const val SAFE_MARGIN = 16f

        /** The closed navigation rail, which nothing on a screen may be drawn under. */
        const val RAIL = 80f
    }
}
