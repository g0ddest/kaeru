package app.kaeru.ui.mobile.player

import android.graphics.Bitmap
import android.graphics.Canvas
import androidx.activity.ComponentActivity
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.junit4.AndroidComposeTestRule
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.text.TextLayoutResult
import androidx.test.ext.junit.rules.ActivityScenarioRule
import app.kaeru.R
import java.io.File
import org.junit.Assert.assertTrue

/**
 * The harness both phone screens are measured with: compose, draw, read the tree back.
 *
 * It lives beside the two tests rather than inside one of them because the remote control and
 * the player ask the same questions of the same phone. `TvRenderBudgetTest` is where the idea
 * comes from, and the reason is the same: no arithmetic test could have caught the photograph
 * this branch started from, because the fault was not in a number anybody had written down — a
 * `Column` measures its later children against whatever the earlier ones left, and what was left
 * of the transport row was nothing at all.
 *
 * So nothing here is modelled. A screen is composed at the phone's own qualifiers with the real
 * `manrope.ttf` and `GraphicsMode.NATIVE` — which is what makes Robolectric measure text with the
 * font the app ships rather than with a stand-in whose metrics do not move with the size — and
 * every number is read back off the semantics tree.
 *
 * `captureToImage()` does not work under Robolectric — its `forceRedraw` waits on a vsync that
 * never arrives — so the pictures, when they are asked for, are taken by drawing the decor view
 * into a bitmap. That is a real rasterisation and it is not free, so it happens only when
 * `KAERU_RENDER_DIR` names somewhere to put them:
 *
 * ```
 * KAERU_RENDER_DIR=/tmp/remote ./gradlew :app:testDebugUnitTest --tests '*RenderBudgetTest'
 * ```
 */
internal typealias PhoneRule = AndroidComposeTestRule<ActivityScenarioRule<ComponentActivity>, ComponentActivity>

/**
 * The theme the activities that host these screens actually run under.
 *
 * A bare `ComponentActivity` comes up under Robolectric with the platform's own theme, whose
 * `colorBackground` is transparent — and `MediaRouteButton` will not be built against one
 * («background can not be translucent»), so a guard that leaves the theme alone is a guard that
 * cannot have the cast button in the header it measures. `Theme.Kaeru` descends from AppCompat
 * for exactly the reason the route chooser needs it to, and it is what both activities declare.
 */
internal fun PhoneRule.dressedAsTheApp() {
    activity.setTheme(R.style.Theme_Kaeru)
}

