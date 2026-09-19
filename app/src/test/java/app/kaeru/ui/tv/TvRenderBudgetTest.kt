package app.kaeru.ui.tv

import android.graphics.Bitmap
import android.graphics.Canvas
import androidx.activity.ComponentActivity
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasScrollAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performScrollToIndex
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.text.TextLayoutResult
import app.kaeru.domain.model.Account
import app.kaeru.domain.model.Anime
import app.kaeru.domain.model.AnimeStatus
import app.kaeru.domain.model.LibraryEntry
import app.kaeru.domain.model.ListStatus
import app.kaeru.domain.model.Quality
import app.kaeru.domain.model.Translation
import app.kaeru.domain.model.TranslationKind
import app.kaeru.domain.model.UserRate
import app.kaeru.domain.playback.RankedTranslation
import app.kaeru.domain.playback.SkipKind
import app.kaeru.domain.update.UpdateRelease
import app.kaeru.ui.common.auth.AuthUiState
import app.kaeru.ui.common.design.KaeruTokens
import app.kaeru.ui.common.details.DetailsUiState
import app.kaeru.ui.common.details.EpisodeCell
import app.kaeru.ui.common.home.HomeUiState
import app.kaeru.ui.common.player.PlayerUiState
import app.kaeru.ui.common.player.skipLabel
import app.kaeru.ui.common.settings.SettingsUiState
import app.kaeru.ui.common.theme.KaeruTvTheme
import app.kaeru.ui.common.update.UpdateStage
import app.kaeru.ui.common.update.UpdateUiState
import app.kaeru.ui.tv.auth.TvLoginScreen
import app.kaeru.ui.tv.auth.TvPairingStatus
import app.kaeru.ui.tv.auth.TvPairingUiState
import app.kaeru.ui.tv.details.TvTitleScreen
import app.kaeru.ui.tv.home.TvHomeScreen
import app.kaeru.ui.tv.player.TvPanelRung
import app.kaeru.ui.tv.player.TvPlayerHeader
import app.kaeru.ui.tv.player.PlayerGutter
import app.kaeru.ui.tv.player.TvPlayerPanel
import app.kaeru.ui.tv.player.TvSkipButton
import app.kaeru.ui.tv.player.rememberTvPanelContent
import app.kaeru.ui.tv.player.rememberTvPlayerClock
import app.kaeru.ui.tv.settings.TvSettingsScreen
import app.kaeru.ui.tv.update.TvUpdatesScreen
import java.io.File
import java.time.Duration
import java.time.Instant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Every television screen, composed and drawn at the size a television reports, and measured.
 *
 * This is the test the arithmetic in `TvLayoutBudgetTest` cannot be: that one adds up the terms
 * somebody remembered to write down, and the screens overflowed by 36dp for weeks because one term
 * — a row's own heading above the cards in the same viewport — was not among them. Here nothing is
 * modelled. The screen is composed at `w960dp-h540dp-television` with the real `manrope.ttf` and
 * `GraphicsMode.NATIVE`, which is what makes Robolectric measure text with the font the app ships
 * rather than with a stand-in whose metrics do not move with the size, and the numbers are read off
 * the semantics tree afterwards.
 *
 * Three questions are asked of every screen, and each of them was a fault somebody had to see on a
 * television to find:
 *
 * 1. **Is anything laid out past the bottom of the panel?** `positionInRoot` plus `size` is where a
 *    node actually is, whatever its parent believes.
 * 2. **Is what is on the panel drawn whole?** `boundsInRoot` is what the parents let through, so a
 *    heading reduced to a 9dp sliver under the hero band, or a focus ring shaved off by a lazy
 *    list's own clipping, is the difference between the two boxes.
 * 3. **Does a line of text have the box its glyphs need?** A `Column` of a fixed height measures its
 *    last child against whatever is left, and what was left of the login screen was 8dp of a 30dp
 *    line — so the tops of «Ждём телефон…» were drawn and nothing else. `GetTextLayoutResult` knows
 *    how tall the paragraph it laid out is; the node knows how much room it was given.
 *
 * `captureToImage()` does not work under Robolectric — its `forceRedraw` waits on a vsync that never
 * arrives — so the pictures, when they are asked for, are taken by drawing the decor view into a
 * bitmap. That is a real rasterisation and it is not free, so it happens only when `KAERU_RENDER_DIR`
 * names somewhere to put them:
 *
 * ```
 * KAERU_RENDER_DIR=/tmp/tv ./gradlew :app:testDebugUnitTest --tests '*TvRenderBudgetTest'
 * ```
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "w960dp-h540dp-television-notnight-xhdpi")
class TvRenderBudgetTest {

    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    // --- the home screen, in the four states a viewer opens it in ----------------------------

