package app.kaeru.ui.mobile.update

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import app.kaeru.domain.update.UpdateRelease
import app.kaeru.ui.common.design.IconAction
import app.kaeru.ui.common.design.IndeterminateStrip
import app.kaeru.ui.common.design.KaeruTokens
import app.kaeru.ui.common.design.KaeruTopBar
import app.kaeru.ui.common.design.PrimaryButton
import app.kaeru.ui.common.design.RowHeader
import app.kaeru.ui.common.design.SecondaryButton
import app.kaeru.ui.common.theme.KaeruTheme
import app.kaeru.ui.common.update.UPDATES_ALLOW
import app.kaeru.ui.common.update.UPDATES_BACK
import app.kaeru.ui.common.update.UPDATES_CHECK
import app.kaeru.ui.common.update.UPDATES_CHECKING
import app.kaeru.ui.common.update.UPDATES_DOWNLOAD
import app.kaeru.ui.common.update.UPDATES_DOWNLOADING
import app.kaeru.ui.common.update.UPDATES_INSTALL
import app.kaeru.ui.common.update.UPDATES_INSTALLED
import app.kaeru.ui.common.update.UPDATES_LATEST
import app.kaeru.ui.common.update.UPDATES_PERMISSION
import app.kaeru.ui.common.update.UPDATES_READY
import app.kaeru.ui.common.update.UPDATES_READY_NOTE
import app.kaeru.ui.common.update.UPDATES_TITLE
import app.kaeru.ui.common.update.UPDATES_UNKNOWN
import app.kaeru.ui.common.update.UPDATES_UP_TO_DATE
import app.kaeru.ui.common.update.UpdateError
import app.kaeru.ui.common.update.UpdateHeadline
import app.kaeru.ui.common.update.UpdateNote
import app.kaeru.ui.common.update.UpdateNotes
import app.kaeru.ui.common.update.UpdateProgress
import app.kaeru.ui.common.update.UpdateStage
import app.kaeru.ui.common.update.UpdateUiState
import app.kaeru.ui.common.update.availableLine
import app.kaeru.ui.common.update.checkedLine
import app.kaeru.ui.common.update.downloadedLine
import app.kaeru.ui.common.update.installedLine
import app.kaeru.ui.common.update.releaseLine
import java.time.Instant

/**
 * «Обновления»: what is installed, what is published, and one button between them.
 *
 * Written as the settings page is — two named parts and prose inside them, with the control at the
 * end of the sentence that explains it — because this is the same kind of page. It is not a
 * dialog and not a notification: nothing here interrupts, nothing is dismissed, and the screen is
 * only ever reached by somebody who went looking for it.
 *
 * There is one amber button on screen at a time, and it is always the same one press: «Скачать и
 * установить», which fetches the file and opens the installer without asking again. «Проверить»
 * is quiet beside it, because pressing it is how you find out there is nothing to do.
 */
@Composable
fun UpdatesScreen(
    state: UpdateUiState,
    onBack: () -> Unit,
    onCheck: () -> Unit,
    onDownload: () -> Unit,
    onInstall: () -> Unit,
    onAllowInstalls: () -> Unit,
) {
    Column(Modifier.fillMaxSize()) {
        KaeruTopBar(
            title = UPDATES_TITLE,
            navigationIcon = { IconAction(Icons.AutoMirrored.Filled.ArrowBack, UPDATES_BACK, onBack) },
        )
        Column(
            Modifier
                .verticalScroll(rememberScrollState())
                .padding(top = KaeruTokens.Space2, bottom = KaeruTokens.Space8),
            verticalArrangement = Arrangement.spacedBy(KaeruTokens.Space8),
        ) {
            Section(UPDATES_INSTALLED) { UpdateNote(installedLine(state.installedVersion)) }
            Section(UPDATES_LATEST) {
                Latest(state, onDownload, onInstall)
                // Under the block it is about, never in place of it: a release already found stays
                // on screen when the check that would have refreshed it fails.
                state.message?.let { UpdateError(it) }
                if (state.permissionNeeded) {
                    UpdateNote(UPDATES_PERMISSION)
                    SecondaryButton(UPDATES_ALLOW, onClick = onAllowInstalls)
                }
                // Offered wherever asking again could change the answer. During a download it is
                // not: the answer is already known and the transfer is what is happening.
                if (state.stage.canCheck) SecondaryButton(UPDATES_CHECK, onClick = onCheck)
            }
        }
    }
}

