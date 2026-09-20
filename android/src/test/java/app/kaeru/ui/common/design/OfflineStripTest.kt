package app.kaeru.ui.common.design

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.width
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.unit.dp
import app.kaeru.ui.common.theme.KaeruTheme
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The two shapes one line of notice comes in.
 *
 * `compact` was added for the television, where everything above the rows has to come to a fixed
 * height and a second line would be drawn over a poster card. It is a height budget and nothing
 * else — same words, same colour, same ground — so what is worth pinning is the height.
 *
 * **What is deliberately not asserted here: that the phone's sentence wraps.** The strip holds
 * `maxLines` to one only when compact, precisely so the longer phone sentence is never clipped to
 * «Нет сети — доступны скачанны…». Robolectric cannot see that: its synthetic font measures a
 * string twelve times too long for the box at exactly one line's height, and the measured height
 * does not move when the width is cut by five. `TvLayoutBudgetTest` says the same thing about the
 * same harness. Wrapping is a device check.
 */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w360dp-h640dp-notnight-mdpi")
class OfflineStripTest {
    @get:Rule val compose = createComposeRule()

    @Before
    fun show() = compose.setContent {
        KaeruTheme {
            Column {
                Box(Modifier.width(Wide)) {
                    OfflineStrip(Modifier.testTag(PHONE), OFFLINE_WITH_DOWNLOADS)
                }
                Box(Modifier.width(Wide)) {
                    OfflineStrip(Modifier.testTag(TV_WIDE), OFFLINE, compact = true)
                }
                Box(Modifier.width(Narrow)) {
                    OfflineStrip(Modifier.testTag(TV_NARROW), OFFLINE, compact = true)
                }
            }
        }
    }

    private fun height(tag: String): Int = compose.onNodeWithTag(tag).fetchSemanticsNode().size.height

    /**
     * The only thing `compact` changes is the air, and it changes it by a known amount. Measured as
     * a difference rather than as a number, so the assertion does not move with the type scale or
     * with whatever face the harness happens to be measuring in.
     */
    @Test
    fun `the compact strip gives up exactly the air the phone keeps`() {
        val air = 2 * (KaeruTokens.Space3.value - KaeruTokens.Space1.value)

        assertEquals(air.toInt(), height(PHONE) - height(TV_WIDE))
    }

    /** The whole point of it: a height the television layout can budget for, whatever the width. */
    @Test
    fun `the compact strip is the same height however narrow it gets`() {
        assertEquals(height(TV_WIDE), height(TV_NARROW))
    }

    private companion object {
        val Wide = 360.dp
        val Narrow = 72.dp

        const val PHONE = "phone"
        const val TV_WIDE = "tv-wide"
        const val TV_NARROW = "tv-narrow"
    }
}
