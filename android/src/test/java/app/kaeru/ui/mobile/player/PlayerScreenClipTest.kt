package app.kaeru.ui.mobile.player

import androidx.compose.ui.test.junit4.createComposeRule
import androidx.media3.common.util.UnstableApi
import app.kaeru.domain.together.VoicePlayback
import app.kaeru.ui.common.player.PlayerUiState
import app.kaeru.ui.common.together.PlayingClip
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
 * A friend talking over the episode, with the picture folded into a floating window.
 *
 * The window is a few centimetres of video with no controls on it, and everything the player
 * draws is left out of it — but a shared viewing carries on underneath, and the clip has to
 * come out of the speaker there exactly as it does full screen. Left inside the branch that
 * draws the controls, it never plays and the receipt that ends the duck never comes back.
 */
@OptIn(UnstableApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w800dp-h360dp-land-notnight-mdpi")
class PlayerScreenClipTest {

    @get:Rule val compose = createComposeRule()

    /** A speaker that holds the clip until the test says it has finished, as a real one would. */
    private class Speaker : VoicePlayback {
        val played = mutableListOf<Long>()
        var stops = 0
        private var finish: (() -> Unit)? = null

        override fun play(id: Long, bytes: ByteArray, onFinished: () -> Unit) {
            played += id
            finish = onFinished
        }

        override fun stop() {
            stops += 1
        }

        fun finished() = finish?.invoke()
    }

    private val playing = PlayerUiState(
        title = "Фрирен",
        episode = 4,
        isPlaying = true,
        isBuffering = false,
        positionMs = 60_000,
        durationMs = 1_440_000,
    )

    private fun screen(
        inWindow: Boolean,
        speaker: Speaker,
        clip: PlayingClip?,
        onClipPlayed: () -> Unit,
    ) = compose.setContent {
        PlayerScreen(
            state = playing,
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
            isInPictureInPicture = inWindow,
            together = TogetherControls(
                state = TogetherUiState(phase = TogetherPhase.LIVE, peerName = "Аня", playing = clip),
                player = speaker,
                onClipPlayed = onClipPlayed,
                enabled = true,
            ),
        )
    }

    @Test
    fun `a friend's clip comes out of the speaker while the picture is in a window`() {
        val speaker = Speaker()
        var receipts = 0

        screen(
            inWindow = true,
            speaker = speaker,
            clip = PlayingClip(7, byteArrayOf(1, 2, 3), durationMs = 1_200),
            onClipPlayed = { receipts += 1 },
        )
        compose.waitForIdle()

        assertEquals(listOf(7L), speaker.played)
        assertEquals("the clip is still being heard", 0, receipts)

        // The receipt is what puts the episode's sound back: without it the window stays quiet
        // for as long as it is open.
        speaker.finished()
        compose.waitForIdle()

        assertEquals(1, receipts)
    }

    @Test
    fun `a clip with nowhere to play is receipted at once rather than left hanging`() {
        var receipts = 0

        compose.setContent {
            PlayerScreen(
                state = playing,
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
                isInPictureInPicture = true,
                together = TogetherControls(
                    state = TogetherUiState(
                        phase = TogetherPhase.LIVE,
                        playing = PlayingClip(9, byteArrayOf(1), durationMs = 500),
                    ),
                    player = null,
                    onClipPlayed = { receipts += 1 },
                    enabled = true,
                ),
            )
        }
        compose.waitForIdle()

        assertEquals(1, receipts)
    }

    @Test
    fun `a full screen player plays the clip exactly as the window does`() {
        val speaker = Speaker()
        var receipts = 0

        screen(
            inWindow = false,
            speaker = speaker,
            clip = PlayingClip(7, byteArrayOf(1, 2, 3), durationMs = 1_200),
            onClipPlayed = { receipts += 1 },
        )
        compose.waitForIdle()

        assertEquals(listOf(7L), speaker.played)

        speaker.finished()
        compose.waitForIdle()

        assertEquals(1, receipts)
    }
}
