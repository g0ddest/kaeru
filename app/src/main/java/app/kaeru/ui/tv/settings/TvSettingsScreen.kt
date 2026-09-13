package app.kaeru.ui.tv.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
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
import app.kaeru.ui.common.design.SecondaryButton
import app.kaeru.ui.common.design.TextAction
import app.kaeru.ui.common.settings.AccountBlock
import app.kaeru.ui.common.settings.AccountSkeleton
import app.kaeru.ui.common.settings.SettingChoiceRow
import app.kaeru.ui.common.settings.SettingLabel
import app.kaeru.ui.common.settings.SettingNote
import app.kaeru.ui.common.settings.SettingSwitchRow
import app.kaeru.ui.common.settings.SettingsSection
import app.kaeru.ui.common.settings.SettingsUiState
import app.kaeru.ui.common.settings.StudioRow
import app.kaeru.ui.common.settings.qualityOptions
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

/** One column of prose, kept near the width a sentence stays readable at across a room. */
private val TextColumn = 640.dp

/**
 * Settings on a television, written as the same page the phone shows and reachable with a remote.
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
) {
    var confirming by rememberSaveable { mutableStateOf(false) }
    val first = remember { FocusRequester() }
    // Not the sign-out button, which is the first focusable on the page: the first press after a
    // screen opens is the easiest one to make by accident, and it should not be the red one.
    LaunchedEffect(Unit) { first.requestFocusOrLog("the autoplay switch of the television settings") }

    Column(
        modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(top = TvLayout.SafeVertical, bottom = TvLayout.SafeVertical),
        verticalArrangement = Arrangement.spacedBy(KaeruTokens.Space8),
    ) {
        SettingsSection(ACCOUNT, Modifier.widthIn(max = TextColumn + TvLayout.Gutter), TvLayout.Gutter) {
            if (state.accountLoading) AccountSkeleton() else AccountBlock(state.account)
            if (!state.accountLoading && state.account == null) {
                TextAction(RETRY, onRetryAccount)
            }
            DestructiveButton(SIGN_OUT, onClick = { confirming = true })
        }
        SettingsSection(PLAYBACK, Modifier.widthIn(max = TextColumn + TvLayout.Gutter), TvLayout.Gutter) {
            SettingSwitchRow(AUTOPLAY, state.autoplayNext, onAutoplay, Modifier.focusRequester(first))
            SettingLabel(QUALITY)
            SettingChoiceRow(
                options = qualityOptions(state.defaultQuality),
                label = { it.label },
                selected = { it.quality == state.defaultQuality },
                onSelect = { onQuality(it.quality) },
            )
            SettingLabel(THRESHOLD)
            SettingNote(THRESHOLD_NOTE)
            SettingChoiceRow(
                options = thresholdOptions(state.watchedThreshold),
                label = { it.label },
                selected = { thresholdChosen(it.fraction, state.watchedThreshold) },
                onSelect = { onThreshold(it.fraction) },
            )
        }
        SettingsSection(DUBS, Modifier.widthIn(max = TextColumn + TvLayout.Gutter), TvLayout.Gutter) {
            SettingNote(DUBS_NOTE)
            state.studios.forEachIndexed { index, studio ->
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
            if (state.studiosChosen) {
                SecondaryButton(RESET, onStudiosReset, Modifier.fillMaxWidth(0.4f))
            }
        }
        SettingsSection(ABOUT, Modifier.widthIn(max = TextColumn + TvLayout.Gutter), TvLayout.Gutter) {
            SettingNote("Kaeru ${BuildConfig.VERSION_NAME}")
        }
    }

    if (confirming) {
        TvSignOutDialog(
            onDismiss = { confirming = false },
            onConfirm = { confirming = false; onSignOut() },
        )
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

@Preview(device = Devices.TV_1080p)
@Composable
private fun TvSettingsPreview() = KaeruTvTheme {
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
    )
}

@Preview(device = Devices.TV_1080p)
@Composable
private fun TvSignOutDialogPreview() = KaeruTvTheme {
    TvSignOutDialog(onDismiss = {}, onConfirm = {})
}
