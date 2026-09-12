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

private fun manrope(weight: FontWeight, size: Int, letterSpacing: Double = 0.0) = TextStyle(
    fontFamily = Manrope, fontWeight = weight, fontSize = size.sp, letterSpacing = letterSpacing.sp,
)

private fun TextStyle.manrope(weight: FontWeight) = copy(fontFamily = Manrope, fontWeight = weight)

private val MaterialDefaults = Typography()

/** Every role is defined: an unset one silently falls back to the system font. */
val KaeruTypography = Typography(
    displayLarge = MaterialDefaults.displayLarge.manrope(FontWeight.Bold),
    displayMedium = MaterialDefaults.displayMedium.manrope(FontWeight.Bold),
    displaySmall = manrope(FontWeight.Bold, 36, -0.5),
    headlineLarge = MaterialDefaults.headlineLarge.manrope(FontWeight.Bold),
    headlineMedium = manrope(FontWeight.Bold, 26, -0.25),
    headlineSmall = MaterialDefaults.headlineSmall.manrope(FontWeight.SemiBold),
    titleLarge = manrope(FontWeight.SemiBold, 20),
    titleMedium = manrope(FontWeight.SemiBold, 16),
    titleSmall = MaterialDefaults.titleSmall.manrope(FontWeight.SemiBold),
    bodyLarge = manrope(FontWeight.Normal, 16),
    bodyMedium = manrope(FontWeight.Normal, 14),
    bodySmall = MaterialDefaults.bodySmall.manrope(FontWeight.Normal),
    labelLarge = manrope(FontWeight.Bold, 14),
    labelMedium = MaterialDefaults.labelMedium.manrope(FontWeight.Medium),
    labelSmall = MaterialDefaults.labelSmall.manrope(FontWeight.Medium),
)

/** The TV type scale is its own class; without this, `tv.material3` text uses the system font. */
val KaeruTvTypography = TvTypography().run {
    copy(
        displayLarge = displayLarge.manrope(FontWeight.ExtraBold),
        displayMedium = displayMedium.manrope(FontWeight.Bold),
        displaySmall = displaySmall.manrope(FontWeight.Bold),
        headlineLarge = headlineLarge.manrope(FontWeight.Bold),
        headlineMedium = headlineMedium.manrope(FontWeight.Bold),
        headlineSmall = headlineSmall.manrope(FontWeight.SemiBold),
        titleLarge = titleLarge.manrope(FontWeight.SemiBold),
        titleMedium = titleMedium.manrope(FontWeight.SemiBold),
        titleSmall = titleSmall.manrope(FontWeight.SemiBold),
        bodyLarge = bodyLarge.manrope(FontWeight.Normal),
        bodyMedium = bodyMedium.manrope(FontWeight.Normal),
        bodySmall = bodySmall.manrope(FontWeight.Normal),
        labelLarge = labelLarge.manrope(FontWeight.Bold),
        labelMedium = labelMedium.manrope(FontWeight.Medium),
        labelSmall = labelSmall.manrope(FontWeight.Medium),
    )
}
