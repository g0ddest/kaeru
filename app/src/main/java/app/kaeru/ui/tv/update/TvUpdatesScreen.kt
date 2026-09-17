package app.kaeru.ui.tv.update

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.tooling.preview.Devices
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import app.kaeru.domain.update.UpdateRelease
import app.kaeru.ui.common.design.IndeterminateStrip
import app.kaeru.ui.common.design.KaeruTokens
import app.kaeru.ui.common.design.PrimaryButton
import app.kaeru.ui.common.design.RowHeader
import app.kaeru.ui.common.design.SecondaryButton
import app.kaeru.ui.common.theme.KaeruTvTheme
import app.kaeru.ui.common.update.UPDATES_ALLOW
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
import app.kaeru.ui.tv.TvLayout
import app.kaeru.ui.tv.claimFocusWhenReady
import java.time.Instant

/** What each slot holds, so a button's node is reused for a button and not for a heading. */
private const val HEADING = "heading"
private const val ROW = "row"

/** One column of prose, at the width a sentence stays readable at across a room. */
private val TextColumn = 640.dp

/** The air above a section name, less the spacing the list already puts between items. */
private val SectionGap = KaeruTokens.Space8 - KaeruTokens.Space3

/** As wide as a button on this page gets: a control spanning 640dp reads as a banner. */
private val ButtonWidth = 320.dp

/**
 * How much of a release note a television shows.
 *
 * A D-pad scrolls by moving focus and nothing else, and a block of prose is not a thing to land
 * on — so a note taller than the panel would have a tail nobody could ever bring into view. Twelve
 * lines is more than any release of this app has needed and still leaves the button on screen.
 * The phone shows the whole of it, which is where a long note belongs anyway.
 */
private const val NOTES_LINES = 12

/**
 * «Обновления» on a television: the same page the phone shows, walked with a remote.
 *
 * A lazy list of individual rows rather than a column that scrolls, for the reason the television
 * settings page is one: focus search composes the next item whether or not it is on screen, and
 * the row that takes the focus is the exact thing brought into view. The remote opens on the
 * button — the only thing here worth pressing — rather than at the top of the page, so the one
 * action this screen exists for is one press away.
 *
 * There is no top bar and no back arrow. The drawer on the left is the way out of every screen on
 * this device, and back closes this page the way it closes a title card.
 */
@Composable
fun TvUpdatesScreen(
    state: UpdateUiState,
    onCheck: () -> Unit,
    onDownload: () -> Unit,
    onInstall: () -> Unit,
    onAllowInstalls: () -> Unit,
    modifier: Modifier = Modifier,
    listState: LazyListState = rememberLazyListState(),
) {
    val first = remember { FocusRequester() }
    // Latched by the focus arriving rather than by a request being accepted: a node inside a lazy
    // list that is attached but not yet placed takes the request and does nothing with it.
    var claimed by remember { mutableStateOf(false) }
    LaunchedEffect(state.stage) {
        if (!claimed) first.claimFocusWhenReady("the action on the television updates screen") { claimed }
    }
    val focusFirst = Modifier
        .focusRequester(first)
        .onFocusChanged { if (it.isFocused) claimed = true }

    LazyColumn(
        // The safe area is held outside the scrolling viewport, as it is on every other television
        // screen: a focused row asks to be brought into the viewport, and a viewport running to
        // the bottom of the panel brings it flush against the five per cent a television crops.
        modifier.fillMaxSize().padding(bottom = TvLayout.SafeVertical),
        state = listState,
        contentPadding = PaddingValues(top = TvLayout.SafeVertical),
        verticalArrangement = Arrangement.spacedBy(KaeruTokens.Space3),
    ) {
        heading(UPDATES_INSTALLED, first = true)
        row("installed") { UpdateNote(installedLine(state.installedVersion)) }

        heading(UPDATES_LATEST)
        latest(state, focusFirst, onDownload, onInstall)

        state.message?.let { message -> row("message") { UpdateError(message) } }

        if (state.permissionNeeded) {
            row("permission-note") { UpdateNote(UPDATES_PERMISSION) }
            row("permission") {
                SecondaryButton(UPDATES_ALLOW, onAllowInstalls, Modifier.widthIn(max = ButtonWidth))
            }
        }

        // The last stop on the page, and the one that makes the end of it reachable: a D-pad
        // moves the page by moving focus, so the bottom row has to be something to land on.
        if (state.stage.canCheck) {
            row("check") {
                SecondaryButton(
                    UPDATES_CHECK,
                    onCheck,
                    Modifier
                        .widthIn(max = ButtonWidth)
                        // The opening claim when there is nothing to download: «Проверить» is
                        // then the only thing on the page worth pressing.
                        .then(if (state.stage.hasPrimaryAction) Modifier else focusFirst),
                )
            }
        }
    }
}

