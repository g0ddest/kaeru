package app.kaeru.ui.common.theme

import androidx.compose.material3.Typography
import androidx.compose.ui.text.ExperimentalTextApi
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontVariation
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.LineHeightStyle
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Typography as TvTypography
import app.kaeru.R

private val ManropeWeights = listOf(
    FontWeight.Normal, FontWeight.Medium, FontWeight.SemiBold, FontWeight.Bold, FontWeight.ExtraBold,
)

/**
 * `manrope.ttf` is a variable font. Registering it once would leave every weight above Normal to
 * synthetic emboldening, so each weight the type scale uses gets its own instance pinned through
 * the `wght` axis (supported from API 26, the app's minSdk).
 */
@OptIn(ExperimentalTextApi::class)
val Manrope = FontFamily(
    ManropeWeights.map { weight ->
        Font(
            resId = R.font.manrope,
            weight = weight,
            variationSettings = FontVariation.Settings(FontVariation.weight(weight.weight)),
        )
    },
)

/**
 * How much line box one line of Manrope needs, as a multiple of the font size.
 *
 * Read off `manrope.ttf` rather than assumed: `unitsPerEm` 2000, `hhea` ascender 2132 and descender
 * −600, and `OS/2` agreeing with both (`sTypo` the same pair, `usWin` 2132/600, `USE_TYPO_METRICS`
 * set). (2132 + 600) / 2000 is [MANROPE_BOX], and that is the height the font draws itself in
 * before any leading at all. Most Latin faces sit near 1.2; Manrope is unusually tall, and that is
 * the whole of why the app was clipping letters.
 *
 * Every line height in the scale below is at least [MIN_LINE_RATIO] of its font size — comfortably
 * over the box, so the glyphs fit with room to spare rather than exactly. `TypeScaleTest` holds the
 * line, and the rule is a fact about the font file: change the family and this number changes.
 */
internal const val MANROPE_BOX = 1.366

/** The bar every style clears, chosen above [MANROPE_BOX] so a line has leading and not only room. */
internal const val MIN_LINE_RATIO = 1.4

/**
 * One style of the scale.
 *
 * Two things decide whether a letter survives its line, and only one of them is a setting.
 *
 * The first is the line height, and it is the one that was wrong. Compose draws a line inside the
 * box the style declares; anything the font would draw outside that box — the tails of «у», «р»,
 * «д» and «ф», the breve of «й» — is not painted. The scale used to ask for 1.14 to 1.50 of the
 * font size while Manrope needs [MANROPE_BOX] to draw itself, so twenty-two of the thirty styles
 * declared a line box smaller than their own glyphs. Every one of them now clears
 * [MIN_LINE_RATIO].
 *
 * The second is [LineHeightStyle], which decides where the leading goes. Compose's default trims it
 * off the top of the first line and the bottom of the last — exactly where it is needed, since the
 * last line of a card's caption is the one whose descenders meet the edge of the card. Centred and
 * untrimmed, every line of every paragraph gets the same room above and below it, and a
 * single-line label gets exactly the box it asked for.
 *
 * Deliberately *not* here: `PlatformTextStyle(includeFontPadding = true)`. It buys nothing for this
 * font — `usWinAscent`/`usWinDescent` are the same pair as `hhea`, so there is no padding to put
 * back — and `LineHeightStyle`'s own contract says trimming applies only while font padding is off.
 * With the line heights right, the supported pair is the one that is also the correct one.
 */
private fun manrope(weight: FontWeight, size: Int, lineHeight: Int, letterSpacing: Double = 0.0) = TextStyle(
    fontFamily = Manrope,
    fontWeight = weight,
    fontSize = size.sp,
    lineHeight = lineHeight.sp,
    letterSpacing = letterSpacing.sp,
    lineHeightStyle = LineHeightStyle(
        alignment = LineHeightStyle.Alignment.Center,
        trim = LineHeightStyle.Trim.None,
    ),
)

/**
 * One type scale, written once and handed to both Material and tv-Material.
 *
 * The design system names five roles; they live at the Material names below so that
 * `MaterialTheme.typography` stays the only place a screen reads type from:
 *
 * | role     | Material name    | phone                 | used by                            |
 * |----------|------------------|-----------------------|------------------------------------|
 * | display  | `displaySmall`   | 34/48 ExtraBold, −0.8 | the hero title, nothing else       |
 * | headline | `headlineMedium` | 24/34 Bold, −0.4      | screen titles, state titles        |
 * | title    | `titleMedium`    | 17/24 SemiBold        | row headers, buttons               |
 * |          | `titleSmall`     | 15/21 SemiBold        | card titles, status pills          |
 * | body     | `bodyMedium`     | 15/22 Regular         | descriptions, empty and error text |
 * | label    | `labelMedium`    | 13/19 SemiBold        | badges, chips, metadata            |
 *
 * The neighbouring roles are filled in too: an unset one silently falls back to the system font.
 */
private class KaeruRoles(
    val displayLarge: TextStyle,
    val displayMedium: TextStyle,
    val displaySmall: TextStyle,
    val headlineLarge: TextStyle,
    val headlineMedium: TextStyle,
    val headlineSmall: TextStyle,
    val titleLarge: TextStyle,
    val titleMedium: TextStyle,
    val titleSmall: TextStyle,
    val bodyLarge: TextStyle,
    val bodyMedium: TextStyle,
    val bodySmall: TextStyle,
    val labelLarge: TextStyle,
    val labelMedium: TextStyle,
    val labelSmall: TextStyle,
)

