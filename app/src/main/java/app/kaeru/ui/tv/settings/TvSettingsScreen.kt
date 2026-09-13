package app.kaeru.ui.tv.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
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
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.tooling.preview.Devices
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import app.kaeru.BuildConfig
import app.kaeru.domain.model.Account
import app.kaeru.domain.model.Quality
import app.kaeru.ui.common.design.DestructiveButton
import app.kaeru.ui.common.design.KaeruTokens
import app.kaeru.ui.common.design.RowHeader
import app.kaeru.ui.common.design.SecondaryButton
import app.kaeru.ui.common.design.TextAction
import app.kaeru.ui.common.settings.AccountBlock
import app.kaeru.ui.common.settings.AccountSkeleton
import app.kaeru.ui.common.settings.SettingChoiceRow
import app.kaeru.ui.common.settings.SettingLabel
import app.kaeru.ui.common.settings.SettingNote
import app.kaeru.ui.common.settings.SettingSwitchRow
import app.kaeru.ui.common.settings.SettingsUiState
import app.kaeru.ui.common.settings.StudioRow
import app.kaeru.ui.common.settings.qualityOptions
import app.kaeru.ui.common.settings.studioKeys
import app.kaeru.ui.common.settings.thresholdChosen
import app.kaeru.ui.common.settings.thresholdOptions
import app.kaeru.ui.common.theme.KaeruTvTheme
import app.kaeru.ui.tv.TvDialog
import app.kaeru.ui.tv.TvLayout
import app.kaeru.ui.tv.requestFocusOrLog

private const val ACCOUNT = "Аккаунт"
private const val SIGN_OUT = "Выйти из аккаунта"
private const val CONFIRM_TITLE = "Выйти из аккаунта?"
private const val CONFIRM_TEXT = "Список и прогресс останутся на Shikimori, локальный кэш будет очищен"
private const val CONFIRM = "Выйти"
private const val CANCEL = "Отмена"
private const val RETRY = "Повторить"

private const val PLAYBACK = "Воспроизведение"
private const val AUTOPLAY = "Следующая серия автоматически"
private const val QUALITY = "Качество по умолчанию"
private const val THRESHOLD = "Порог просмотра"
private const val THRESHOLD_NOTE = "Серия считается просмотренной после этой доли"

private const val DUBS = "Озвучки"
private const val DUBS_NOTE = "Порядок работает, когда у аниме ещё нет запомненной озвучки"
private const val RESET = "Сбросить"

private const val ABOUT = "О приложении"

/** What each slot holds, so the list reuses a studio's node for a studio and not for a heading. */
private const val HEADING = "heading"
private const val ROW = "row"

/** One column of prose, kept near the width a sentence stays readable at across a room. */
private val TextColumn = 640.dp

/**
 * The air above a section name. [KaeruTokens.Space8] in total, of which the list's own spacing
 * already supplies [KaeruTokens.Space3].
 */
private val SectionGap = KaeruTokens.Space8 - KaeruTokens.Space3

/**
 * Settings on a television, written as the same page the phone shows and reachable with a remote.
 *
 * **Why this is a lazy list and not a column that scrolls.** Four sections of controls come to
 * something near two and a half screens on a 540dp-tall panel, so the page has to move under the
 * D-pad — and on the real television it did not: the account block and the first settings were
 * reachable, everything below them was not. A `LazyColumn` whose items are the individual rows is
 * the arrangement that cannot have that fault. Focus search composes the next item whether or not
 * it is on screen, every focusable row is an item of its own, and the row that takes the focus is
 * the exact thing brought into view — rather than a whole section that is taller than the panel.
 *
 * The nine dub rows are items of this same list rather than a scrolling box inside it. A second
 * vertical scroll area inside the first is a place a remote can get stuck, and it buys nothing: a
 * row that is an item is already one press away and already scrolls itself into view.
 *
 * Three things are deliberately not here. There is no top bar with a back arrow — the drawer on the
 * left is the way out, and a second one would be a control that does the same thing twice. There is
 * no field for adding a studio: typing a studio name with a D-pad costs a minute, the order can be
 * rearranged and reset without one, and the phone is where a list like that gets built. And there
 * is no Kodik token field, for the same reason.
 *
 * Everything else is the phone's, from the same view model and the same components, so a preference
 * changed on the sofa reads the same way in the hand.
 */
