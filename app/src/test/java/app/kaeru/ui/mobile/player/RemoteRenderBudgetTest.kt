package app.kaeru.ui.mobile.player

import androidx.activity.ComponentActivity
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import app.kaeru.domain.model.Quality
import app.kaeru.ui.common.details.EpisodeCell
import app.kaeru.ui.common.player.LocalCastAvailable
import app.kaeru.ui.common.player.PlayerUiState
import app.kaeru.ui.common.theme.KaeruTheme
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * The remote control, composed and drawn at every shape and size of type a phone gives it.
 *
 * The harness is [Phone]; what is particular to this screen is the strip of episodes standing
 * clear of everything above it, and the three discs being whole with their glyphs inside them.
 *
 * Four things are varied, because each of them is a way this screen has already been broken:
 *
 * * **The shape of the window.** Landscape is the photograph this branch started from; portrait is
 *   what multi-window and a tablet give an activity locked to `sensorLandscape`; and the two split
 *   sizes are the one shape that is narrow and short at once.
 * * **The size of the type.** One step of the Android font-size slider past the default is where
 *   the fold of the scrolling block cut a pressable chip and «Повторить» in half.
 * * **What is on the screen.** A failure is the tallest the facts ever get; the end of an episode
 *   is the widest the transport row ever gets; a shared viewing and a long dub name are the two
 *   things most likely to run the header and the chips out of width next.
 * * **Whether the phone can cast at all.** `LocalCastAvailable` is false by default, and a guard
 *   that leaves it there measures a header two controls short of the one a viewer sees — this
 *   screen only ever exists while a receiver has the picture.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = LANDSCAPE)
class RemoteRenderBudgetTest {

    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    // --- the landscape phone the screenshot came from -------------------------------------------

    @Test
    @Config(qualifiers = LANDSCAPE)
    fun `the remote fits a landscape phone while the episode plays`() {
        show(playing)
        compose.screen("remote-land-playing").remoteFits(PAUSE)
    }

    @Test
    @Config(qualifiers = LANDSCAPE)
    fun `the remote fits a landscape phone while a failure is on it`() {
        show(failed)
        compose.screen("remote-land-error").remoteFits(RESUME)
    }

    @Test
    @Config(qualifiers = LANDSCAPE)
    fun `the remote fits a landscape phone while the receiver buffers`() {
        show(buffering)
        compose.screen("remote-land-buffering").remoteFits(null)
    }

    // --- and the portrait phone it is held in the rest of the time -------------------------------

    @Test
    @Config(qualifiers = PORTRAIT)
    fun `the remote fits a portrait phone while the episode plays`() {
        show(playing)
        compose.screen("remote-port-playing").remoteFits(PAUSE)
    }

    @Test
    @Config(qualifiers = PORTRAIT)
    fun `the remote fits a portrait phone while a failure is on it`() {
        show(failed)
        compose.screen("remote-port-error").remoteFits(RESUME)
    }

    @Test
    @Config(qualifiers = PORTRAIT)
    fun `the remote fits a portrait phone while the receiver buffers`() {
        show(buffering)
        compose.screen("remote-port-buffering").remoteFits(null)
    }

    // --- the same six one step up the font-size slider -------------------------------------------

    @Test
    @Config(qualifiers = LANDSCAPE, fontScale = BIG_TYPE)
    fun `the remote fits a landscape phone in big type while the episode plays`() {
        show(playing)
        compose.screen("remote-land-playing-big").remoteFits(PAUSE)
    }

    @Test
    @Config(qualifiers = LANDSCAPE, fontScale = BIG_TYPE)
    fun `the remote fits a landscape phone in big type while a failure is on it`() {
        show(failed)
        compose.screen("remote-land-error-big").remoteFits(RESUME)
    }

    @Test
    @Config(qualifiers = LANDSCAPE, fontScale = BIG_TYPE)
    fun `the remote fits a landscape phone in big type while the receiver buffers`() {
        show(buffering)
        compose.screen("remote-land-buffering-big").remoteFits(null)
    }