    @Test
    fun `home fits the panel with nothing above the band`() {
        showHome(tvPreviewHome())
        panel("home-plain").homeFits(NEW_EPISODES)
    }

    @Test
    fun `home fits under the offline strip`() {
        showHome(tvPreviewHome().copy(offline = true))
        panel("home-offline").homeFits(NEW_EPISODES)
    }

    @Test
    fun `home fits under the update strip`() {
        showHome(tvPreviewHome().copy(updateVersion = "0.4.0"))
        panel("home-update").homeFits(NEW_EPISODES)
    }

    /** The D-pad walks down into «Продолжить», which is the row below the one the screen opens on. */
    @Test
    fun `home fits with the remote on the second row`() {
        showHome(tvPreviewHome())
        compose.waitForIdle()
        // The column composes about one row, so the card below does not exist as a node until the
        // list is taken to it.
        compose.onNode(SemanticsMatcher.keyIsDefined(SemanticsProperties.VerticalScrollAxisRange))
            .performScrollToIndex(1)
        compose.waitForIdle()
        compose.onNode(hasText(FRIEREN) and hasClickAction())
            .performSemanticsAction(SemanticsActions.RequestFocus)
        panel("home-second-row").homeFits(CONTINUE)
    }

    // --- the title card -----------------------------------------------------------------------

    /**
     * The longest title card the catalogue produces: a name that runs past the column, three
     * sentences of description, five chips of metadata and a full season of 24 episodes.
     *
     * The left column is asserted to need no scrolling at all. It is scrollable — a television that
     * one day carries a longer name still has to reach «Развернуть» — but a title card whose
     * description is sliced through by the bottom of the panel is the first thing a viewer sees,
     * and `VerticalScrollAxisRange.maxValue` is exactly the number that says so.
     */
    @Test
    fun `the title card fits its left column with nothing to scroll`() {
        compose.setContent {
            KaeruTvTheme {
                TvTitleScreen(
                    state = titleState(),
                    onRetry = {},
                    onStatus = {},
                    onPlay = { _, _ -> },
                    onLoadTranslations = {},
                    onPickTranslation = {},
                    onMarkWatched = {},
                    onMarkUnwatched = {},
                )
            }
        }

        val panel = panel("title")
        panel.textIsNeverSqueezed()

        val column = panel.leftColumn()
        assertEquals(
            "title: the left column has ${panel.scrollMax(column)}dp of content below the panel",
            0f,
            panel.scrollMax(column),
            TOLERANCE,
        )
        panel.nothingBelow(PANEL - SAFE, column)
        panel.everythingUnderIsWhole(column)

        val name = panel.saying(FRIEREN)
        assertTrue("title: the name runs to ${name.lines()} lines", name.lines() <= 2)
        panel.wholeAndOnThePanel(panel.saying(EXPAND))
        panel.report("left column", panel.deepest(column), PANEL - SAFE)
    }

    // --- the login screen ---------------------------------------------------------------------