@Composable
fun TvSettingsScreen(
    state: SettingsUiState,
    onSignOut: () -> Unit,
    onAutoplay: (Boolean) -> Unit,
    onQuality: (Quality?) -> Unit,
    onThreshold: (Float) -> Unit,
    onStudioUp: (Int) -> Unit,
    onStudioDown: (Int) -> Unit,
    onStudioRemove: (Int) -> Unit,
    onStudiosReset: () -> Unit,
    onRetryAccount: () -> Unit,
    modifier: Modifier = Modifier,
    listState: LazyListState = rememberLazyListState(),
) {
    var confirming by rememberSaveable { mutableStateOf(false) }
    val first = remember { FocusRequester() }
    // Not the sign-out button, which is the first focusable on the page: the first press after a
    // screen opens is the easiest one to make by accident, and it should not be the red one.
    LaunchedEffect(Unit) { first.requestFocusOrLog("the autoplay switch of the television settings") }

    LazyColumn(
        modifier.fillMaxSize(),
        state = listState,
        contentPadding = PaddingValues(
            top = TvLayout.SafeVertical,
            bottom = TvLayout.SafeVertical,
        ),
        verticalArrangement = Arrangement.spacedBy(KaeruTokens.Space3),
    ) {
        heading(ACCOUNT, first = true)
        row("account") { if (state.accountLoading) AccountSkeleton() else AccountBlock(state.account) }
        if (!state.accountLoading && state.account == null) {
            row("account-retry") { TextAction(RETRY, onRetryAccount) }
        }
        row("sign-out") { DestructiveButton(SIGN_OUT, onClick = { confirming = true }) }

        heading(PLAYBACK)
        row("autoplay") {
            SettingSwitchRow(AUTOPLAY, state.autoplayNext, onAutoplay, Modifier.focusRequester(first))
        }
        // A label and the chips it names are one item: they are read together, and splitting them
        // would let the list stop with the question off the top of the panel and the answers on it.
        row("quality") {
            Labelled(QUALITY) {
                SettingChoiceRow(
                    options = qualityOptions(state.defaultQuality),
                    label = { it.label },
                    selected = { it.quality == state.defaultQuality },
                    onSelect = { onQuality(it.quality) },
                )
            }
        }
        row("threshold") {
            Labelled(THRESHOLD, THRESHOLD_NOTE) {
                SettingChoiceRow(
                    options = thresholdOptions(state.watchedThreshold),
                    label = { it.label },
                    selected = { thresholdChosen(it.fraction, state.watchedThreshold) },
                    onSelect = { onThreshold(it.fraction) },
                )
            }
        }

        heading(DUBS)
        row("dubs-note") { SettingNote(DUBS_NOTE) }
        val keys = studioKeys(state.studios)
        state.studios.forEachIndexed { index, studio ->
            // Keyed by the studio, so a row that moves up takes the remote with it.
            row(keys[index]) {
                StudioRow(
                    name = studio,
                    canMoveUp = index > 0,
                    canMoveDown = index < state.studios.lastIndex,
                    canRemove = state.canRemoveStudio,
                    onMoveUp = { onStudioUp(index) },
                    onMoveDown = { onStudioDown(index) },
                    onRemove = { onStudioRemove(index) },
                )
            }
        }
        if (state.studiosChosen) {
            row("dubs-reset") { SecondaryButton(RESET, onStudiosReset, Modifier.fillMaxWidth(0.4f)) }
        }

        heading(ABOUT)
        row("version") { SettingNote("Kaeru ${BuildConfig.VERSION_NAME}") }
    }

    if (confirming) {
        TvSignOutDialog(
            onDismiss = { confirming = false },
            onConfirm = { confirming = false; onSignOut() },
        )
    }
}

