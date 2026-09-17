package app.kaeru.ui.tv.player

import androidx.compose.foundation.layout.Row
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import app.kaeru.ui.common.theme.KaeruTvTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * A voice that does not carry the episode is drawn dimmed and captioned «нет серии N», and its
 * press does nothing — but a chip the D-pad cannot land on is a hole in the row, so it stays
 * focusable. Both halves of that have to reach a viewer who is listening to the screen rather
 * than looking at it: the colour and the caption say nothing to TalkBack, and a chip that only
 * announced itself as a button would be a promise the press does not keep.
 */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w960dp-h540dp-television-notnight-mdpi")
class TvStripChipStateTest {
    @get:Rule val compose = createComposeRule()

    private fun chip(enabled: Boolean) {
        compose.setContent {
            KaeruTvTheme {
                Row {
                    TvStripChip(
                        label = "Студийная банда",
                        onClick = {},
                        caption = if (enabled) null else "нет серии 12",
                        enabled = enabled,
                        modifier = Modifier.testTag("chip"),
                    )
                }
            }
        }
    }

    @Test
    fun `a voice that lacks the episode says so to a viewer who cannot see it`() {
        chip(enabled = false)

        compose.onNodeWithTag("chip").assertIsNotEnabled()
        // Still in the walk: the row would otherwise have a gap the remote falls through.
        compose.onNodeWithTag("chip").assert(SemanticsMatcher.keyIsDefined(SemanticsProperties.Focused))
    }

    @Test
    fun `a voice that carries it is an ordinary chip`() {
        chip(enabled = true)

        compose.onNodeWithTag("chip").assertIsEnabled()
    }
}
