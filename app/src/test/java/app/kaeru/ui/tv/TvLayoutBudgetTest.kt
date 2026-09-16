package app.kaeru.ui.tv

import app.kaeru.ui.common.design.KaeruTokens
import app.kaeru.ui.common.theme.KaeruTvTypography
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
        val viewport = panel - (safe + TvLayout.HeroHeight.value) - safe
        val needed = tile + 2 * TvLayout.CardFocusPad.value
        assertTrue(
            "a card wants $needed of the $viewport left under the hero band",
            needed <= viewport,
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
