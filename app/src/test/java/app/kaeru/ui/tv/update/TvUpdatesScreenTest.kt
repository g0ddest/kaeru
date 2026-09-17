package app.kaeru.ui.tv.update

import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.hasScrollAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.pressKey
import app.kaeru.domain.update.UpdateRelease
import app.kaeru.ui.common.design.KaeruTokens
import app.kaeru.ui.common.theme.KaeruTvTheme
import app.kaeru.ui.common.update.UpdateStage
import app.kaeru.ui.common.update.UpdateUiState
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.time.Instant

/**
 * «Обновления» on a 1080p panel, asked the questions only a composition can answer: does the
 * remote open on the thing worth pressing, is the whole page reachable, and does what it lands on
 * land where a television actually draws.
 *
 * The screen has one job and it is the last step of it — a press that installs a build — so a
 * button the D-pad cannot reach is the whole feature not working.
 */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w960dp-h540dp-television-notnight-mdpi")
class TvUpdatesScreenTest {
    @get:Rule val compose = createComposeRule()

    private val release = UpdateRelease(
        version = "0.4.0",
        publishedAt = Instant.parse("2026-09-16T08:00:00Z"),
        notes = (1..20).joinToString("\n") { "• строка релиза номер $it" },
        apkUrl = "https://example.test/Kaeru-0.4.0.apk",
        apkName = "Kaeru-0.4.0.apk",
        sizeBytes = 31_457_280,
    )

    private fun show(state: UpdateUiState, onDownload: () -> Unit = {}, onCheck: () -> Unit = {}) =
        compose.setContent {
            KaeruTvTheme {
                TvUpdatesScreen(
                    state = state,
                    onCheck = onCheck,
                    onDownload = onDownload,
                    onInstall = {},
                    onAllowInstalls = {},
                )
            }
        }

    private fun available(message: String? = null, permission: Boolean = false) = UpdateUiState(
        installedVersion = "0.3.0",
        stage = UpdateStage.AVAILABLE,
        checkedAt = Instant.parse("2026-09-16T09:00:00Z"),
        release = release,
        message = message,
        permissionNeeded = permission,
    )

    @Test
    fun `the remote opens on the button, not at the top of the page`() {
        show(available())

        compose.onNodeWithText(DOWNLOAD).assertIsFocused()
    }

    /** With nothing to download, «Проверить» is the only thing worth pressing and takes the focus. */
    @Test
    fun `with nothing to install the remote opens on the check`() {
        show(
            UpdateUiState(
                installedVersion = "0.3.0",
                stage = UpdateStage.UP_TO_DATE,
                checkedAt = Instant.parse("2026-09-16T09:00:00Z"),
            ),
        )

        compose.onNodeWithText(CHECK).assertIsFocused()
    }

    @Test
    fun `the page says what is installed and what is published`() {
        show(available())

        compose.onNodeWithText("Kaeru 0.3.0").assertIsDisplayed()
        compose.onNodeWithText("Доступна версия 0.4.0").assertIsDisplayed()
        compose.onNodeWithText("16 сентября 2026, 30 МБ").assertIsDisplayed()
    }

    /**
     * The fault every television page in this app has had at least once: a row below the fold that
     * a remote cannot reach. A D-pad scrolls by moving focus, so the bottom of the page has to be
     * something to land on — here, «Проверить» under a release note twenty lines long.
     */
    @Test
    fun `the D-pad reaches the bottom of the page past a long release note`() {
        show(available())

        repeat(20) { compose.onRoot().performKeyInput { pressKey(Key.DirectionDown) } }

        compose.onNodeWithText(CHECK).assertIsDisplayed().assertIsFocused()
    }

    @Test
    fun `the focused button lands clear of the edge the panel crops`() {
        show(available())

        val button = compose.onNodeWithText(DOWNLOAD).fetchSemanticsNode()
        val panel = compose.onRoot().fetchSemanticsNode().size.height.toFloat()
        val top = button.positionInRoot.y
        val bottom = top + button.size.height
        assertTrue(
            "the button runs from $top to $bottom on a panel $panel tall",
            top >= SAFE_MARGIN && bottom <= panel - SAFE_MARGIN,
        )
    }

