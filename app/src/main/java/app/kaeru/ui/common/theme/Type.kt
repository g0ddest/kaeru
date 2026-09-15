package app.kaeru.ui.common.theme

import androidx.compose.material3.Typography
import androidx.compose.ui.text.ExperimentalTextApi
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontVariation
import androidx.compose.ui.text.font.FontWeight
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

private fun manrope(weight: FontWeight, size: Int, lineHeight: Int, letterSpacing: Double = 0.0) = TextStyle(
    fontFamily = Manrope,
    fontWeight = weight,
    fontSize = size.sp,
    lineHeight = lineHeight.sp,
    letterSpacing = letterSpacing.sp,
)

/**
 * One type scale, written once and handed to both Material and tv-Material.
 *
 * The design system names five roles; they live at the Material names below so that
 * `MaterialTheme.typography` stays the only place a screen reads type from:
 *
 * | role     | Material name    | phone                 | used by                            |
 * |----------|------------------|-----------------------|------------------------------------|
 * | display  | `displaySmall`   | 34/40 ExtraBold, −0.8 | the hero title, nothing else       |
 * | headline | `headlineMedium` | 24/30 Bold, −0.4      | screen titles, state titles        |
 * | title    | `titleMedium`    | 17/22 SemiBold        | row headers, buttons               |
 * |          | `titleSmall`     | 15/20 SemiBold        | card titles, status pills          |
 * | body     | `bodyMedium`     | 15/22 Regular         | descriptions, empty and error text |
 * | label    | `labelMedium`    | 13/18 SemiBold        | badges, chips, metadata            |
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
    displayLarge = manrope(FontWeight.ExtraBold, 52, 60, -1.2),
    displayMedium = manrope(FontWeight.ExtraBold, 42, 48, -1.0),
    displaySmall = manrope(FontWeight.ExtraBold, 34, 40, -0.8),
    headlineLarge = manrope(FontWeight.Bold, 28, 34, -0.5),
    headlineMedium = manrope(FontWeight.Bold, 24, 30, -0.4),
    headlineSmall = manrope(FontWeight.Bold, 20, 26, -0.2),
    titleLarge = manrope(FontWeight.SemiBold, 20, 26),
    titleMedium = manrope(FontWeight.SemiBold, 17, 22),
    titleSmall = manrope(FontWeight.SemiBold, 15, 20),
    bodyLarge = manrope(FontWeight.Normal, 16, 24),
    bodyMedium = manrope(FontWeight.Normal, 15, 22),
    bodySmall = manrope(FontWeight.Normal, 13, 18),
    labelLarge = manrope(FontWeight.SemiBold, 15, 20),
    labelMedium = manrope(FontWeight.SemiBold, 13, 18),
    labelSmall = manrope(FontWeight.SemiBold, 11, 16),
)

/** The same scale at ×1.35 — the distance between a phone in the hand and a television across the room. */
private val TvRoles = KaeruRoles(
    displayLarge = manrope(FontWeight.ExtraBold, 70, 80, -1.6),
    displayMedium = manrope(FontWeight.ExtraBold, 57, 65, -1.3),
    displaySmall = manrope(FontWeight.ExtraBold, 46, 54, -1.0),
    headlineLarge = manrope(FontWeight.Bold, 38, 46, -0.7),
    headlineMedium = manrope(FontWeight.Bold, 32, 40, -0.5),
    headlineSmall = manrope(FontWeight.Bold, 27, 34, -0.3),
    titleLarge = manrope(FontWeight.SemiBold, 27, 34),
    titleMedium = manrope(FontWeight.SemiBold, 23, 30),
    titleSmall = manrope(FontWeight.SemiBold, 20, 26),
    bodyLarge = manrope(FontWeight.Normal, 22, 32),
    bodyMedium = manrope(FontWeight.Normal, 20, 30),
    bodySmall = manrope(FontWeight.Normal, 18, 26),
    labelLarge = manrope(FontWeight.SemiBold, 20, 26),
    labelMedium = manrope(FontWeight.SemiBold, 18, 24),
    labelSmall = manrope(FontWeight.SemiBold, 15, 20),
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
