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
 * The same scale at ×1.35 — the distance between a phone in the hand and a television across the
 * room.
 *
 * With one difference beyond the size: nothing here is tracked tighter than the font draws it.
 * Negative tracking is a phone-sized decision, made so a long title fits the hand; at three metres
 * it pulls the glyphs into each other, and on the largest styles it pulls the first and last of
 * them into the edge of whatever box holds the line.
 */
private val TvRoles = KaeruRoles(
    displayLarge = manrope(FontWeight.ExtraBold, 70, 98),
    displayMedium = manrope(FontWeight.ExtraBold, 57, 80),
    displaySmall = manrope(FontWeight.ExtraBold, 46, 65),
    headlineLarge = manrope(FontWeight.Bold, 38, 54),
    headlineMedium = manrope(FontWeight.Bold, 32, 45),
    headlineSmall = manrope(FontWeight.Bold, 27, 38),
    titleLarge = manrope(FontWeight.SemiBold, 27, 38),
    titleMedium = manrope(FontWeight.SemiBold, 23, 33),
    titleSmall = manrope(FontWeight.SemiBold, 20, 28),
    bodyLarge = manrope(FontWeight.Normal, 22, 32),
    bodyMedium = manrope(FontWeight.Normal, 20, 30),
    bodySmall = manrope(FontWeight.Normal, 18, 26),
    labelLarge = manrope(FontWeight.SemiBold, 20, 28),
    labelMedium = manrope(FontWeight.SemiBold, 18, 26),
    labelSmall = manrope(FontWeight.SemiBold, 15, 21),
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