    /**
     * Nothing readable starts off the left of the picture: the closed rail owns the first 80dp,
     * and a row drawn under it is a row with icons on top of it.
     *
     * The focused control is measured with its focus growth added back. A focused thing on this
     * device gains six per cent, half of it to each side, and that overhang is the ring rather
     * than the content — what has to sit on the gutter is where the row is laid out.
     */
    @Test
    fun `nothing starts under the navigation rail`() {
        show(available())

        listOf("Kaeru 0.3.0", "Доступна версия 0.4.0", DOWNLOAD).forEach { text ->
            val node = compose.onNodeWithText(text).fetchSemanticsNode()
            val growth = node.size.width * (KaeruTokens.FocusScale - 1f) / 2f
            assertTrue(
                "«$text» is laid out at ${node.positionInRoot.x + growth}",
                node.positionInRoot.x + growth >= RAIL,
            )
        }
    }

    /** A failure sits under the release rather than in place of it: both are true at once. */
    @Test
    fun `a failure never takes the release off the screen`() {
        show(available(message = "Нет связи"))

        compose.onNodeWithText("Доступна версия 0.4.0").assertIsDisplayed()
        compose.onNode(hasScrollAction()).performScrollToNode(hasText("Нет связи"))
        compose.onNodeWithText("Нет связи").assertIsDisplayed()
    }

    @Test
    fun `the permission is explained before it is asked for`() {
        show(available(permission = true))

        compose.onNode(hasScrollAction()).performScrollToNode(hasText(ALLOW))
        compose.onNodeWithText(ALLOW).assertIsDisplayed()
        compose
            .onNodeWithText("Android один раз спросит, можно ли Kaeru устанавливать приложения")
            .assertIsDisplayed()
    }

    @Test
    fun `a download in progress shows how far it has got and offers no check`() {
        show(
            UpdateUiState(
                installedVersion = "0.3.0",
                stage = UpdateStage.DOWNLOADING,
                release = release,
                downloadedBytes = 15_728_640,
            ),
        )

        compose.onNodeWithText("15 МБ из 30 МБ").assertIsDisplayed()
        // A transfer is already the answer «Проверить» would go and fetch, so the button is gone.
        assertTrue(
            "«$CHECK» should not be offered during a transfer",
            compose.onAllNodesWithText(CHECK).fetchSemanticsNodes().isEmpty(),
        )
    }

    /**
     * The fault this closes: `canCheck` and the primary action were both false while checking and
     * while downloading, so on those two stages nothing on the page was focusable at all. A
     * transfer over a television's Wi-Fi is minutes, and for the whole of it the D-pad had nowhere
     * on this screen to be — it walks out and does not come back.
     *
     * Driven through one composition rather than six, which also pins the second half of the
     * fault: the claim latched on the first focus it ever got, so «Установить» appearing where the
     * progress had been was never focused either. The remote follows the page.
     */
    @Test
    fun `every stage of the page has somewhere for the remote to be`() {
        val stage = mutableStateOf(UpdateStage.CHECKING)
        compose.setContent {
            KaeruTvTheme {
                TvUpdatesScreen(
                    state = UpdateUiState(
                        installedVersion = "0.3.0",
                        stage = stage.value,
                        checkedAt = Instant.parse("2026-09-16T09:00:00Z"),
                        release = release.takeIf { stage.value != UpdateStage.UNKNOWN },
                        downloadedBytes = 15_728_640,
                    ),
                    onCheck = {},
                    onDownload = {},
                    onInstall = {},
                    onAllowInstalls = {},
                )
            }
        }

        listOf(
            UpdateStage.CHECKING to CHECKING_LINE,
            UpdateStage.AVAILABLE to DOWNLOAD,
            UpdateStage.DOWNLOADING to DOWNLOADING_LINE,
            UpdateStage.READY to INSTALL,
            UpdateStage.UP_TO_DATE to CHECK,
            UpdateStage.UNKNOWN to CHECK,
        ).forEach { (next, focused) ->
            compose.runOnIdle { stage.value = next }
            compose.waitForIdle()

            compose.onNodeWithText(focused, substring = true).assertIsFocused()
        }
    }

    private companion object {
        const val DOWNLOAD = "Скачать и установить"
        const val CHECK = "Проверить"
        const val ALLOW = "Разрешить установку"
        const val INSTALL = "Установить"
        const val CHECKING_LINE = "Проверяем"
        const val DOWNLOADING_LINE = "Скачиваем файл"

        /** Nothing readable sits closer than this to an edge a television may crop. */
        const val SAFE_MARGIN = 16f

        /** The closed navigation rail, which nothing on a screen may be drawn under. */
        const val RAIL = 80f
    }
}