    @Test
    @Config(qualifiers = PORTRAIT, fontScale = BIG_TYPE)
    fun `the remote fits a portrait phone in big type while the episode plays`() {
        show(playing)
        compose.screen("remote-port-playing-big").remoteFits(PAUSE)
    }

    @Test
    @Config(qualifiers = PORTRAIT, fontScale = BIG_TYPE)
    fun `the remote fits a portrait phone in big type while a failure is on it`() {
        show(failed)
        compose.screen("remote-port-error-big").remoteFits(RESUME)
    }

    @Test
    @Config(qualifiers = PORTRAIT, fontScale = BIG_TYPE)
    fun `the remote fits a portrait phone in big type while the receiver buffers`() {
        show(buffering)
        compose.screen("remote-port-buffering-big").remoteFits(null)
    }

    // --- the end of an episode, with a friend watching and a dub name that will not stop ---------

    @Test
    @Config(qualifiers = LANDSCAPE)
    fun `the remote fits a landscape phone at the end of a shared episode`() {
        show(crowded, peer = PEER)
        compose.screen("remote-land-crowded").remoteFits(PAUSE)
    }

    @Test
    @Config(qualifiers = LANDSCAPE, fontScale = BIG_TYPE)
    fun `the remote fits a landscape phone in big type at the end of a shared episode`() {
        show(crowded, peer = PEER)
        compose.screen("remote-land-crowded-big").remoteFits(PAUSE)
    }

    @Test
    @Config(qualifiers = PORTRAIT)
    fun `the remote fits a portrait phone at the end of a shared episode`() {
        show(crowded, peer = PEER)
        compose.screen("remote-port-crowded").remoteFits(PAUSE)
    }

    @Test
    @Config(qualifiers = PORTRAIT, fontScale = BIG_TYPE)
    fun `the remote fits a portrait phone in big type at the end of a shared episode`() {
        show(crowded, peer = PEER)
        compose.screen("remote-port-crowded-big").remoteFits(PAUSE)
    }

    // --- split screen: the one shape that is narrow and short at once ----------------------------

    @Test
    @Config(qualifiers = SPLIT_TALLISH)
    fun `the remote fits the taller half of a split screen`() {
        show(playing)
        compose.screen("remote-split-tallish").remoteFits(PAUSE)
    }

    @Test
    @Config(qualifiers = SPLIT_TALLISH, fontScale = BIG_TYPE)
    fun `the remote fits the taller half of a split screen in big type`() {
        show(failed)
        compose.screen("remote-split-tallish-big").remoteFits(RESUME)
    }

    @Test
    @Config(qualifiers = SPLIT_WIDEISH)
    fun `the remote fits the shorter half of a split screen`() {
        show(playing)
        compose.screen("remote-split-wideish").remoteFits(PAUSE)
    }

    @Test
    @Config(qualifiers = SPLIT_WIDEISH, fontScale = BIG_TYPE)
    fun `the remote fits the shorter half of a split screen in big type`() {
        show(failed)
        compose.screen("remote-split-wideish-big").remoteFits(RESUME)
    }

    // --- the states, and what is on the screen in each of them -----------------------------------

    /** The worst case the catalogue produces: a name that runs past the column and a full season. */
    private val base = PlayerUiState(
        title = "Фрирен, провожающая в последний путь",
        posterUrl = null,
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
        isCasting = true,
        receiverName = "Гостиная ТВ",
        episodes = (1..24).map { EpisodeCell(it, watched = it < 7, progress = null, aired = true) },
    )

    private val playing = base.copy(isPlaying = true, isBuffering = false)

    /**
     * Paused with a failure under the chips — the tallest the right-hand column ever gets, since
     * the error is the one row that is there only sometimes.
     */
    private val failed = base.copy(
        isPlaying = false,
        isBuffering = false,
        errorMessage = "Не удалось получить ссылку на серию. Проверьте соединение.",
    )

    private val buffering = base.copy(isPlaying = false, isBuffering = true)

