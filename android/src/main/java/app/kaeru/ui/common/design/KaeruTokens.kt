package app.kaeru.ui.common.design

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.unit.dp

/**
 * Every measurement the design system fixes, in one place.
 *
 * Colour lives in `ui.common.theme.Color` and type in `ui.common.theme.Type`, reached through
 * `MaterialTheme`; what is here is the part Material has no opinion about — the grid, the shapes,
 * how long things take, and how big a poster is on a phone versus across a room.
 *
 * The type roles the components use, for reference: display is `displaySmall` (hero title only),
 * headline is `headlineMedium` (screen and state titles), title is `titleMedium` (row headers,
 * buttons) and `titleSmall` (card titles), body is `bodyMedium`, label is `labelMedium` (badges,
 * chips, metadata).
 */
object KaeruTokens {

    // --- spacing: an 8dp grid, with 4 inside a control and 12 between siblings ----------------

    /** Inside a control: an icon and its label, a badge and its edge. */
    val Space1 = 4.dp

    /** Between a line of text and the line under it. */
    val Space2 = 8.dp

    /** Between two things of the same kind: two cards, two buttons. */
    val Space3 = 12.dp

    /** The side margin on a phone, and the gap between a block and the block below it. */
    val Space4 = 16.dp

    /** Between a block and a different kind of block. */
    val Space6 = 24.dp

    /** Around a state that owns the whole screen. */
    val Space8 = 32.dp

    /** The side margin on a television, where the panel edge is off the visible picture. */
    val Space14 = 56.dp

    val GutterPhone = Space4
    val GutterTv = Space14

    // --- shape: three radii, and nothing rounder than a chip ----------------------------------

    val RadiusCard = 12.dp
    val RadiusButton = 12.dp
    val RadiusChip = 20.dp

    val CardShape = RoundedCornerShape(RadiusCard)
    val ButtonShape = RoundedCornerShape(RadiusButton)
    val ChipShape = RoundedCornerShape(RadiusChip)

    // --- motion, in milliseconds ---------------------------------------------------------------

    /** A control answering a press. */
    const val DurationFast = 150

    /** Something opening, closing or changing place. */
    const val DurationNormal = 250

    /** The one orchestrated moment: the hero's backdrop changing under the text. */
    const val DurationHero = 400

    // --- content metrics ------------------------------------------------------------------------

    val PosterWidthPhone = 132.dp

    /**
     * A television poster, sized by what has to fit *under* it rather than by what looks generous.
     *
     * 144dp of width is 216dp of 2:3 artwork, and the card is that plus its name: a 540dp panel has
     * to carry the hero band, a row heading and the whole card inside the five per cent it crops.
     * It was 168 while the type scale asked for line boxes smaller than Manrope draws in, and 156
     * while that scale was a third larger than the phone's. 144 is fifteen per cent of the panel's
     * width, which is what a television launcher's row of artwork is, and it is the number the
     * whole card measures whole in: `TvRenderBudgetTest` renders the row and reads it back.
     */
    val PosterWidthTv = 144.dp

    /** Poster artwork is 2:3; a hero is 4:5, tall enough to be the screen rather than a banner. */
    const val PosterAspect = 2f / 3f
    const val HeroAspect = 4f / 5f

    val ButtonHeight = 52.dp

    /** Nothing the finger or the remote can reach is smaller than this. */
    val MinTouchTarget = 48.dp

    val ProgressHeight = 4.dp

    /**
     * How far the seek bar's track is held back from both edges of the control.
     *
     * The row of timecodes above the bar uses the same value, so «0:00» starts where the track
     * does; the gap it leaves is what the thumb overhangs into at either end.
     */
    val SeekInset = Space3

    /** Tall enough for a thumb and a thumb-sized target, and fixed so a press moves nothing. */
    val SeekBarHeight = MinTouchTarget

    // --- focus, on the television ---------------------------------------------------------------

    /** A focused card grows just enough to lift off the row. No glow: the border says it already. */
    const val FocusScale = 1.06f
    val FocusBorder = 3.dp
}