/** The six states of the page, each with the one control that belongs under it. */
@Composable
private fun Latest(state: UpdateUiState, onDownload: () -> Unit, onInstall: () -> Unit) {
    when (state.stage) {
        UpdateStage.CHECKING -> {
            UpdateNote(UPDATES_CHECKING)
            IndeterminateStrip()
        }
        UpdateStage.UNKNOWN -> UpdateNote(UPDATES_UNKNOWN)
        UpdateStage.UP_TO_DATE -> {
            UpdateHeadline(UPDATES_UP_TO_DATE)
            checkedLine(state.checkedAt)?.let { UpdateNote(it) }
        }
        UpdateStage.AVAILABLE -> {
            val release = state.release ?: return
            ReleaseBlock(release)
            PrimaryButton(UPDATES_DOWNLOAD, onClick = onDownload)
        }
        UpdateStage.DOWNLOADING -> {
            val release = state.release ?: return
            UpdateHeadline(availableLine(release.version))
            UpdateNote(UPDATES_DOWNLOADING)
            UpdateProgress(state.progress, downloadedLine(state.downloadedBytes, release.sizeBytes))
        }
        UpdateStage.READY -> {
            UpdateHeadline(UPDATES_READY)
            UpdateNote(UPDATES_READY_NOTE)
            PrimaryButton(UPDATES_INSTALL, onClick = onInstall)
        }
    }
}

/** Which version, when it came out and what it weighs, and then what changed in it. */
@Composable
private fun ReleaseBlock(release: UpdateRelease) {
    UpdateHeadline(availableLine(release.version))
    releaseLine(release)?.let { UpdateNote(it) }
    release.notes.takeIf { it.isNotBlank() }?.let { UpdateNotes(it) }
}

/**
 * One named part of the page.
 *
 * The same shape the settings page uses — a quiet heading, thirty-two pixels of nothing between
 * parts, no card and no rule — written here rather than imported, because the settings screen's
 * components belong to the settings screen.
 */
@Composable
private fun Section(title: String, content: @Composable () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(KaeruTokens.Space3)) {
        RowHeader(title)
        Column(
            Modifier.padding(horizontal = KaeruTokens.GutterPhone),
            verticalArrangement = Arrangement.spacedBy(KaeruTokens.Space3),
        ) { content() }
    }
}

/**
 * Whether «Проверить» is worth offering.
 *
 * Not while a check is already running, and not during a transfer — in both of those the app is
 * already doing the thing the button would ask for.
 */
private val UpdateStage.canCheck: Boolean
    get() = this == UpdateStage.UNKNOWN || this == UpdateStage.UP_TO_DATE || this == UpdateStage.AVAILABLE

private val previewRelease = UpdateRelease(
    version = "0.4.0",
    publishedAt = Instant.parse("2026-09-16T08:00:00Z"),
    notes = "Что нового\n\n• Обновления внутри приложения\n• Исправлен плеер",
    apkUrl = "https://example.test/Kaeru-0.4.0.apk",
    apkName = "Kaeru-0.4.0.apk",
    sizeBytes = 31_457_280,
)

@Preview(showBackground = true, backgroundColor = 0xFF0B0C10, widthDp = 360, heightDp = 640)
@Composable
private fun UpdatesAvailablePreview() = KaeruTheme {
    UpdatesScreen(
        state = UpdateUiState(
            installedVersion = "0.3.0",
            stage = UpdateStage.AVAILABLE,
            checkedAt = Instant.parse("2026-09-16T08:00:00Z"),
            release = previewRelease,
        ),
        onBack = {}, onCheck = {}, onDownload = {}, onInstall = {}, onAllowInstalls = {},
    )
}

@Preview(showBackground = true, backgroundColor = 0xFF0B0C10, widthDp = 360, heightDp = 640)
@Composable
private fun UpdatesUpToDatePreview() = KaeruTheme {
    UpdatesScreen(
        state = UpdateUiState(
            installedVersion = "0.3.0",
            stage = UpdateStage.UP_TO_DATE,
            checkedAt = Instant.parse("2026-09-16T08:00:00Z"),
        ),
        onBack = {}, onCheck = {}, onDownload = {}, onInstall = {}, onAllowInstalls = {},
    )
}

@Preview(showBackground = true, backgroundColor = 0xFF0B0C10, widthDp = 360, heightDp = 640)
@Composable
private fun UpdatesDownloadingPreview() = KaeruTheme {
    UpdatesScreen(
        state = UpdateUiState(
            installedVersion = "0.3.0",
            stage = UpdateStage.DOWNLOADING,
            release = previewRelease,
            downloadedBytes = 12L * 1024 * 1024,
        ),
        onBack = {}, onCheck = {}, onDownload = {}, onInstall = {}, onAllowInstalls = {},
    )
}

@Preview(showBackground = true, backgroundColor = 0xFF0B0C10, widthDp = 360, heightDp = 640)
@Composable
private fun UpdatesOfflinePreview() = KaeruTheme {
    UpdatesScreen(
        state = UpdateUiState(
            installedVersion = "0.3.0",
            stage = UpdateStage.AVAILABLE,
            checkedAt = Instant.parse("2026-09-16T08:00:00Z"),
            release = previewRelease,
            message = "Нет связи",
        ),
        onBack = {}, onCheck = {}, onDownload = {}, onInstall = {}, onAllowInstalls = {},
    )
}