/** Composes nothing and measures everything: the tree as it stands, in device-independent pixels. */
internal fun PhoneRule.screen(name: String): Phone {
    waitForIdle()
    val root = onRoot(useUnmergedTree = true).fetchSemanticsNode()
    val phone = Phone(name, root, density.density)
    System.getenv(RENDER_DIR)?.takeIf { it.isNotBlank() }?.let { dir ->
        val view = activity.window.decorView
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

/** The two sizes the ruling names: a landscape phone at 780×360dp, and the same phone upright. */
internal const val LANDSCAPE = "w780dp-h360dp-land-xxhdpi"
internal const val PORTRAIT = "w360dp-h780dp-port-xxhdpi"

/**
 * And the two shapes split-screen makes of them.
 *
 * `PlayerActivity` is locked to `sensorLandscape`, so these are the one way it is handed a window
 * it did not ask for: narrow and short at once, which is neither of the shapes above.
 */
internal const val SPLIT_TALLISH = "w360dp-h400dp-port-xxhdpi"
internal const val SPLIT_WIDEISH = "w390dp-h360dp-land-xxhdpi"

/**
 * The fourth step of the Android font-size slider.
 *
 * One step past the default is where the original photograph came back: the landscape fold cut a
 * pressable chip and «Повторить» through the middle, and nothing said so. Every case is rendered
 * at both sizes of type for that reason.
 */
internal const val BIG_TYPE = 1.3f

/** How much room has to be left over at the bottom of the screen. */
internal const val SLACK = 8f

/**
 * The floor for anything a finger reaches.
 *
 * The app's own token is 48dp and that is what the screen aims for; 40 is what the ruling allows a
 * control to come down to where the room is genuinely not there, so it is what the guard holds.
 */
internal const val PRESSABLE = 40f

/** Half a device-independent pixel: rounding, not a fault. */
internal const val TOLERANCE = 0.5f

internal const val RENDER_DIR = "KAERU_RENDER_DIR"

internal const val PAUSE = "Пауза"
internal const val RESUME = "Продолжить"
internal const val BACK_10 = "Назад на 10 секунд"
internal const val FORWARD_10 = "Вперёд на 10 секунд"
internal const val EPISODES = "Серии"

internal fun Float.r(): String = String.format("%.1f", this)

internal fun flatten(node: SemanticsNode): List<SemanticsNode> =
    listOf(node) + node.children.flatMap(::flatten)

internal fun SemanticsNode.textLayout(): TextLayoutResult? {
    val out = mutableListOf<TextLayoutResult>()
    config.getOrNull(SemanticsActions.GetTextLayoutResult)?.action?.invoke(out)
    return out.firstOrNull()
}

internal fun SemanticsNode.lines(): Int = textLayout()?.lineCount ?: 0

internal fun SemanticsNode.words(): String? =
    config.getOrNull(SemanticsProperties.Text)?.joinToString(" ") { it.text }

internal fun SemanticsNode.describedAs(): String? =
    config.getOrNull(SemanticsProperties.ContentDescription)?.joinToString(" ")

internal fun SemanticsNode.label(): String =
    words() ?: describedAs() ?: config.getOrNull(SemanticsProperties.TestTag) ?: "node $id"

internal fun SemanticsNode.isUnder(ancestor: SemanticsNode): Boolean =
    generateSequence(parent) { it.parent }.any { it.id == ancestor.id }

/**
 * A list of many, rather than a column that happens to scroll.
 *
 * `LazyRow` and `LazyColumn` put `scrollToIndex` on the node they build and a plain
 * `Modifier.verticalScroll` puts nothing of the kind, which is the difference between a strip
 * whose edge tiles are meant to be cut and a fold that is cutting a control.
 */
internal fun SemanticsNode.isAList(): Boolean =
    config.getOrNull(SemanticsActions.ScrollToIndex) != null

/** Whether a finger has anything to land on here. */
internal fun SemanticsNode.isPressable(): Boolean =
    config.getOrNull(SemanticsActions.OnClick) != null

/**
 * One composed screen, and the questions worth asking it.
 *
 * The tree is the unmerged one on purpose: a button's glyph is merged into the button it belongs
 * to, and the glyph cut off by the bottom of a squeezed button is exactly the node this has to see.
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
 */
internal class Phone(val name: String, val root: SemanticsNode, val density: Float) {

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
     * Whether something that scrolls has carried this node clean out of sight.
     *
     * *Clean* out of sight is the whole of it. This used to read «any edge outside the viewport»,
     * and that exempted the one thing the guard exists to catch: a chip drawn 28dp of its 41 by the
     * fold of the block it sits in is a control sliced through the middle, which is the photograph
     * this branch began with, and the old test called it scrolled and passed it. A node that
     * straddles the fold of a plain scrolling column now answers to [everythingIsDrawnWhole] like
     * everything else, so the fold has to fall between rows rather than through one.
     *
     * A **list** is the one exception, and it is not a loophole: a strip of tiles cuts its first
     * and its last on purpose, because the sliver at either edge is the whole of what says there is
     * more of the season that way. A list says so in the tree — `scrollToIndex` is on a `LazyRow`
     * and on nothing a `verticalScroll` builds — so the guard can tell the two apart without being
     * told. What the list carries is still held to every other question: a tile squeezed under a
     * finger's width is still a fault, and so is one laid over the row of discs above it.
     */
    fun SemanticsNode.isScrolledOutOfView(): Boolean {
        val scroller = scroller() ?: return false
        val view = scroller.boundsInRoot
        val whollyOutside = bottomDp() <= view.top.dp() + TOLERANCE ||
            topDp() >= view.bottom.dp() - TOLERANCE ||
            rightDp() <= view.left.dp() + TOLERANCE ||
            leftDp() >= view.right.dp() - TOLERANCE
        return whollyOutside || scroller.isAList()
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
     * How far down the screen anything a viewer reads or presses reaches, and what that leaves.
     *
     * Over what is drawn rather than over every box: the column that holds the strip of episodes
     * ends where the screen does by design, and its last 12dp of padding is the room this is
     * measuring.
     */
    fun roomToSpare(): Float = height - drawn
        .filter { it.words() != null || it.describedAs() != null || it.isPressable() }
        .maxOf { it.bottomDp() }

    /** The three questions every phone screen answers, and the room it has left when it does. */
    fun fitsThePhone() {
        textIsNeverSqueezed()
        nothingLeavesTheScreen()
        everythingIsDrawnWhole()
        nothingPressableIsSqueezed()

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
