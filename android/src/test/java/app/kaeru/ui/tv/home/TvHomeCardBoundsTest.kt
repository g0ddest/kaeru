package app.kaeru.ui.tv.home

import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performSemanticsAction
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
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * A card is a picture *and* the name under it, and a television that shows only the picture is a
 * television whose viewer cannot tell what they are looking at.
 *
 * Two faults met in the one symptom the viewer reported — pictures on the screen, names off the
 * bottom of it. The focusable node was the artwork alone, so what the lists were asked to bring
 * into view was never the whole tile; and the opening focus was claimed before anything had been
 * placed, so nothing was brought into view at all and the screen came up with its first row
 * already past the edge of the panel.
 *
 * Measured at the television's own qualifiers, because that is the whole of the fault: 1920×1080
 * reports 960×540dp, and the panel is the constraint.
 */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w960dp-h540dp-television-notnight-mdpi")
class TvHomeCardBoundsTest {
    @get:Rule val compose = createComposeRule()

    /** Nothing readable sits closer than this to an edge a television may crop. */
    private val safeMargin = 16f

    private val title = "Фрирен, провожающая в последний путь"
    private val other = "Дандадан"

    private fun continuing(id: Int, name: String) = FeedItem(
        tvPreviewEntry(
            tvPreviewAnime(id, name, status = AnimeStatus.ONGOING, episodes = 12, aired = 7),
            watched = 6,
            watch = WatchState(id, 7, 600_000, 1_440_000, null, null, TV_PREVIEW_NOW),
        ),
        episode = 7,
        kind = FeedKind.CONTINUE,
    )

    /** One row, so the only two nodes carrying a title are the hero band and the card's own name. */
    private fun home(
        vararg items: FeedItem,
        updateVersion: String? = null,
        offline: Boolean = false,
    ) = HomeUiState(
        feed = HomeFeed(items.first(), items.toList(), emptyList(), emptyList(), emptyList(), emptyList()),
        isLoading = false,
        offline = offline,
        updateVersion = updateVersion,
    )

    private fun showHome(state: HomeUiState) {
        compose.setContent {
            KaeruTvTheme {
                TvHomeScreen(
                    state = state,
                    onRefresh = {},
                    onPlay = { _, _ -> },
                    onDetails = {},
                    onSeason = {},
                    onRetrySeason = {},
                    onSearch = {},
                    onUpdate = {},
                )
            }
        }
        compose.waitForIdle()
    }

    /**
     * The caption of a card. Two nodes carry the title — the hero band at the top of the panel and
     * the card's own name — and the card's is the lower of the two.
     */
    private fun captionBounds(name: String): ClosedFloatingPointRange<Float> {
        val nodes = compose.onAllNodesWithText(name, useUnmergedTree = true).fetchSemanticsNodes()
        assertEquals("the hero band and the card caption", 2, nodes.size)
        val caption = nodes.maxBy { it.positionInRoot.y }
        return caption.positionInRoot.y..(caption.positionInRoot.y + caption.size.height)
    }

    /** The focused card as one node: the whole tile, which is the point of the fix. */
    private fun focusedTileBounds(): ClosedFloatingPointRange<Float> {
        val tile = compose.onAllNodes(hasClickAction()).fetchSemanticsNodes()
            .single { node -> node.config.any { it.key.name == "Focused" && it.value == true } }
        return tile.positionInRoot.y..(tile.positionInRoot.y + tile.size.height)
    }

    private fun panelHeight(): Float = compose.onRoot().fetchSemanticsNode().size.height.toFloat()

    private fun assertOnPanel(what: String, bounds: ClosedFloatingPointRange<Float>) {
        val panel = panelHeight()
        assertTrue(
            "$what runs from ${bounds.start} to ${bounds.endInclusive} on a panel $panel tall",
            bounds.start >= safeMargin && bounds.endInclusive <= panel - safeMargin,
        )
    }

    @Test
    fun `the screen opens with the whole of the focused card on the panel`() {
        showHome(home(continuing(1, title)))

        assertOnPanel("the caption of the focused card", captionBounds(title))
    }

    /** Artwork and name are one node now, so «brought into view» can only mean both of them. */
    @Test
    fun `the focused card is one node and the whole of it is on the panel`() {
        showHome(home(continuing(1, title)))

        assertOnPanel("the focused card", focusedTileBounds())
    }

    /**
     * The same card on the screen most evenings actually show: one with «Доступна версия 0.4.0»
     * across the top of it.
     *
     * A notice added above the hero band used to cost the rows 81dp of a viewport that had nine to
     * spare, so the bottom of a focused card — its name, and the focus ring under it — was drawn
     * off the panel. It now takes that height out of the band instead, and this is the composed
     * proof that it does.
     */
    @Test
    fun `an update notice does not push the focused card off the panel`() {
        showHome(home(continuing(1, title), updateVersion = "0.4.0"))

        assertOnPanel("the focused card under an update notice", focusedTileBounds())
    }

    /** The offline strip had the identical cost and the identical fault. */
    @Test
    fun `the offline notice does not push the focused card off the panel`() {
        showHome(home(continuing(1, title), offline = true))

        assertOnPanel("the focused card under the offline notice", focusedTileBounds())
    }

    /** And the notice itself has to be drawn where a television draws, not in the cropped edge. */
    @Test
    fun `the notice sits clear of the top edge the panel crops`() {
        showHome(home(continuing(1, title), updateVersion = "0.4.0"))

        val notice = compose
            .onAllNodesWithText("Доступна версия 0.4.0", useUnmergedTree = true)
            .fetchSemanticsNodes()
            .single()
        assertTrue(
            "the notice starts at ${notice.positionInRoot.y}",
            notice.positionInRoot.y >= safeMargin,
        )
    }

    /**
     * And the same holds for a card the D-pad arrives at rather than one the screen opened on.
     *
     * Named rather than indexed: a focused card is lifted above its neighbours, which reorders the
     * semantics of the row, so «the second node with a click action» is not «the second card».
     */
    @Test
    fun `a card the remote moves to brings its own caption onto the panel with it`() {
        showHome(home(continuing(1, title), continuing(2, other)))

        compose.onNode(hasText(other) and hasClickAction())
            .performSemanticsAction(SemanticsActions.RequestFocus)
        compose.waitForIdle()

        assertOnPanel("the caption of the card the remote moved to", captionBounds(other))
        assertOnPanel("the card the remote moved to", focusedTileBounds())
    }
}