    @Test
    fun `the login screen draws every line it says`() {
        compose.setContent {
            KaeruTvTheme {
                TvLoginScreen(
                    authorizeUrl = AUTHORIZE_URL,
                    state = AuthUiState(loggedIn = false),
                    pairing = TvPairingUiState(
                        deviceName = "Гостиная",
                        pairingUri = "kaeru://pair?host=192.168.1.7&port=41234&nonce=n&name=Гостиная",
                        expiresAt = NOW.plus(Duration.ofMinutes(5)),
                        status = TvPairingStatus.WAITING,
                    ),
                    code = "",
                    onCode = {},
                    onSubmit = {},
                    onNewQr = {},
                )
            }
        }

        val panel = panel("login")
        panel.textIsNeverSqueezed()
        panel.nothingBelow(PANEL, panel.root)
        panel.everythingUnderIsWhole(panel.root)
        panel.wholeAndOnThePanel(panel.saying(WAITING_FOR_PHONE))
        panel.report("pairing column", panel.deepest(panel.root), PANEL)
    }

    // --- the screens the type scale only has to leave alone -------------------------------------

    @Test
    fun `settings opens with every row inside the fold drawn whole`() {
        showSettings()
        panel("settings-top").scrollingScreenFits()
    }

    @Test
    fun `settings reaches its last row`() {
        showSettings()
        compose.onNode(hasScrollAction()).performScrollToNode(hasText(CHECK_UPDATES))
        val panel = panel("settings-bottom")
        panel.scrollingScreenFits()
        panel.wholeAndOnThePanel(panel.saying(CHECK_UPDATES))
    }

    @Test
    fun `the player panel fits over the picture`() {
        compose.setContent {
            KaeruTvTheme {
                val content = rememberTvPanelContent(playerState)
                val clock = rememberTvPlayerClock(playerState)
                val rungFocus = remember { TvPanelRung.entries.associateWith { FocusRequester() } }
                Box(Modifier.fillMaxSize().background(Color(0xFF05070C))) {
                    TvPlayerHeader(playerState.title, Modifier.align(Alignment.TopStart))
                    Column(Modifier.align(Alignment.BottomStart).fillMaxWidth()) {
                        TvPlayerPanel(
                            content = content,
                            clock = clock,
                            rungFocus = rungFocus,
                            onPickEpisode = {},
                            onPickTranslation = {},
                            onPickQuality = {},
                            onTogglePlayPause = {},
                            onSeekBy = {},
                            onSkipIntro = {},
                            onNext = {},
                        )
                    }
                }
            }
        }

        val panel = panel("player-panel")
        panel.textIsNeverSqueezed()
        panel.nothingBelow(PANEL, panel.root)
        panel.everythingUnderIsWhole(panel.root)
        panel.report("controls", panel.deepest(panel.root), PANEL)
    }

    @Test
    fun `the skip button stands over the panel and is drawn whole`() {
        compose.setContent {
            KaeruTvTheme {
                val content = rememberTvPanelContent(playerState)
                val clock = rememberTvPlayerClock(playerState)
                val rungFocus = remember { TvPanelRung.entries.associateWith { FocusRequester() } }
                Box(Modifier.fillMaxSize().background(Color(0xFF05070C))) {
                    TvPlayerHeader(playerState.title, Modifier.align(Alignment.TopStart))
                    Column(Modifier.align(Alignment.BottomStart).fillMaxWidth()) {
                        // Where the screen puts it: above the panel, hard against the right
                        // gutter, with the panel still standing underneath.
                        TvSkipButton(
                            label = skipLabel(SkipKind.OPENING),
                            onSkip = {},
                            modifier = Modifier
                                .padding(end = PlayerGutter, bottom = KaeruTokens.Space4)
                                .align(Alignment.End),
                        )
                        TvPlayerPanel(
                            content = content,
                            clock = clock,
                            rungFocus = rungFocus,
                            onPickEpisode = {},
                            onPickTranslation = {},
                            onPickQuality = {},
                            onTogglePlayPause = {},
                            onSeekBy = {},
                            onSkipIntro = {},
                            onNext = {},
                        )
                    }
                }
            }
        }

        val panel = panel("player-skip")
        panel.textIsNeverSqueezed()
        panel.nothingBelow(PANEL, panel.root)
        panel.everythingUnderIsWhole(panel.root)
        panel.wholeAndOnThePanel(panel.saying(skipLabel(SkipKind.OPENING)))
    }

