package app.kaeru.ui.mobile.player

import androidx.compose.ui.test.junit4.createComposeRule
import androidx.media3.common.util.UnstableApi
import app.kaeru.ui.common.player.PlayerUiState
import app.kaeru.ui.common.together.ShareRequest
import app.kaeru.ui.common.together.TogetherPhase
import app.kaeru.ui.common.together.TogetherUiState
import app.kaeru.ui.mobile.together.TogetherControls
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The invitation leaving the app, and the one thing that has to happen before it does.
 *
 * The chooser is a window of the system's: it sends this task to the background with the episode
 * still playing, and on Android 12 and later that is exactly when the platform folds the player
 * into a floating window from the parameters it was last given. A host who picked a messenger to
 * send the link through would arrive in it behind their own episode.
 */
@OptIn(UnstableApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w800dp-h360dp-land-notnight-mdpi")
class PlayerScreenSharePromptTest {

    @get:Rule val compose = createComposeRule()

    private val playing = PlayerUiState(
        title = "Фрирен",
        episode = 4,
        isPlaying = true,
        isBuffering = false,
        positionMs = 60_000,
        durationMs = 1_440_000,
    )

    @Test
    fun `the invitation says a question is going up before it leaves the app`() {
        val order = mutableListOf<String>()

        compose.setContent {
            PlayerScreen(
                state = playing,
                player = null,
                onBack = {},
                onTogglePlayPause = {},
                onSeekTo = {},
                onSeekBy = {},
                onSkipIntro = {},
                onNext = {},
                onCancelAutoplay = {},
                onOpenTranslations = {},
                onOpenQualities = {},
                onCloseSheet = {},
                onPickTranslation = {},
                onPickQuality = {},
                onRememberQuality = {},
                onPickEpisode = {},
                onRetry = {},
                onStopCasting = {},
                onConfirmCompleted = {},
                onDismissCompleted = {},
                onToastShown = {},
                onDownload = {},
                onRemoveDownload = {},
                onRemoveBrokenDownload = {},
            onBackToEpisodes = {},
                together = TogetherControls(
                    state = TogetherUiState(
                        phase = TogetherPhase.HOSTING,
                        share = ShareRequest("Смотрим «Фрирен», 4 серия", "https://kaeru/r/abc"),
                    ),
                    onSystemPrompt = { up -> order += if (up) "prompt" else "answered" },
                    onShareShown = { order += "shared" },
                    enabled = true,
                ),
            )
        }
        compose.waitForIdle()

        assertEquals(listOf("prompt", "shared"), order)
    }
}
