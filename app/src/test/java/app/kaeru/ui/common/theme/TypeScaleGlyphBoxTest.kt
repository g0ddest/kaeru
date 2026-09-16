package app.kaeru.ui.common.theme

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.material3.Typography
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.TextUnit
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import androidx.tv.material3.Typography as TvTypography

/**
 * Every style of both scales, drawn and measured.
 *
 * «Друзья Ярче» is the string because it carries both halves of the fault in six words: «Д» and
 * «Я» reach the cap height, «у», «р» and «ф» hang below the baseline, and «ч» does both. A line box
 * smaller than that is a line that loses letters.
 *
 * The assertion is the one invariant that holds whatever font the runtime has: **a style's line box
 * is never smaller than the box the same font draws itself in.** That is what was broken —
 * `manrope.ttf` draws itself in [MANROPE_BOX] of the font size and the scale declared as little as
 * 1.14 — and it is checked here by measuring each style against the same string in the same style
 * with the line height taken off, which is the font's own box by definition.
 *
 * It is worth saying plainly what this test cannot do: Robolectric has no Manrope. It measures with
 * a synthetic face whose metrics do not vary with the font size at all, so the *numbers* here are
 * not the device's. What carries across is the relation — Compose must never hand a paragraph less
 * box than the font needs, nor less than the style asked for — and `TypeScaleTest` holds the
 * declared ratios against Manrope's real metrics, read off the file.
 */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w960dp-h540dp-television-notnight-mdpi")
class TypeScaleGlyphBoxTest {
    @get:Rule val compose = createComposeRule()

    /** Ascenders, descenders and a capital with a tail, in one line of Russian. */
    private val specimen = "Друзья Ярче"

    private fun Typography.styles(): List<Pair<String, TextStyle>> = listOf(
        "displayLarge" to displayLarge, "displayMedium" to displayMedium, "displaySmall" to displaySmall,
        "headlineLarge" to headlineLarge, "headlineMedium" to headlineMedium, "headlineSmall" to headlineSmall,
        "titleLarge" to titleLarge, "titleMedium" to titleMedium, "titleSmall" to titleSmall,
        "bodyLarge" to bodyLarge, "bodyMedium" to bodyMedium, "bodySmall" to bodySmall,
        "labelLarge" to labelLarge, "labelMedium" to labelMedium, "labelSmall" to labelSmall,
    )

    private fun TvTypography.styles(): List<Pair<String, TextStyle>> = listOf(
        "displayLarge" to displayLarge, "displayMedium" to displayMedium, "displaySmall" to displaySmall,
        "headlineLarge" to headlineLarge, "headlineMedium" to headlineMedium, "headlineSmall" to headlineSmall,
        "titleLarge" to titleLarge, "titleMedium" to titleMedium, "titleSmall" to titleSmall,
        "bodyLarge" to bodyLarge, "bodyMedium" to bodyMedium, "bodySmall" to bodySmall,
        "labelLarge" to labelLarge, "labelMedium" to labelMedium, "labelSmall" to labelSmall,
    )

    private val scales: List<Pair<String, List<Pair<String, TextStyle>>>> = listOf(
        "phone" to KaeruTypography.styles(),
        "television" to KaeruTvTypography.styles(),
    )

    /** The same style with nothing said about the line: the box the font draws itself in. */
    private fun TextStyle.natural() = copy(lineHeight = TextUnit.Unspecified, lineHeightStyle = null)

    private fun show() {
        compose.setContent {
            // Scrolled, so the column is measured with unbounded height and every specimen below
            // the fold is measured properly instead of being handed what is left of the panel.
            Column(Modifier.verticalScroll(rememberScrollState())) {
                scales.forEach { (scale, styles) ->
                    styles.forEach { (name, style) ->
                        Text(specimen, style = style, modifier = Modifier.testTag("$scale-$name"))
                        Text(specimen, style = style.natural(), modifier = Modifier.testTag("$scale-$name-font"))
                    }
                }
            }
        }
        compose.waitForIdle()
    }

    private fun heightOf(tag: String): Int = compose.onNodeWithTag(tag).fetchSemanticsNode().size.height

    @Test
    fun `no style draws its line in a box smaller than the font's own`() {
        show()

        scales.forEach { (scale, styles) ->
            styles.forEach { (name, _) ->
                val drawn = heightOf("$scale-$name")
                val font = heightOf("$scale-$name-font")
                assertTrue(
                    "$scale $name draws «$specimen» in $drawn where the font itself wants $font",
                    drawn >= font,
                )
            }
        }
    }

    @Test
    fun `no style is handed less line box than it asked for`() {
        show()

        scales.forEach { (scale, styles) ->
            styles.forEach { (name, style) ->
                val drawn = heightOf("$scale-$name")
                val asked = style.lineHeight.value
                assertTrue(
                    "$scale $name asked for $asked and was drawn in $drawn",
                    drawn >= asked,
                )
            }
        }
    }
}