    @Test
    fun `the updates page opens on a button that is drawn whole`() {
        compose.setContent {
            KaeruTvTheme {
                TvUpdatesScreen(
                    state = UpdateUiState(
                        installedVersion = "0.3.0",
                        stage = UpdateStage.AVAILABLE,
                        checkedAt = NOW,
                        release = UpdateRelease(
                            version = "0.4.0",
                            publishedAt = NOW.minus(Duration.ofHours(1)),
                            notes = (1..20).joinToString("\n") { "• строка релиза номер $it" },
                            apkUrl = "https://example.test/Kaeru-0.4.0.apk",
                            apkName = "Kaeru-0.4.0.apk",
                            sizeBytes = 31_457_280,
                        ),
                    ),
                    onCheck = {},
                    onDownload = {},
                    onInstall = {},
                    onAllowInstalls = {},
                )
            }
        }

        val panel = panel("updates")
        panel.scrollingScreenFits()
        panel.wholeAndOnThePanel(panel.saying(DOWNLOAD))
    }

    // --- what the screens are shown -------------------------------------------------------------

    private fun showHome(state: HomeUiState) = compose.setContent {
        KaeruTvTheme {
            TvHomeScreen(
                state = state,
                onRefresh = {},
                onPlay = { _, _ -> },
                onDetails = {},
                onSeason = {},
                onRetrySeason = {},
                onSearch = {},
                onUpdate = {},
            )
        }
    }

    private fun showSettings() = compose.setContent {
        KaeruTvTheme {
            TvSettingsScreen(
                state = SettingsUiState(
                    accountLoading = false,
                    account = Account(id = 1, nickname = "vitaliy", avatarUrl = null),
                    autoplayNext = true,
                    defaultQuality = Quality.P720,
                    watchedThreshold = 0.9f,
                    studiosChosen = true,
                ),
                onSignOut = {},
                onAutoplay = {},
                onQuality = {},
                onThreshold = {},
                onStudioUp = {},
                onStudioDown = {},
                onStudioRemove = {},
                onStudiosReset = {},
                onRetryAccount = {},
                onUpdates = {},
            )
        }
    }

    private val anime = Anime(
        id = 1,
        nameRu = FRIEREN,
        nameRomaji = "Sousou no Frieren",
        posterUrl = null,
        screenshotUrls = emptyList(),
        status = AnimeStatus.RELEASED,
        episodes = 24,
        episodesAired = 24,
        nextEpisodeAt = null,
        score = 9.1,
        year = 2024,
        studio = "Madhouse",
        // Three sentences, the length Shikimori actually returns.
        description = "Эльфийка-волшебница Фрирен пережила своих спутников и только теперь начинает " +
            "понимать, чем для неё были эти десять лет пути и что они значили для них. " +
            "Вместе с ученицей она отправляется на север, к месту, где покоятся души умерших, " +
            "и по дороге встречает тех, кого её прежний отряд когда-то спас. " +
            "Это история о том, как долгая жизнь учится измерять себя чужими короткими.",
    )

    private fun titleState() = DetailsUiState(
        entry = LibraryEntry(anime, UserRate(1L, 1, ListStatus.WATCHING, 17, NOW), null),
        anime = anime,
        refreshing = false,
        translations = listOf(
            RankedTranslation(Translation(1, "AniLibria.TV", TranslationKind.VOICE, 24), true),
            RankedTranslation(Translation(2, "AniDUB", TranslationKind.VOICE, 24), false),
        ),
    )

