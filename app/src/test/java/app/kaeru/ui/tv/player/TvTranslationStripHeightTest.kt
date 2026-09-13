package app.kaeru.ui.tv.player

import androidx.compose.foundation.layout.Column
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.unit.height
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import app.kaeru.domain.model.Translation
import app.kaeru.domain.model.TranslationKind
import app.kaeru.domain.playback.RankedTranslation
import app.kaeru.ui.common.theme.KaeruTvTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * The voices strip, measured in both of its states.
 *
 * The panel is exactly as tall as its rows, so a row that changes height when its list arrives
 * moves everything above it — on the television the whole panel jumped eight device-independent
 * pixels the moment the voices landed, which on a remote is the panel shifting under a press.
 */
@RunWith(RobolectricTestRunner::class)
class TvTranslationStripHeightTest {
    @get:Rule val compose = createComposeRule()

    private val tracks = listOf(
        RankedTranslation(Translation(1, "AniLibria.TV", TranslationKind.VOICE, episodesCount = 12), true),
        RankedTranslation(Translation(2, "AniDUB", TranslationKind.VOICE, episodesCount = 12), false),
    )

    @Test
    fun `the row is the same height while the voices load as it is once they arrive`() {
        compose.setContent {
            KaeruTvTheme {
                val loadingFocus = remember { FocusRequester() }
                val loadedFocus = remember { FocusRequester() }
                Column {
                    TvTranslationStrip(
                        translations = emptyList(),
                        translationId = null,
                        loading = true,
                        focus = loadingFocus,
                        onPick = {},
                        modifier = Modifier.testTag("loading"),
                    )
                    TvTranslationStrip(
                        translations = tracks,
                        translationId = 1,
                        loading = false,
                        focus = loadedFocus,
                        onPick = {},
                        modifier = Modifier.testTag("loaded"),
                    )
                }
            }
        }

        val loading = compose.onNodeWithTag("loading").getUnclippedBoundsInRoot().height
        val loaded = compose.onNodeWithTag("loaded").getUnclippedBoundsInRoot().height

        assertEquals(loaded.value.toDouble(), loading.value.toDouble(), 0.5)
    }
}
