package app.kaeru.ui.mobile.player

import androidx.activity.ComponentActivity
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.media3.common.util.UnstableApi
import app.kaeru.domain.model.Quality
import app.kaeru.ui.common.player.LocalCastAvailable
import app.kaeru.ui.common.player.PlayerUiState
import app.kaeru.ui.common.theme.KaeruTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * The player the phone draws when it has the picture itself, measured the same way.
 *
 * The first round of this work measured it once by hand, found 8dp of room at the bottom and left
 * it at that. Eight is the floor, which is another way of saying there is nothing left: the next
 * line of type, the next inset or the next control in the top bar puts this screen where the
 * remote already was. So it gets a standing guard rather than a number in a report, at both
 * shapes and both sizes of type.
 *
 * The episode is paused in every case on purpose. The controls linger for three seconds of
 * *uninterrupted playback* and then fade, and a faded screen measures nothing; paused, they stay
 * up, which is the state a viewer looking at the timeline is in anyway.
 */
@OptIn(UnstableApi::class)
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = LANDSCAPE)
class PlayerRenderBudgetTest {

    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    @Test
    @Config(qualifiers = LANDSCAPE)
    fun `the player fits a landscape phone`() {
        show(paused)
        compose.screen("player-land").fitsThePhone()
    }

    @Test
    @Config(qualifiers = LANDSCAPE, fontScale = BIG_TYPE)
    fun `the player fits a landscape phone in big type`() {
        show(paused)
        compose.screen("player-land-big").fitsThePhone()
    }

    @Test
    @Config(qualifiers = PORTRAIT)
    fun `the player fits a portrait phone`() {
        show(paused)
        compose.screen("player-port").fitsThePhone()
    }

    @Test
    @Config(qualifiers = PORTRAIT, fontScale = BIG_TYPE)
    fun `the player fits a portrait phone in big type`() {
        show(paused)
        compose.screen("player-port-big").fitsThePhone()
    }

    /** Everything the top bar can carry at once, over an episode that has a next one to go to. */
    private val paused = PlayerUiState(
        title = "Фрирен, провожающая в последний путь",
        episode = 7,
        availableEpisodes = 24,
        translationTitle = "AniLibria.TV",
        translationId = 1,
        positionMs = 600_000,
        bufferedPositionMs = 900_000,
        durationMs = 1_440_000,
        quality = Quality.P1080,
        qualities = listOf(Quality.P480, Quality.P720, Quality.P1080),
        nextEpisodeAvailable = true,
        isPlaying = false,
        isBuffering = false,
    )

    private fun show(state: PlayerUiState) {
        compose.dressedAsTheApp()
        compose.setContent {
            KaeruTheme {
                CompositionLocalProvider(LocalCastAvailable provides true) {
                    PlayerScreen(
                        state = state,
                        player = null,
                        onBack = {},
                        onTogglePlayPause = {},
                        onSeekTo = {},
                        onSeekBy = {},
                        onSkipIntro = {},
                        onSkip = {},
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
                        // The window button is only there where there is a picture to fold into
                        // one, which is every case here.
                        onEnterPictureInPicture = {},
                    )
                }
            }
        }
    }
}
