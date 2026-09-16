package app.kaeru.ui.tv.player

import androidx.compose.foundation.layout.Row
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import app.kaeru.ui.common.theme.KaeruTvTheme
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The episode chip is a box of a fixed height that clips what it holds, and it holds two lines of
 * text. A line that grew — a bigger line height, the font padding a descender needs — would be
 * shaved along the bottom edge rather than making the chip taller, and the viewer would see the
 * tails cut off «серия» and nothing else to say why.
 *
 * So the box is measured against what is in it rather than trusted to be big enough.
 */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w960dp-h540dp-television-notnight-mdpi")
class TvStripChipFitsTest {
    @get:Rule val compose = createComposeRule()

    private val label = "7 серия"
    private val caption = "осталось 14 мин"

    @Test
    fun `both lines of an episode chip are inside the box that clips them`() {
        compose.setContent {
            KaeruTvTheme {
                Row {
                    TvStripChip(
                        label = label,
                        onClick = {},
                        caption = caption,
                        modifier = Modifier.testTag("chip"),
                    )
                }
            }
        }

        val chip = compose.onNodeWithTag("chip").fetchSemanticsNode()
        val top = chip.positionInRoot.y
        val bottom = top + chip.size.height
        listOf(label, caption).forEach { text ->
            val line = compose.onNodeWithText(text, useUnmergedTree = true).fetchSemanticsNode()
            val lineTop = line.positionInRoot.y
            val lineBottom = lineTop + line.size.height
            assertTrue(
                "«$text» runs from $lineTop to $lineBottom inside a chip of $top..$bottom",
                lineTop >= top && lineBottom <= bottom,
            )
        }
    }
}