    private val playerState = PlayerUiState(
        title = FRIEREN,
        episode = 7,
        availableEpisodes = 24,
        translationTitle = "AniLibria.TV",
        translationId = 1,
        isPlaying = false,
        isBuffering = false,
        positionMs = 600_000,
        bufferedPositionMs = 900_000,
        durationMs = 1_440_000,
        quality = Quality.P720,
        qualities = listOf(Quality.P480, Quality.P720, Quality.P1080),
        translations = listOf(
            RankedTranslation(Translation(1, "AniLibria.TV", TranslationKind.VOICE, 24), true),
            RankedTranslation(Translation(2, "AniDUB", TranslationKind.VOICE, 24), false),
            RankedTranslation(Translation(3, "Студийная банда", TranslationKind.VOICE, 24), false),
            RankedTranslation(Translation(4, "Субтитры Crunchyroll", TranslationKind.SUBTITLES, 24), false),
        ),
        nextEpisodeAvailable = true,
        episodes = (1..24).map { EpisodeCell(it, watched = it < 7, progress = null, aired = true) },
    )

    // --- measuring ------------------------------------------------------------------------------

    /** Composes nothing and measures everything: the tree as it stands, in device-independent pixels. */
    private fun panel(name: String): Panel {
        compose.waitForIdle()
        val root = compose.onRoot(useUnmergedTree = true).fetchSemanticsNode()
        val panel = Panel(name, root)
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
            // Beside the picture, the tree it was drawn from: where every node was put, how wide it
            // is, how many lines it took and whether it was cut. It is the report the audit that
            // found all this produced, and it is what a next one would want.
            panel.dump()
        }
        return panel
    }
}

/** What the panel is, in the units every number below is reported in. */
private const val DENSITY = 2f
private const val PANEL = 540f
private const val PANEL_WIDTH = 960f
private val SAFE = TvLayout.SafeVertical.value

/** Half a device-independent pixel: rounding, not a fault. */
private const val TOLERANCE = 0.5f

private const val RENDER_DIR = "KAERU_RENDER_DIR"

private val NOW: Instant = Instant.parse("2026-09-13T20:00:00Z")
private const val NEW_EPISODES = "Новые серии"
private const val CONTINUE = "Продолжить"
private const val FRIEREN = "Фрирен, провожающая в последний путь"
private const val EXPAND = "Развернуть"
private const val WAITING_FOR_PHONE = "Ждём телефон…"
private const val CHECK_UPDATES = "Проверить обновления"
private const val DOWNLOAD = "Скачать и установить"
private const val AUTHORIZE_URL =
    "https://shikimori.one/oauth/authorize?client_id=cid&response_type=code"

private fun Float.dp(): Float = this / DENSITY

private fun Float.r(): String = String.format("%.1f", this)

/** Where a node was put, whatever its parent believes. */
private fun SemanticsNode.topDp(): Float = positionInRoot.y.dp()

private fun SemanticsNode.bottomDp(): Float = (positionInRoot.y + size.height).dp()

private fun SemanticsNode.heightDp(): Float = size.height.toFloat().dp()

/** And how much of it the parents actually let through to the panel. */
private fun SemanticsNode.drawnDp(): Float = (boundsInRoot.bottom - boundsInRoot.top).dp()

private fun SemanticsNode.textLayout(): TextLayoutResult? {
    val out = mutableListOf<TextLayoutResult>()
    config.getOrNull(SemanticsActions.GetTextLayoutResult)?.action?.invoke(out)
    return out.firstOrNull()
}

/** How tall the paragraph this node laid out actually is, whatever room the node was given. */
private fun SemanticsNode.lineBoxDp(): Float? =
    textLayout()?.takeIf { it.lineCount > 0 }?.let { it.getLineBottom(it.lineCount - 1).dp() }

private fun SemanticsNode.lines(): Int = textLayout()?.lineCount ?: 0

private fun SemanticsNode.words(): String? =
    config.getOrNull(SemanticsProperties.Text)?.joinToString(" ") { it.text }

private fun SemanticsNode.label(): String =
    words()
        ?: config.getOrNull(SemanticsProperties.ContentDescription)?.joinToString(" ")
        ?: config.getOrNull(SemanticsProperties.TestTag)
        ?: "node $id"

private fun flatten(node: SemanticsNode): List<SemanticsNode> =
    listOf(node) + node.children.flatMap(::flatten)

/**
 * One composed screen, and the questions worth asking it.
 *
 * The tree is the unmerged one on purpose: a card's caption is merged into the card it belongs to,
 * and the caption drawn off the bottom of the panel is exactly the node this has to see.
 */
