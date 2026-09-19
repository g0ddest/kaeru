package app.kaeru.ui.mobile.player

import android.graphics.Bitmap
import android.graphics.Canvas
import androidx.activity.ComponentActivity
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.text.TextLayoutResult
import app.kaeru.domain.model.Quality
import app.kaeru.ui.common.details.EpisodeCell
import app.kaeru.ui.common.player.PlayerUiState
import app.kaeru.ui.common.theme.KaeruTheme
import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * The remote control, composed and drawn at the two sizes a phone holds it, and measured.
 *
 * It is `TvRenderBudgetTest` for the phone, and it exists for the same reason: the screen that
 * sent it here was a photograph of three grey circles with their glyphs cut off, sitting between
 * the timeline and «Серии» on a 780×360dp landscape phone. No arithmetic test could have caught
 * that, because the fault was not in a number anybody had written down — a `Column` measures its
 * later children against whatever the earlier ones left, and what was left of the transport row
 * was nothing at all.
 *
 * So nothing here is modelled. The screen is composed at the phone's own qualifiers with the real
 * `manrope.ttf` and `GraphicsMode.NATIVE` — which is what makes Robolectric measure text with the
 * font the app ships rather than with a stand-in whose metrics do not move with the size — and
 * every number is read back off the semantics tree.
 *
 * Six questions, each of them a fault that had to be seen on a phone to be found:
 *
 * 1. **Is anything laid out past the edge of the screen?** `positionInRoot` plus `size` is where a
 *    node actually is, whatever its parent believes.
 * 2. **Is what is on the screen drawn whole?** `boundsInRoot` is what the parents let through, so
 *    a glyph shaved off by a button clipped to a circle is the difference between the two boxes.
 * 3. **Is anything a finger reaches squeezed flat?** A `Row` hands its later children what is left,
 *    and «вперёд на 10 секунд» measured 2dp wide in portrait — a disc, drawn, pressable in theory,
 *    and invisible.
 * 4. **Does the episode strip stand clear of everything above it?** Nothing may reach down into it.
 * 5. **Does a line of text have the box its glyphs need?** `GetTextLayoutResult` knows how tall the
 *    paragraph it laid out is; the node knows how much room it was given.
 * 6. **Is there room left over?** [SLACK] of it, so the next line of type or the next inset does
 *    not put the screen back where it started.
 *
 * `captureToImage()` does not work under Robolectric — its `forceRedraw` waits on a vsync that
 * never arrives — so the pictures, when they are asked for, are taken by drawing the decor view
 * into a bitmap. That is a real rasterisation and it is not free, so it happens only when
 * `KAERU_RENDER_DIR` names somewhere to put them:
 *
 * ```
 * KAERU_RENDER_DIR=/tmp/remote ./gradlew :app:testDebugUnitTest --tests '*RemoteRenderBudgetTest'
 * ```
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
        screen("remote-land-playing").remoteFits(PAUSE)
    }

    @Test
    @Config(qualifiers = LANDSCAPE)
    fun `the remote fits a landscape phone while a failure is on it`() {
        show(failed)
        screen("remote-land-error").remoteFits(RESUME)
    }

    @Test
    @Config(qualifiers = LANDSCAPE)
    fun `the remote fits a landscape phone while the receiver buffers`() {
        show(buffering)
        screen("remote-land-buffering").remoteFits(null)
    }

    // --- and the portrait phone it is held in the rest of the time -------------------------------

    @Test
    @Config(qualifiers = PORTRAIT)
    fun `the remote fits a portrait phone while the episode plays`() {
        show(playing)
        screen("remote-port-playing").remoteFits(PAUSE)
    }

    @Test
    @Config(qualifiers = PORTRAIT)
    fun `the remote fits a portrait phone while a failure is on it`() {
        show(failed)
        screen("remote-port-error").remoteFits(RESUME)
    }

    @Test
    @Config(qualifiers = PORTRAIT)
    fun `the remote fits a portrait phone while the receiver buffers`() {
        show(buffering)
        screen("remote-port-buffering").remoteFits(null)
    }

    // --- the three states, and what is on the screen in each of them -----------------------------

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

    private fun show(state: PlayerUiState) = compose.setContent {
        KaeruTheme {
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
            )
        }
    }

    // --- measuring --------------------------------------------------------------------------------

    /** Composes nothing and measures everything: the tree as it stands, in device-independent pixels. */
    private fun screen(name: String): Phone {
        compose.waitForIdle()
        val root = compose.onRoot(useUnmergedTree = true).fetchSemanticsNode()
        val phone = Phone(name, root, compose.density.density)
        System.getenv(RENDER_DIR)?.takeIf { it.isNotBlank() }?.let { dir ->
            val view = compose.activity.window.decorView
            val bitmap = Bitmap.createBitmap(
                view.width.coerceAtLeast(1),
                view.height.coerceAtLeast(1),
                Bitmap.Config.ARGB_8888,
            )
            view.draw(Canvas(bitmap))
            val out = File(File(dir).apply { mkdirs() }, "$name.png")
            out.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
            println("PNG|$name|${out.absolutePath}")
            // Beside the picture, the tree it was drawn from: where every node was put, how wide
            // it is, how many lines it took and whether it was cut.
            phone.dump()
        }
        return phone
    }
}

