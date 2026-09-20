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
     * The hero band above the rows: a one-line title, and one line carrying what OK does and where
     * the viewer is.
     *
     * Fixed, and that is the point. A band that grew with a long title would push the first row of
     * cards off the bottom of the screen on some titles and not others — which is the exact fault
     * the television screen had before: text over artwork over cards, all fighting for the same
     * 540dp.
     *
     * **One line, always.** It was two, and the second one was being paid for by the row below: the
     * cards, their captions and their focus rings wanted 36dp more than the panel had. The card
     * under the remote repeats the name in its own caption anyway, so the second line was saying
     * the same thing twice on the one screen with no room to say anything twice.
     *
     * The number is the sum of what it holds and is recomputed whenever the type scale moves: one
     * line of `displaySmall` at 56dp, [KaeruTokens.Space3] between, and a line of `titleMedium` at
     * 30dp — 98dp of content. It is sized for where that content has least room, which is under a
     * notice: a notice takes [NoticeBlock] out of [BandTotal] including the [SafeVertical] the band
     * would otherwise have carried itself, so the band needs 98 + [NoticeBlock] − [SafeVertical] =
     * 121 before it is anything at all. The rest is air above the title, where the artwork shows
     * through.
     */
    val HeroHeight = 128.dp

    /**
     * Everything above the rows, whatever happens to be inside it.
     *
     * This number is the one the rows are measured against, and it does not move: a notice
     * appearing at the top of the panel takes its height out of the hero band rather than out of
     * the viewport below. What is left is 358dp; a row is its heading, [KaeruTokens.Space3] under
     * it and a card with its focus room — 324dp — so the rows have 34dp of slack in every state a
     * viewer can put the screen in, rather than the −36 they had.
     */
    val BandTotal = SafeVertical + HeroHeight

    /**
     * One line of notice above the band: «Нет сети», or «Доступна версия 0.4.0».
     *
     * A line of `bodyMedium` on the television scale is 26dp and the compact strips put
     * [KaeruTokens.Space1] above and below it. Not the 48dp touch floor the phone uses — nothing
     * here is touched, and on a remote the target is whatever has the focus ring around it.
     */
    val NoticeHeight = 34.dp

    /**
     * How far the notice is held off the top edge of the panel.
     *
     * Less than [SafeVertical], and deliberately: the five per cent figure is what a title, a
     * poster or a control has to clear, and this is one quiet grey line on a grey ground. 16dp
     * clears the overscan of every panel this app has been on, and the 11dp it saves goes to the
     * band below it, which is measuring a hero title against the same 540.
     */
    val NoticeInset = 16.dp

    /** The notice and the inset that keeps it out of the part of the panel a television crops. */
    val NoticeBlock = NoticeInset + NoticeHeight

    /**
     * What is left of the band once a notice has taken the top of it.
     *
     * The notice carries the inset, so the band gives up its own as well as the notice's height:
     * 105dp for the 98 the title and its action line need. That is the state [HeroHeight] is sized
     * for, and the reason a notice costs the rows below nothing at all.
     */
    val HeroHeightUnderNotice = BandTotal - NoticeBlock

    /** Between the hero and the first row. */
    val HeroGap = KaeruTokens.Space4

    /**
     * Room around a row for the six per cent a focused card grows by.
     *
     * What grows is the whole card now, not only its artwork — that is what makes a focused tile
     * scroll into view with its name — so the number is about the card's full height. 234dp of
     * poster, a name under it and the clearance around that is about 278dp, and six per cent of
     * that is 17dp: eight and a half above and below. A lazy list clips to its own bounds, so eight
     * would shave the focus ring along the top and bottom edges.
     */
    val CardFocusPad = KaeruTokens.Space3

    /**
     * How many cards of a row can be relied on to be composed, counted low on purpose.
     *
     * The content column is 960 − 80 − 56 = 824dp and a card's pitch is 144 + 16 = 160dp, so five
     * and a sixth fit; a lazy row composes a little beyond its viewport as well. Four is the
     * number that is true even if a future card grows, and being wrong low costs one needless
     * scroll while being wrong high costs a screen the D-pad cannot move.
     */
    const val RowViewport = 4

    /**
     * The same count for the library's grid: five columns, and a 540dp panel shows two full rows of
     * them under the tabs with a third on the way in.
     */
    const val GridViewport = 10

    /**
     * And for the home screen's column of rows: the band takes 155 of 540dp, the safe area below
     * takes 27, and a row is about 324 — so the row the screen opens on is the only one with
     * pixels on it and the next one is not composed at all. One, therefore, and being wrong low
     * here costs a scroll nobody sees.
     */
    const val ColumnViewport = 1
}