private class Panel(val name: String, val root: SemanticsNode) {

    val nodes: List<SemanticsNode> = flatten(root)

    fun saying(text: String): SemanticsNode =
        nodes.firstOrNull { it.words() == text } ?: error("$name: nothing on the panel says «$text»")

    fun focused(): SemanticsNode =
        nodes.firstOrNull { it.config.getOrNull(SemanticsProperties.Focused) == true }
            ?: error("$name: nothing on the panel has the focus")

    fun scrollMax(node: SemanticsNode): Float =
        node.config.getOrNull(SemanticsProperties.VerticalScrollAxisRange)?.maxValue?.invoke()?.dp() ?: 0f

    /**
     * How far down the panel the content under [from] actually reaches.
     *
     * Containers as tall as [from] itself say nothing about fit — a full-bleed backdrop is 540dp by
     * design — so the number is taken over what they hold.
     */
    fun deepest(from: SemanticsNode): Float = flatten(from)
        .filter { it.id != from.id && it.heightDp() < from.heightDp() - TOLERANCE }
        .maxOf { it.bottomDp() }

    /** The column a title card puts the name, the controls and the description in. */
    fun leftColumn(): SemanticsNode = nodes
        .filter { it.config.getOrNull(SemanticsProperties.VerticalScrollAxisRange) != null }
        .minByOrNull { it.positionInRoot.x }
        ?: error("$name: no scrolling column on this screen")

    /** Every line of text has the box its own glyphs need. */
    fun textIsNeverSqueezed() {
        nodes.forEach { node ->
            val box = node.lineBoxDp() ?: return@forEach
            assertTrue(
                "$name: «${node.label()}» was given ${node.heightDp().r()}dp for ${node.lines()} " +
                    "line(s) needing ${box.r()}dp, so only the top of it is drawn",
                node.heightDp() + TOLERANCE >= box,
            )
        }
    }

    /** Nothing under [from] is laid out past [limit]. */
    fun nothingBelow(limit: Float, from: SemanticsNode) {
        flatten(from).forEach { node ->
            assertTrue(
                "$name: «${node.label()}» runs to ${node.bottomDp().r()}dp of a ${limit.r()}dp panel",
                node.bottomDp() <= limit + TOLERANCE,
            )
        }
    }

    /** Everything under [from] is drawn in full rather than shaved by a parent's clipping. */
    fun everythingUnderIsWhole(from: SemanticsNode) = flatten(from).forEach(::assertWhole)

    fun wholeAndOnThePanel(node: SemanticsNode) {
        assertWhole(node)
        assertTrue(
            "$name: «${node.label()}» ends at ${node.bottomDp().r()}dp, past the panel",
            node.bottomDp() <= PANEL + TOLERANCE,
        )
    }

    private fun assertWhole(node: SemanticsNode) {
        // A card the row has scrolled past the side of the panel has no visible box at all, and
        // that is the row working rather than a card being cut: the question here is only ever
        // vertical, so a node that is off to the side is not one to ask it of.
        if (node.isOffToTheSide()) return
        assertTrue(
            "$name: «${node.label()}» is ${node.heightDp().r()}dp tall at ${node.topDp().r()}dp and " +
                "only ${node.drawnDp().r()}dp of it is drawn",
            node.drawnDp() + TOLERANCE >= node.heightDp(),
        )
    }