    /**
     * The end of an episode, with everything that is only sometimes there at once.
     *
     * The countdown turns one button into two, the dub name is the longest the catalogue carries,
     * and a friend puts a third control in the header. Each of them alone leaves room; the guard
     * is a budget, and a budget is spent all at once or not at all.
     */
    private val crowded = playing.copy(
        autoplayCountdownSec = 5,
        translationTitle = "Многоголосый закадровый, AniDUB HD",
    )

    private fun show(state: PlayerUiState, peer: String? = null) {
        compose.dressedAsTheApp()
        compose.setContent {
            KaeruTheme {
                // This screen exists only while a receiver has the picture, so the header always
                // has the cast button on it. Left at its default the guard would measure a header
                // that never happens.
                CompositionLocalProvider(LocalCastAvailable provides true) {
                    RemoteControlScreen(
                        state = state,
                        onBack = {},
                        onTogglePlayPause = {},
                        onSeekTo = {},
                        onSeekBy = {},
                        onNext = {},
                        onCancelAutoplay = {},
                        onOpenTranslations = {},
                        onOpenQualities = {},
                        onPickEpisode = {},
                        onRetry = {},
                        onStopCasting = {},
                        togetherPeer = peer,
                        onLeaveTogether = {},
                    )
                }
            }
        }
    }
}

/** A 34-character dub name and a friend on the other phone: the two longest things the screen gets. */
private const val PEER = "Смотрим с Аней"

// --- what is particular to the remote ------------------------------------------------------------

/**
 * The strip of episodes has the bottom of the screen to itself.
 *
 * This is the fault in the photograph, stated as a number: the transport row and the strip were
 * laid out over one another, so the discs were drawn into «Серии» and cut.
 */
internal fun Phone.theStripStandsClear() {
    val heading = saying(EPISODES)
    val row = strip()
    val top = minOf(heading.topDp(), row.topDp())
    // The strip itself, what it carries, and the containers it sits inside: a `Column` that
    // holds the strip legitimately reaches to the bottom of the strip.
    val theStripsOwn = generateSequence<SemanticsNode>(row) { it.parent }.map { it.id }.toSet()
    drawn
        .filter { it.id != heading.id && it.id !in theStripsOwn && !it.isUnder(row) }
        .filterNot { it.isScrolledOutOfView() }
        .forEach { node ->
            assertTrue(
                "$name: «${node.label()}» reaches ${node.bottomDp().r()}dp, down into the strip " +
                    "of episodes that starts at ${top.r()}dp",
                node.bottomDp() <= top + TOLERANCE,
            )
        }
}

/**
 * The three discs under the timeline, each whole, each pressable, each with its glyph inside it.
 *
 * [main] is what the middle one says, or null while the receiver is buffering and it is a
 * spinner instead.
 */
internal fun Phone.theTransportIsWhole(main: String?) {
    (listOfNotNull(BACK_10, FORWARD_10, main)).forEach { description ->
        val glyph = describing(description)
        val disc = generateSequence(glyph) { it.parent }.firstOrNull { it.isPressable() }
            ?: error("$name: «$description» is not inside anything that can be pressed")
        assertTrue(
            "$name: the disc under «$description» is ${disc.widthDp().r()}×${disc.heightDp().r()}dp",
            disc.widthDp() + TOLERANCE >= PRESSABLE && disc.heightDp() + TOLERANCE >= PRESSABLE,
        )
        assertTrue(
            "$name: the glyph «$description» is ${glyph.heightDp().r()}dp tall and only " +
                "${glyph.drawnHeightDp().r()}dp of it is drawn — the disc is cutting it",
            glyph.drawnHeightDp() + TOLERANCE >= glyph.heightDp() &&
                glyph.drawnWidthDp() + TOLERANCE >= glyph.widthDp(),
        )
    }
}

internal fun Phone.remoteFits(main: String?) {
    theStripStandsClear()
    theTransportIsWhole(main)
    fitsThePhone()
}