/** One part of the screen, named. Nothing focusable, so the D-pad steps over it on its way down. */
private fun LazyListScope.heading(title: String, first: Boolean = false) =
    item(key = "heading:$title", contentType = HEADING) {
        RowHeader(
            title,
            Modifier.padding(top = if (first) 0.dp else SectionGap),
            gutter = TvLayout.Gutter,
        )
    }

/**
 * One row of the page: a stop for the remote, inset past the rail and held to the text column.
 *
 * [key] is what keeps a row's node — and the focus on it — attached to the row rather than to the
 * position, which matters on the one list here whose rows change places.
 */
private fun LazyListScope.row(key: String, content: @Composable () -> Unit) =
    item(key = key, contentType = ROW) {
        Box(
            Modifier
                .padding(start = TvLayout.Gutter, end = TvLayout.GutterEnd)
                .widthIn(max = TextColumn),
        ) { content() }
    }

/** A control that needs saying what it does before it can be answered. */
@Composable
private fun Labelled(label: String, note: String? = null, content: @Composable () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(KaeruTokens.Space2)) {
        SettingLabel(label)
        note?.let { SettingNote(it) }
        content()
    }
}

/**
 * The one irreversible control in the app, asked about before it happens.
 *
 * «Отмена» takes the focus, not «Выйти»: on a remote the first press after a panel opens is the
 * easiest one to make by accident, and it should land on the answer that changes nothing.
 */
@Composable
private fun TvSignOutDialog(onDismiss: () -> Unit, onConfirm: () -> Unit) {
    val cancel = remember { FocusRequester() }
    LaunchedEffect(Unit) { cancel.requestFocusOrLog("the sign-out confirmation") }
    TvDialog(title = CONFIRM_TITLE, onDismiss = onDismiss, text = CONFIRM_TEXT) {
        Row(horizontalArrangement = Arrangement.spacedBy(KaeruTokens.Space3)) {
            SecondaryButton(CANCEL, onDismiss, Modifier.focusRequester(cancel))
            DestructiveButton(CONFIRM, onConfirm)
        }
    }
}

private val previewState = SettingsUiState(
    accountLoading = false,
    account = Account(id = 1, nickname = "vitaliy", avatarUrl = null),
    autoplayNext = true,
    defaultQuality = Quality.P720,
    watchedThreshold = 0.9f,
    studiosChosen = true,
)

@Composable
private fun TvSettingsPreviewAt(index: Int) = KaeruTvTheme {
    TvSettingsScreen(
        state = previewState,
        onSignOut = {},
        onAutoplay = {},
        onQuality = {},
        onThreshold = {},
        onStudioUp = {},
        onStudioDown = {},
        onStudioRemove = {},
        onStudiosReset = {},
        onRetryAccount = {},
        listState = rememberLazyListState(initialFirstVisibleItemIndex = index),
    )
}

/** The top of the page: the account block, sign-out, and the first of the playback controls. */
@Preview(device = Devices.TV_1080p)
@Composable
private fun TvSettingsPreview() = TvSettingsPreviewAt(0)

/** The middle, where a Column that would not scroll used to end: quality, threshold, the dubs. */
@Preview(device = Devices.TV_1080p)
@Composable
private fun TvSettingsPlaybackPreview() = TvSettingsPreviewAt(5)

/** The end of the nine dub rows, the reset, and the version — the part the remote could not reach. */
@Preview(device = Devices.TV_1080p)
@Composable
private fun TvSettingsDubsPreview() = TvSettingsPreviewAt(15)

@Preview(device = Devices.TV_1080p)
@Composable
private fun TvSignOutDialogPreview() = KaeruTvTheme {
    TvSignOutDialog(onDismiss = {}, onConfirm = {})
}