    /**
     * The home screen, in whichever of its four states it was composed in.
     *
     * Two things have to hold and neither of them did. Everything above the rows — the hero band and
     * whatever notice sits over it — is drawn whole; and the row the remote is on is whole *inside
     * the rows' own viewport*, heading and card and caption and the room the card grows into when it
     * takes the focus. The 36dp this screen was over by lived exactly there: the heading was drawn
     * as a 9dp sliver behind the hero's action line and the row's bottom 12dp of focus padding was
     * outside the viewport, where a lazy list clips it.
     */
    fun homeFits(heading: String) {
        textIsNeverSqueezed()

        val rows = nodes.first { it.config.getOrNull(SemanticsProperties.VerticalScrollAxisRange) != null }
        // The band and its notice: everything on the screen that is not one of the rows.
        nodes.filter { it.lineBoxDp() != null && !it.isUnder(rows) }.forEach(::assertWhole)

        val card = focused()
        val row = generateSequence(card) { it.parent }
            .first { it.config.getOrNull(SemanticsProperties.HorizontalScrollAxisRange) != null }
        val floor = PANEL - SAFE

        assertTrue(
            "$name: the focused row runs to ${row.bottomDp().r()}dp, past the ${floor.r()}dp the " +
                "rows have — a card's focus ring is drawn outside the viewport and clipped away",
            row.bottomDp() <= floor + TOLERANCE,
        )
        assertWhole(row)
        flatten(card).forEach(::assertWhole)
        val head = saying(heading)
        wholeAndOnThePanel(head)
        assertTrue(
            "$name: the row heading «$heading» is laid out at ${head.topDp().r()}dp, above the " +
                "${TvLayout.BandTotal.value.r()}dp the band takes",
            head.topDp() + TOLERANCE >= TvLayout.BandTotal.value,
        )

        // A focused card grows six per cent about its own centre, and the row has to have reserved
        // the room: a lazy list clips to its own bounds, ring and all.
        val grown = card.bottomDp() + card.heightDp() * (KaeruTokens.FocusScale - 1f) / 2f
        assertTrue(
            "$name: the focused card grows to ${grown.r()}dp of the ${floor.r()}dp the rows have",
            grown <= floor + TOLERANCE,
        )
        report("focused row", row.bottomDp(), floor)
    }

    /**
     * A screen that legitimately continues past the fold — settings, the updates page.
     *
     * The question is not whether it all fits on one panel; it is whether what the fold does show is
     * shown whole. Anything laid out entirely inside the viewport has no excuse for being cut.
     */
    fun scrollingScreenFits() {
        textIsNeverSqueezed()
        val fold = PANEL - SAFE
        nodes
            .filter { it.topDp() >= -TOLERANCE && it.bottomDp() <= fold + TOLERANCE }
            .forEach(::assertWhole)
    }

    fun dump() {
        nodes.forEach {
            println(
                "A|$name|${it.label()}|y=${it.topDp().r()}..${it.bottomDp().r()}" +
                    "|x=${(it.positionInRoot.x.dp()).r()}..${((it.positionInRoot.x + it.size.width).dp()).r()}" +
                    "|lines=${it.lines()}|cut=${it.textLayout()?.didOverflowHeight}",
            )
        }
    }

    /** The one line a reader of the test output is looking for: how much room was left over. */
    fun report(what: String, deepest: Float, floor: Float) {
        println("BUDGET|$name|$what|deepest=${deepest.r()}dp|floor=${floor.r()}dp|slack=${(floor - deepest).r()}dp")
    }
}

/**
 * Whether this node is off the side of the panel, or of the row that scrolls it.
 *
 * A chip a strip has scrolled past its own edge has no visible box at all, which is the row doing
 * its job rather than a chip being cut. Everything else with an empty box — a caption under the
 * bottom of the panel, a control a fixed-height column had no room left for — is a fault, so the
 * side is the only direction excused here.
 */
private fun SemanticsNode.isOffToTheSide(): Boolean {
    val left = positionInRoot.x
    val right = left + size.width
    if (right <= TOLERANCE || left >= PANEL_WIDTH * DENSITY - TOLERANCE) return true
    val row = generateSequence(parent) { it.parent }
        .firstOrNull { it.config.getOrNull(SemanticsProperties.HorizontalScrollAxisRange) != null }
        ?: return false
    return right <= row.boundsInRoot.left + TOLERANCE || left >= row.boundsInRoot.right - TOLERANCE
}

private fun SemanticsNode.isUnder(ancestor: SemanticsNode): Boolean =
    generateSequence(parent) { it.parent }.any { it.id == ancestor.id }