private fun KaeruRoles.toMaterial() = Typography(
    displayLarge = displayLarge, displayMedium = displayMedium, displaySmall = displaySmall,
    headlineLarge = headlineLarge, headlineMedium = headlineMedium, headlineSmall = headlineSmall,
    titleLarge = titleLarge, titleMedium = titleMedium, titleSmall = titleSmall,
    bodyLarge = bodyLarge, bodyMedium = bodyMedium, bodySmall = bodySmall,
    labelLarge = labelLarge, labelMedium = labelMedium, labelSmall = labelSmall,
)

private fun KaeruRoles.toTv() = TvTypography(
    displayLarge = displayLarge, displayMedium = displayMedium, displaySmall = displaySmall,
    headlineLarge = headlineLarge, headlineMedium = headlineMedium, headlineSmall = headlineSmall,
    titleLarge = titleLarge, titleMedium = titleMedium, titleSmall = titleSmall,
    bodyLarge = bodyLarge, bodyMedium = bodyMedium, bodySmall = bodySmall,
    labelLarge = labelLarge, labelMedium = labelMedium, labelSmall = labelSmall,
)

private val PhoneRoles = KaeruRoles(
    displayLarge = manrope(FontWeight.ExtraBold, 52, 73, -1.2),
    displayMedium = manrope(FontWeight.ExtraBold, 42, 59, -1.0),
    displaySmall = manrope(FontWeight.ExtraBold, 34, 48, -0.8),
    headlineLarge = manrope(FontWeight.Bold, 28, 40, -0.5),
    headlineMedium = manrope(FontWeight.Bold, 24, 34, -0.4),
    headlineSmall = manrope(FontWeight.Bold, 20, 28, -0.2),
    titleLarge = manrope(FontWeight.SemiBold, 20, 28),
    titleMedium = manrope(FontWeight.SemiBold, 17, 24),
    titleSmall = manrope(FontWeight.SemiBold, 15, 21),
    bodyLarge = manrope(FontWeight.Normal, 16, 24),
    bodyMedium = manrope(FontWeight.Normal, 15, 22),
    bodySmall = manrope(FontWeight.Normal, 13, 19),
    labelLarge = manrope(FontWeight.SemiBold, 15, 21),
    labelMedium = manrope(FontWeight.SemiBold, 13, 19),
    labelSmall = manrope(FontWeight.SemiBold, 11, 16),
)

/**
 * The same scale across the room: about a fifth larger than the phone's, and no larger than that.
 *
 * It used to be ×1.35, which is the multiplier a television deserves and not one a 540dp panel can
 * pay. A 1080p television reports 960×540dp — a third of the height a phone has and the same
 * height a phone has in landscape — and everything a screen carries is measured in line boxes: a
 * hero band is two of them, a card's caption is one, a row's heading is one more above the card.
 * At ×1.35 the home screen's rows wanted 36dp more than the panel had, the pairing column 22, and
 * the title card's left column 103. Rendered at the television's own qualifiers, the three screens
 * came back cut.
 *
 * So the numbers below are what fits, worked back from the panel rather than forward from the
 * phone: `displaySmall` at 40/56 is the hero band's one line, `titleMedium` at 21/30 is a row
 * heading and a button, `titleSmall` at 18/26 is a card's name under its artwork. Every one of
 * them is still well above the 24sp a television interface is meant to reach for body text at
 * three metres, and every one of them clears Manrope's own line box with [MIN_LINE_RATIO] to
 * spare — `TvRenderBudgetTest` measures what these sizes actually draw, on every television
 * screen, at the size a television draws them.
 *
 * With one difference beyond the size: nothing here is tracked tighter than the font draws it.
 * Negative tracking is a phone-sized decision, made so a long title fits the hand; at three metres
 * it pulls the glyphs into each other, and on the largest styles it pulls the first and last of
 * them into the edge of whatever box holds the line.
 */
private val TvRoles = KaeruRoles(
    displayLarge = manrope(FontWeight.ExtraBold, 56, 79),
    displayMedium = manrope(FontWeight.ExtraBold, 48, 68),
    displaySmall = manrope(FontWeight.ExtraBold, 40, 56),
    headlineLarge = manrope(FontWeight.Bold, 34, 48),
    headlineMedium = manrope(FontWeight.Bold, 28, 40),
    headlineSmall = manrope(FontWeight.Bold, 24, 34),
    titleLarge = manrope(FontWeight.SemiBold, 24, 34),
    titleMedium = manrope(FontWeight.SemiBold, 21, 30),
    titleSmall = manrope(FontWeight.SemiBold, 18, 26),
    bodyLarge = manrope(FontWeight.Normal, 20, 28),
    bodyMedium = manrope(FontWeight.Normal, 18, 26),
    bodySmall = manrope(FontWeight.Normal, 16, 23),
    labelLarge = manrope(FontWeight.SemiBold, 18, 26),
    labelMedium = manrope(FontWeight.SemiBold, 16, 23),
    labelSmall = manrope(FontWeight.SemiBold, 14, 20),
)

val KaeruTypography = PhoneRoles.toMaterial()

/** The TV type scale is its own class; without this, `tv.material3` text uses the system font. */
val KaeruTvTypography = TvRoles.toTv()

/**
 * The TV scale as a Material typography, so the components shared between phone and television —
 * buttons, chips, row headers, empty and error states — grow with everything else on a TV instead
 * of staying at phone size inside a TV screen.
 */
val KaeruTvMaterialTypography = TvRoles.toMaterial()