/** The two sizes the ruling names: a landscape phone at 780×360dp, and the same phone upright. */
private const val LANDSCAPE = "w780dp-h360dp-land-xxhdpi"
private const val PORTRAIT = "w360dp-h780dp-port-xxhdpi"

/** How much room has to be left over at the bottom of the screen. */
private const val SLACK = 8f

/**
 * The floor for anything a finger reaches.
 *
 * The app's own token is 48dp and that is what the screen aims for; 40 is what the ruling allows a
 * control to come down to where the room is genuinely not there, so it is what the guard holds.
 */
private const val PRESSABLE = 40f

/** Half a device-independent pixel: rounding, not a fault. */
private const val TOLERANCE = 0.5f

private const val RENDER_DIR = "KAERU_RENDER_DIR"

private const val PAUSE = "Пауза"
private const val RESUME = "Продолжить"
private const val BACK_10 = "Назад на 10 секунд"
private const val FORWARD_10 = "Вперёд на 10 секунд"
private const val EPISODES = "Серии"

private fun Float.r(): String = String.format("%.1f", this)

private fun flatten(node: SemanticsNode): List<SemanticsNode> =
    listOf(node) + node.children.flatMap(::flatten)

private fun SemanticsNode.textLayout(): TextLayoutResult? {
    val out = mutableListOf<TextLayoutResult>()
    config.getOrNull(SemanticsActions.GetTextLayoutResult)?.action?.invoke(out)
    return out.firstOrNull()
}

private fun SemanticsNode.lines(): Int = textLayout()?.lineCount ?: 0

private fun SemanticsNode.words(): String? =
    config.getOrNull(SemanticsProperties.Text)?.joinToString(" ") { it.text }

private fun SemanticsNode.describedAs(): String? =
    config.getOrNull(SemanticsProperties.ContentDescription)?.joinToString(" ")

private fun SemanticsNode.label(): String =
    words() ?: describedAs() ?: config.getOrNull(SemanticsProperties.TestTag) ?: "node $id"

private fun SemanticsNode.isUnder(ancestor: SemanticsNode): Boolean =
    generateSequence(parent) { it.parent }.any { it.id == ancestor.id }

/** Whether a finger has anything to land on here. */
private fun SemanticsNode.isPressable(): Boolean =
    config.getOrNull(SemanticsActions.OnClick) != null

/**
 * One composed screen, and the questions worth asking it.
 *
 * The tree is the unmerged one on purpose: a button's glyph is merged into the button it belongs
 * to, and the glyph cut off by the bottom of a squeezed button is exactly the node this has to see.
 */
private class Phone(val name: String, val root: SemanticsNode, val density: Float) {

    val nodes: List<SemanticsNode> = flatten(root)

