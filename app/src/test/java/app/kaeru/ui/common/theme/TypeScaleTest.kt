package app.kaeru.ui.common.theme

import androidx.compose.material3.Typography
import androidx.compose.ui.text.PlatformTextStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.LineHeightStyle
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import androidx.tv.material3.Typography as TvTypography

/**
 * The type scale, measured rather than looked at.
 *
 * Letters were being cut off along the bottom of a line and, here and there, along the side of a
 * box. None of it is the font: Compose turns the platform's own font padding off by default, so a
 * line box is exactly the line height and anything the font draws outside it — the tails of «у»,
 * «р», «д», «ф», and the accents Cyrillic capitals carry — is simply not painted. A line height
 * close to the font size makes that certain rather than likely.
 *
 * So three things are asserted of every style on both scales, and they are the three that decide
 * whether a glyph survives: enough room in the line box, the platform padding that keeps what the
 * font asks for, and a line height spent evenly above and below the text rather than trimmed off
 * the first and last lines.
 */
class TypeScaleTest {

    /**
     * Room for a descender, as a multiple of the font size.
     *
     * A fifth of the size is what a text face wants for the parts of it that hang below the
     * baseline and rise above the cap height. Display sizes are allowed less, because at forty-six
     * points a tenth is already more room than a descender needs and the alternative is a hero
     * whose two lines drift apart.
     */
    private val bodyRatio = 1.2
    private val displayRatio = 1.1

    private fun Typography.styles(): Map<String, TextStyle> = mapOf(
        "displayLarge" to displayLarge, "displayMedium" to displayMedium, "displaySmall" to displaySmall,
        "headlineLarge" to headlineLarge, "headlineMedium" to headlineMedium, "headlineSmall" to headlineSmall,
        "titleLarge" to titleLarge, "titleMedium" to titleMedium, "titleSmall" to titleSmall,
        "bodyLarge" to bodyLarge, "bodyMedium" to bodyMedium, "bodySmall" to bodySmall,
        "labelLarge" to labelLarge, "labelMedium" to labelMedium, "labelSmall" to labelSmall,
    )

    private fun TvTypography.styles(): Map<String, TextStyle> = mapOf(
        "displayLarge" to displayLarge, "displayMedium" to displayMedium, "displaySmall" to displaySmall,
        "headlineLarge" to headlineLarge, "headlineMedium" to headlineMedium, "headlineSmall" to headlineSmall,
        "titleLarge" to titleLarge, "titleMedium" to titleMedium, "titleSmall" to titleSmall,
        "bodyLarge" to bodyLarge, "bodyMedium" to bodyMedium, "bodySmall" to bodySmall,
        "labelLarge" to labelLarge, "labelMedium" to labelMedium, "labelSmall" to labelSmall,
    )

    private fun assertLineHeights(scale: String, styles: Map<String, TextStyle>) {
        styles.forEach { (name, style) ->
            val wanted = if (name.startsWith("display")) displayRatio else bodyRatio
            val ratio = style.lineHeight.value / style.fontSize.value
            assertTrue(
                "$scale $name is ${style.fontSize.value}/${style.lineHeight.value}, a ratio of $ratio",
                ratio >= wanted,
            )
        }
    }

    @Suppress("DEPRECATION")
    private fun assertFontPaddingKept(scale: String, styles: Map<String, TextStyle>) {
        styles.forEach { (name, style) ->
            assertEquals(
                "$scale $name keeps the platform's font padding",
                PlatformTextStyle(includeFontPadding = true),
                style.platformStyle,
            )
        }
    }

    private fun assertLineHeightCentred(scale: String, styles: Map<String, TextStyle>) {
        styles.forEach { (name, style) ->
            assertEquals(
                "$scale $name spends its line height evenly and trims nothing",
                LineHeightStyle(LineHeightStyle.Alignment.Center, LineHeightStyle.Trim.None),
                style.lineHeightStyle,
            )
        }
    }

    @Test
    fun `every phone style leaves room for what hangs below the baseline`() {
        assertLineHeights("phone", KaeruTypography.styles())
    }

    @Test
    fun `every television style leaves room for what hangs below the baseline`() {
        assertLineHeights("television", KaeruTvTypography.styles())
        assertLineHeights("television material", KaeruTvMaterialTypography.styles())
    }

    @Test
    fun `every style keeps the font padding that holds the tails of the letters`() {
        assertFontPaddingKept("phone", KaeruTypography.styles())
        assertFontPaddingKept("television", KaeruTvTypography.styles())
        assertFontPaddingKept("television material", KaeruTvMaterialTypography.styles())
    }

    @Test
    fun `every style spends its line height above and below the text rather than trimming it`() {
        assertLineHeightCentred("phone", KaeruTypography.styles())
        assertLineHeightCentred("television", KaeruTvTypography.styles())
        assertLineHeightCentred("television material", KaeruTvMaterialTypography.styles())
    }

    /**
     * Tightening is a phone-sized decision. Across a room it pulls the glyphs into each other and,
     * on the styles a television draws largest, into the edge of whatever box holds them.
     */
    @Test
    fun `no television style pulls its letters together`() {
        (KaeruTvTypography.styles() + KaeruTvMaterialTypography.styles()).forEach { (name, style) ->
            assertTrue(
                "television $name tracks at ${style.letterSpacing.value}",
                style.letterSpacing.value >= 0f,
            )
        }
    }
}
