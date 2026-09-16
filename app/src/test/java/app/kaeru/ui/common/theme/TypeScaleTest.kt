package app.kaeru.ui.common.theme

import androidx.compose.material3.Typography
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.LineHeightStyle
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import androidx.tv.material3.Typography as TvTypography

/**
 * The type scale, measured against the font rather than against a habit.
 *
 * Letters were being cut off along the bottom of a line and, here and there, along the side of a
 * box, and the cause is in `manrope.ttf`: `unitsPerEm` 2000 with an `hhea` ascender of 2132 and a
 * descender of −600, which the `OS/2` table agrees with on every metric it publishes. The font
 * draws itself in [MANROPE_BOX] of the font size — where most Latin faces want about 1.2 — and a
 * scale built on the usual ratios therefore asked for a line box smaller than the glyphs. What
 * falls outside the box is not painted, and what falls outside it in Cyrillic is «у», «р», «д»,
 * «ф» and the breve of «й».
 *
 * So the bar here is the font's own box with room on top, not a number borrowed from a design
 * system built for a shorter face. Change the family and [MANROPE_BOX] changes with it.
 */
class TypeScaleTest {

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

    private fun everyScale(): Map<String, Map<String, TextStyle>> = mapOf(
        "phone" to KaeruTypography.styles(),
        "television" to KaeruTvTypography.styles(),
        "television material" to KaeruTvMaterialTypography.styles(),
    )

    @Test
    fun `no line box is smaller than the glyphs Manrope draws in it`() {
        everyScale().forEach { (scale, styles) ->
            styles.forEach { (name, style) ->
                val ratio = style.lineHeight.value.toDouble() / style.fontSize.value.toDouble()
                assertTrue(
                    "$scale $name is ${style.fontSize.value}/${style.lineHeight.value}, a ratio of $ratio, " +
                        "and Manrope needs $MANROPE_BOX",
                    ratio >= MANROPE_BOX,
                )
            }
        }
    }

    /** And with room on top of the box, so a line has leading and not only clearance. */
    @Test
    fun `every style leaves room above the font's own box`() {
        everyScale().forEach { (scale, styles) ->
            styles.forEach { (name, style) ->
                val ratio = style.lineHeight.value.toDouble() / style.fontSize.value.toDouble()
                assertTrue(
                    "$scale $name is ${style.fontSize.value}/${style.lineHeight.value}, a ratio of $ratio",
                    ratio >= MIN_LINE_RATIO,
                )
            }
        }
    }

    @Test
    fun `every style spends its line height above and below the text rather than trimming it`() {
        everyScale().forEach { (scale, styles) ->
            styles.forEach { (name, style) ->
                assertEquals(
                    "$scale $name spends its line height evenly and trims nothing",
                    LineHeightStyle(LineHeightStyle.Alignment.Center, LineHeightStyle.Trim.None),
                    style.lineHeightStyle,
                )
            }
        }
    }

    /**
     * `LineHeightStyle`'s own contract is that trimming applies only while the platform's font
     * padding is off, and Manrope has no padding to put back — `usWinAscent`/`usWinDescent` are the
     * same pair as `hhea`. Setting it would be a combination the library does not promise to keep
     * working, bought for nothing.
     */
    @Test
    fun `no style asks for the platform's font padding`() {
        everyScale().forEach { (scale, styles) ->
            styles.forEach { (name, style) ->
                assertEquals("$scale $name leaves the platform style alone", null, style.platformStyle)
            }
        }
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