    /** Everything but the root: the root is the screen, and the screen cannot overflow itself. */
    val drawn: List<SemanticsNode> = nodes.filter { it.id != root.id }

    val width: Float = root.size.width.toFloat().dp()
    val height: Float = root.size.height.toFloat().dp()

    fun Float.dp(): Float = this / density

    fun SemanticsNode.topDp(): Float = positionInRoot.y.dp()

    fun SemanticsNode.bottomDp(): Float = (positionInRoot.y + size.height).dp()

    fun SemanticsNode.leftDp(): Float = positionInRoot.x.dp()

    fun SemanticsNode.rightDp(): Float = (positionInRoot.x + size.width).dp()

    fun SemanticsNode.heightDp(): Float = size.height.toFloat().dp()

    fun SemanticsNode.widthDp(): Float = size.width.toFloat().dp()

    /** And how much of it the parents actually let through to the screen. */
    fun SemanticsNode.drawnHeightDp(): Float = (boundsInRoot.bottom - boundsInRoot.top).dp()

    fun SemanticsNode.drawnWidthDp(): Float = (boundsInRoot.right - boundsInRoot.left).dp()

    /** How tall the paragraph this node laid out is, whatever room the node was given. */
    fun SemanticsNode.lineBoxDp(): Float? =
        textLayout()?.takeIf { it.lineCount > 0 }?.let { it.getLineBottom(it.lineCount - 1).dp() }

    fun saying(text: String): SemanticsNode =
        nodes.firstOrNull { it.words() == text } ?: error("$name: nothing on the screen says «$text»")

    fun describing(description: String): SemanticsNode =
        nodes.firstOrNull { it.describedAs() == description }
            ?: error("$name: nothing on the screen is «$description»")

    /** The strip of episodes, which is the one thing on this screen that is meant to scroll. */
    fun strip(): SemanticsNode = nodes
        .firstOrNull { it.config.getOrNull(SemanticsProperties.HorizontalScrollAxisRange) != null }
        ?: error("$name: no episode strip on this screen")

    /** The nearest thing above this node that scrolls, if there is one. */
    fun SemanticsNode.scroller(): SemanticsNode? = generateSequence(parent) { it.parent }
        .firstOrNull {
            it.config.getOrNull(SemanticsProperties.HorizontalScrollAxisRange) != null ||
                it.config.getOrNull(SemanticsProperties.VerticalScrollAxisRange) != null
        }

    /**
     * Whether something that scrolls has taken this node out of view.
     *
     * A tile the strip has scrolled past its own edge has no visible box at all, and a failure
     * below the fold of the column beside the poster has none either: both are a scroller doing
     * its job rather than a layout being cut, and both are what the ruling allows where the room
     * genuinely is not there. Everything inside a viewport has no such excuse, and is held to the
     * same standard as everything outside one.
     */
    fun SemanticsNode.isScrolledOutOfView(): Boolean {
        val view = scroller()?.boundsInRoot ?: return false
        return topDp() < view.top.dp() - TOLERANCE || bottomDp() > view.bottom.dp() + TOLERANCE ||
            leftDp() < view.left.dp() - TOLERANCE || rightDp() > view.right.dp() + TOLERANCE
    }

    // --- the six questions -------------------------------------------------------------------

    /** Every line of text has the box its own glyphs need. */
    fun textIsNeverSqueezed() = drawn.forEach { node ->
        val box = node.lineBoxDp() ?: return@forEach
        assertTrue(
            "$name: «${node.label()}» was given ${node.heightDp().r()}dp for ${node.lines()} " +
                "line(s) needing ${box.r()}dp, so only the top of it is drawn",
            node.heightDp() + TOLERANCE >= box,
        )
    }