/** The six states of the page, each with the one control that belongs under it. */
private fun LazyListScope.latest(
    state: UpdateUiState,
    focusFirst: Modifier,
    onDownload: () -> Unit,
    onInstall: () -> Unit,
) {
    when (state.stage) {
        UpdateStage.CHECKING -> row("checking") {
            Column(verticalArrangement = Arrangement.spacedBy(KaeruTokens.Space3)) {
                UpdateNote(UPDATES_CHECKING)
                IndeterminateStrip(Modifier.widthIn(max = ButtonWidth))
            }
        }
        UpdateStage.UNKNOWN -> row("unknown") { UpdateNote(UPDATES_UNKNOWN) }
        UpdateStage.UP_TO_DATE -> row("up-to-date") {
            Column(verticalArrangement = Arrangement.spacedBy(KaeruTokens.Space2)) {
                UpdateHeadline(UPDATES_UP_TO_DATE)
                checkedLine(state.checkedAt)?.let { UpdateNote(it) }
            }
        }
        UpdateStage.AVAILABLE -> {
            val release = state.release ?: return
            row("release") {
                Column(verticalArrangement = Arrangement.spacedBy(KaeruTokens.Space2)) {
                    UpdateHeadline(availableLine(release.version))
                    releaseLine(release)?.let { UpdateNote(it) }
                }
            }
            // The button before the notes, which is the opposite of the phone and deliberate: on a
            // remote the first thing under the headline has to be the thing you came to press, and
            // a note of unknown length between them would put it anywhere.
            row("download") {
                PrimaryButton(UPDATES_DOWNLOAD, onDownload, focusFirst.widthIn(max = ButtonWidth))
            }
            release.notes.takeIf { it.isNotBlank() }?.let { notes ->
                row("notes") { UpdateNotes(notes, maxLines = NOTES_LINES) }
            }
        }
        UpdateStage.DOWNLOADING -> {
            val release = state.release ?: return
            row("downloading") {
                Column(verticalArrangement = Arrangement.spacedBy(KaeruTokens.Space2)) {
                    UpdateHeadline(availableLine(release.version))
                    UpdateNote(UPDATES_DOWNLOADING)
                    UpdateProgress(
                        state.progress,
                        downloadedLine(state.downloadedBytes, release.sizeBytes),
                        stripWidth = ButtonWidth,
                    )
                }
            }
        }
        UpdateStage.READY -> {
            row("ready") {
                Column(verticalArrangement = Arrangement.spacedBy(KaeruTokens.Space2)) {
                    UpdateHeadline(UPDATES_READY)
                    UpdateNote(UPDATES_READY_NOTE)
                }
            }
            row("install") {
                PrimaryButton(UPDATES_INSTALL, onInstall, focusFirst.widthIn(max = ButtonWidth))
            }
        }
    }
}

/** One part of the page, named. Nothing focusable, so the D-pad steps over it on its way down. */
private fun LazyListScope.heading(title: String, first: Boolean = false) =
    item(key = "heading:$title", contentType = HEADING) {
        RowHeader(
            title,
            Modifier.padding(top = if (first) 0.dp else SectionGap),
            gutter = TvLayout.Gutter,
        )
    }

/** One row of the page: a stop for the remote, inset past the rail and held to the text column. */
private fun LazyListScope.row(key: String, content: @Composable () -> Unit) =
    item(key = key, contentType = ROW) {
        Box(
            Modifier
                .fillMaxWidth()
                .padding(start = TvLayout.Gutter, end = TvLayout.GutterEnd)
                .widthIn(max = TextColumn),
        ) { content() }
    }

/** Whether asking again could change the answer. During a transfer it could not. */
private val UpdateStage.canCheck: Boolean
    get() = this == UpdateStage.UNKNOWN || this == UpdateStage.UP_TO_DATE || this == UpdateStage.AVAILABLE

/** Whether this stage puts an amber button on the page for the remote to open on. */
private val UpdateStage.hasPrimaryAction: Boolean
    get() = this == UpdateStage.AVAILABLE || this == UpdateStage.READY

private val previewRelease = UpdateRelease(
    version = "0.4.0",
    publishedAt = Instant.parse("2026-09-16T08:00:00Z"),
    notes = "Что нового\n\n• Обновления внутри приложения\n• Исправлен плеер",
    apkUrl = "https://example.test/Kaeru-0.4.0.apk",
    apkName = "Kaeru-0.4.0.apk",
    sizeBytes = 31_457_280,
)

@Preview(device = Devices.TV_1080p)
@Composable
private fun TvUpdatesAvailablePreview() = KaeruTvTheme {
    TvUpdatesScreen(
        state = UpdateUiState(
            installedVersion = "0.3.0",
            stage = UpdateStage.AVAILABLE,
            checkedAt = Instant.parse("2026-09-16T08:00:00Z"),
            release = previewRelease,
        ),
        onCheck = {}, onDownload = {}, onInstall = {}, onAllowInstalls = {},
    )
}

@Preview(device = Devices.TV_1080p)
@Composable
private fun TvUpdatesUpToDatePreview() = KaeruTvTheme {
    TvUpdatesScreen(
        state = UpdateUiState(
            installedVersion = "0.3.0",
            stage = UpdateStage.UP_TO_DATE,
            checkedAt = Instant.parse("2026-09-16T08:00:00Z"),
        ),
        onCheck = {}, onDownload = {}, onInstall = {}, onAllowInstalls = {},
    )
}

@Preview(device = Devices.TV_1080p)
@Composable
private fun TvUpdatesDownloadingPreview() = KaeruTvTheme {
    TvUpdatesScreen(
        state = UpdateUiState(
            installedVersion = "0.3.0",
            stage = UpdateStage.DOWNLOADING,
            release = previewRelease,
            downloadedBytes = 12L * 1024 * 1024,
        ),
        onCheck = {}, onDownload = {}, onInstall = {}, onAllowInstalls = {},
    )
}
