package app.kaeru.ui.tv

import androidx.compose.ui.unit.dp
import app.kaeru.ui.common.design.KaeruTokens

/**
 * The measurements a television screen needs and a phone does not.
 *
 * A 1080p panel reports 960×540dp, and a television may still crop about five per cent of it — so
 * nothing readable starts closer to an edge than [SafeVertical] above and below or [Gutter] at the
 * side. The numbers below are what is left of that budget after the navigation rail takes its
 * share, and they are here rather than in `KaeruTokens` because they are about one device: the
 * design system fixes the 56dp side margin a television content area has, and the rail is what
 * turns that into 80.
 */
object TvLayout {

    /**
     * The drawer when it is closed: a 24dp inset and a 56dp item.
     *
     * The inset exists so the icons clear the five per cent a panel may crop, and the item width
     * is tv-material's own — matching it is what keeps the rail from jumping when the drawer opens.
     */
    val RailWidth = 80.dp

    /** The left margin of everything inside the shell, which is exactly the closed rail. */
    val Gutter = RailWidth

    /** The right margin, where no rail competes for the space. */
    val GutterEnd = KaeruTokens.GutterTv

    /** Five per cent of a 540dp-tall panel. */
    val SafeVertical = 27.dp

    /**
     * The hero band above the rows: a two-line title, and one line carrying what OK does and where
     * the viewer is.
     *
     * Fixed, and that is the point. A band that grew with a long title would push the first row of
     * cards off the bottom of the screen on some titles and not others — which is the exact fault
     * the television screen had before: text over artwork over cards, all fighting for the same
     * 540dp.
     */
    val HeroHeight = 150.dp

    /** Between the hero and the first row. */
    val HeroGap = KaeruTokens.Space4

    /**
     * Room around a row for the six per cent a focused card grows by.
     *
     * A 252dp-tall poster gains 15dp when focused, seven and a half of them above and below. A
     * lazy list clips to its own bounds, so without this the focused card is shaved along its top
     * and bottom edges — and the caption underneath it disappears with them.
     */
    val CardFocusPad = KaeruTokens.Space2
}