    /** Nothing is laid out past an edge of the screen. */
    fun nothingLeavesTheScreen() = drawn.forEach { node ->
        if (node.isScrolledOutOfView()) return@forEach
        assertTrue(
            "$name: «${node.label()}» runs to ${node.bottomDp().r()}dp of a ${height.r()}dp screen",
            node.bottomDp() <= height + TOLERANCE,
        )
        assertTrue(
            "$name: «${node.label()}» starts at ${node.topDp().r()}dp, above the screen",
            node.topDp() >= -TOLERANCE,
        )
        assertTrue(
            "$name: «${node.label()}» runs to ${node.rightDp().r()}dp of a ${width.r()}dp screen",
            node.rightDp() <= width + TOLERANCE,
        )
        assertTrue(
            "$name: «${node.label()}» starts at ${node.leftDp().r()}dp, off the left of the screen",
            node.leftDp() >= -TOLERANCE,
        )
    }

    /** Everything is drawn in full rather than shaved by a parent's clipping. */
    fun everythingIsDrawnWhole() = drawn.forEach { node ->
        if (node.isScrolledOutOfView()) return@forEach
        assertTrue(
            "$name: «${node.label()}» is ${node.heightDp().r()}dp tall at ${node.topDp().r()}dp and " +
                "only ${node.drawnHeightDp().r()}dp of it is drawn",
            node.drawnHeightDp() + TOLERANCE >= node.heightDp(),
        )
        assertTrue(
            "$name: «${node.label()}» is ${node.widthDp().r()}dp wide at ${node.leftDp().r()}dp and " +
                "only ${node.drawnWidthDp().r()}dp of it is drawn",
            node.drawnWidthDp() + TOLERANCE >= node.widthDp(),
        )
    }

    /** Nothing a finger reaches has been squeezed to a sliver by a row that ran out of room. */
    fun nothingPressableIsSqueezed() = drawn.filter { it.isPressable() }.forEach { node ->
        assertTrue(
            "$name: «${node.label()}» is ${node.widthDp().r()}×${node.heightDp().r()}dp, under the " +
                "${PRESSABLE.r()}dp a finger needs",
            node.widthDp() + TOLERANCE >= PRESSABLE && node.heightDp() + TOLERANCE >= PRESSABLE,
        )
    }

    /**
     * The strip of episodes has the bottom of the screen to itself.
     *
     * This is the fault in the photograph, stated as a number: the transport row and the strip
     * were laid out over one another, so the discs were drawn into «Серии» and cut.
     */
    fun theStripStandsClear() {
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
    fun theTransportIsWhole(main: String?) {
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

    /**
     * How far down the screen anything a viewer reads or presses reaches, and what that leaves.
     *
     * Over what is drawn rather than over every box: the column that holds the strip of episodes
     * ends where the screen does by design, and its last 12dp of padding is the room this is
     * measuring.
     */
    fun roomToSpare(): Float = height - drawn
        .filter { it.words() != null || it.describedAs() != null || it.isPressable() }
        .maxOf { it.bottomDp() }

    fun remoteFits(main: String?) {
        textIsNeverSqueezed()
        nothingLeavesTheScreen()
        everythingIsDrawnWhole()
        nothingPressableIsSqueezed()
        theStripStandsClear()
        theTransportIsWhole(main)

        val slack = roomToSpare()
        report(slack)
        assertTrue(
            "$name: the screen reaches ${(height - slack).r()}dp of ${height.r()}dp, leaving " +
                "${slack.r()}dp — under the ${SLACK.r()}dp it has to keep",
            slack + TOLERANCE >= SLACK,
        )
    }

    fun dump() = nodes.forEach {
        println(
            "A|$name|${it.label()}|y=${it.topDp().r()}..${it.bottomDp().r()}" +
                "|x=${it.leftDp().r()}..${it.rightDp().r()}" +
                "|drawn=${it.drawnWidthDp().r()}×${it.drawnHeightDp().r()}" +
                "|lines=${it.lines()}|press=${it.isPressable()}",
        )
    }

    /** The one line a reader of the test output is looking for: how much room was left over. */
    fun report(slack: Float) = println(
        "BUDGET|$name|screen=${width.r()}×${height.r()}dp|deepest=${(height - slack).r()}dp|slack=${slack.r()}dp",
    )
}
