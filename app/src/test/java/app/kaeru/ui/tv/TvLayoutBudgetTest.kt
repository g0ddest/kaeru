package app.kaeru.ui.tv

import app.kaeru.ui.common.design.KaeruTokens
import app.kaeru.ui.common.theme.KaeruTvTypography
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The television's height budget, added up in device-independent pixels.
 *
 * This is the one check on the television layout that Robolectric cannot do. A composed test there
 * measures text with a synthetic face whose metrics do not vary with the font size at all, so
 * `TvHomeCardBoundsTest` certifies that the pieces are arranged correctly and says nothing about
 * whether they fit — it would pass just as happily with a type scale twice the size. This adds the
 * declared numbers up instead: the panel a 1080p television reports, the type scale as written, and
 * the tokens the screens are built from.
 *
 * It exists because raising the line heights to fit Manrope's own box moved every one of these
 * numbers at once, and the failure it guards against is invisible until somebody looks at a
 * television: a hero title growing up into the part of the picture the panel crops, or a card whose
 * name cannot be brought into view because the card is taller than the space it has to live in.
 */
class TvLayoutBudgetTest {

    /** A 1920×1080 panel reports 960×540dp, and about five per cent of it may never be drawn. */
    private val panel = 540f
    private val safe = TvLayout.SafeVertical.value

    /** What the hero band holds: a two-line title, a gap, and the line saying what OK does. */
    private val heroContent =
        2 * KaeruTvTypography.displaySmall.lineHeight.value +
            KaeruTokens.Space3.value +
            KaeruTvTypography.titleMedium.lineHeight.value

    /** The same band with the title cut to one line, which is what a notice above it leaves room for. */
    private val heroContentOneLine =
        KaeruTvTypography.displaySmall.lineHeight.value +
            KaeruTokens.Space3.value +
            KaeruTvTypography.titleMedium.lineHeight.value

    /** One line of `bodyMedium` with [KaeruTokens.Space1] above and below it: a compact strip. */
    private val noticeContent =
        KaeruTvTypography.bodyMedium.lineHeight.value + 2 * KaeruTokens.Space1.value

    /** Artwork, the gap under it, the name, and the clearance that keeps the card's corner off it. */
    private val tile =
        KaeruTokens.PosterWidthTv.value / KaeruTokens.PosterAspect +
            KaeruTokens.Space2.value +
            KaeruTvTypography.titleSmall.lineHeight.value +
            KaeruTokens.Space2.value

    @Test
    fun `the hero band is tall enough for the two lines it is sized for`() {
        assertTrue(
            "the band holds $heroContent of content in ${TvLayout.HeroHeight.value}, so a two-line " +
                "title grows past the safe area into the part of the panel that is cropped",
            TvLayout.HeroHeight.value >= heroContent,
        )
    }

    /**
     * The rows' viewport is what is left of the panel under the band, and a focused card is brought
     * into *that*. A card taller than it can never be shown whole, however the scrolling behaves.
     */
    @Test
    fun `a whole card fits in the rows' viewport with its focus room`() {
        val needed = tile + 2 * TvLayout.CardFocusPad.value
        assertTrue(
            "a card wants $needed of the ${viewport()} left under the hero band",
            needed <= viewport(),
        )
    }

    /**
     * The same card, on the screen a viewer actually sees most evenings: one with «Доступна версия
     * 0.4.0» across the top of it.
     *
     * This is the check that was missing, and the fault it now catches was real. A strip simply
     * added above the band cost 81dp — its own 54 plus the safe inset over it — straight out of a
     * viewport that had nine to spare, so a focused card's title line and its focus ring were
     * drawn off the bottom of the panel. `OfflineStrip` had the identical cost and nobody had
     * noticed, because the budget test modelled neither.
     *
     * The fix is that there is nothing to model: a notice takes its height out of the band, so
     * the viewport is the same number whatever is on screen. That is what this asserts.
     */
    @Test
    fun `a whole card fits just the same with a notice across the top of the panel`() {
        val needed = tile + 2 * TvLayout.CardFocusPad.value
        val withNotice = panel - (TvLayout.NoticeBlock.value + TvLayout.HeroHeightUnderNotice.value) - safe
        assertEquals(
            "a notice must cost the rows nothing, and costs them ${viewport() - withNotice}",
            viewport(),
            withNotice,
            0.001f,
        )
        assertTrue("a card wants $needed of the $withNotice left under a notice", needed <= withNotice)
    }

    /** The two halves add up to the one number the rows are measured against, in every state. */
    @Test
    fun `a notice and the band it sits on come to the band the rows are measured against`() {
        assertEquals(
            "the notice block and the band under it must come to the whole band",
            TvLayout.BandTotal.value,
            TvLayout.NoticeBlock.value + TvLayout.HeroHeightUnderNotice.value,
            0.001f,
        )
    }

    /** A compact strip has to hold the line it is drawn for, or the sentence is clipped instead. */
    @Test
    fun `the notice is tall enough for the line inside it`() {
        assertTrue(
            "a notice holds $noticeContent of content in ${TvLayout.NoticeHeight.value}",
            TvLayout.NoticeHeight.value >= noticeContent,
        )
    }

    /**
     * What the band gives up for a notice is the title's second line, and no more than that.
     *
     * The one-line title is the trade this design makes. It has to actually fit, or the notice
     * would be paid for twice — once by the second line and again by the first one clipping.
     */
    @Test
    fun `the band under a notice still holds a one-line title`() {
        assertTrue(
            "the band holds $heroContentOneLine of content in ${TvLayout.HeroHeightUnderNotice.value}",
            TvLayout.HeroHeightUnderNotice.value >= heroContentOneLine,
        )
    }

    /** What is left of the panel under everything above the rows. */
    private fun viewport() = panel - TvLayout.BandTotal.value - safe

    /**
     * «Обновления» opens with its button on screen, without the remote having to scroll for it.
     *
     * Composed here rather than in `TvUpdatesScreenTest` for the reason this whole class exists:
     * Robolectric measures text with a synthetic face whose metrics do not move with the type
     * scale, so a composed test says the pieces are arranged correctly and nothing about whether
     * they fit. This adds the declared numbers up — two headings, two lines of prose, the release
     * line, and the button — against the panel a 1080p television reports.
     *
     * The release note is deliberately not in the sum. It is the one thing on the page of unknown
     * length, it sits below the button precisely so it cannot push it off, and it is capped.
     */
    @Test
    fun `the updates page reaches its button inside one panel`() {
        val heading = KaeruTvTypography.titleMedium.lineHeight.value
        val body = KaeruTvTypography.bodyMedium.lineHeight.value
        val label = KaeruTvTypography.titleSmall.lineHeight.value
        val gap = KaeruTokens.Space3.value
        val sectionGap = KaeruTokens.Space8.value - gap
        val needed = safe +
            heading + gap +
            body + gap +
            sectionGap + heading + gap +
            label + KaeruTokens.Space2.value + body + gap +
            KaeruTokens.ButtonHeight.value +
            safe

        assertTrue(
            "the page wants $needed of a $panel panel before the button is reachable",
            needed <= panel,
        )
    }

    /** And the focus ring has to be drawn, not shaved off by the row's own clipping. */
    @Test
    fun `the row reserves the room a focused card grows into`() {
        val grows = tile * (KaeruTokens.FocusScale - 1f) / 2f
        assertTrue(
            "a focused card grows $grows past each edge and the row reserves ${TvLayout.CardFocusPad.value}",
            TvLayout.CardFocusPad.value >= grows,
        )
    }
}
