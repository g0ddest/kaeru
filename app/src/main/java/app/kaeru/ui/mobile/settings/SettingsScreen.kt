package app.kaeru.ui.mobile.settings

import androidx.browser.customtabs.CustomTabsIntent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.net.toUri
import app.kaeru.BuildConfig
import app.kaeru.domain.model.Quality
import app.kaeru.ui.common.design.DestructiveButton
import app.kaeru.ui.common.design.IconAction
import app.kaeru.ui.common.design.KaeruTextField
import app.kaeru.ui.common.design.KaeruTokens
import app.kaeru.ui.common.design.KaeruTopBar
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
import app.kaeru.ui.common.theme.KaeruSecondary
import app.kaeru.ui.common.theme.KaeruSurface
import app.kaeru.ui.common.theme.KaeruText

private const val TITLE = "Настройки"
private const val BACK = "Назад"

private const val ACCOUNT = "Аккаунт"
private const val SIGN_OUT = "Выйти из аккаунта"
private const val CONFIRM_TITLE = "Выйти из аккаунта?"
private const val CONFIRM_TEXT = "Список и прогресс останутся на Shikimori, локальный кэш будет очищен"
private const val CONFIRM = "Выйти"
private const val CANCEL = "Отмена"
private const val RETRY = "Повторить"

private const val PLAYBACK = "Воспроизведение"
private const val AUTOPLAY = "Следующая серия автоматически"
private const val PIP_ON_LEAVE = "Сворачивать в окно при выходе из приложения"
private const val QUALITY = "Качество по умолчанию"
private const val THRESHOLD = "Порог просмотра"
private const val THRESHOLD_NOTE = "Серия считается просмотренной после этой доли"

private const val DUBS = "Озвучки"
private const val DUBS_NOTE = "Порядок работает, когда у аниме ещё нет запомненной озвучки"
private const val ADD_STUDIO = "Добавить студию"
private const val RESET = "Сбросить"

private const val DOWNLOADS = "Загрузки"
private const val DOWNLOADS_NOTE = "Скачанные серии и правила, по которым они скачиваются"
private const val OPEN_DOWNLOADS = "Открыть загрузки"

private const val KODIK = "Kodik"
private const val KODIK_NOTE = "Нужен, только если публичный токен перестанет работать"
private const val KODIK_PLACEHOLDER = "Токен"
private const val CLEAR = "Очистить"

private const val ABOUT = "О приложении"
private const val CHECK_UPDATES = "Проверить обновления"
private const val RELEASES_URL = "https://github.com/g0ddest/kaeru/releases"

/**
 * Settings, written as a page rather than laid out as a control panel.
 *
 * Every setting here changes something that happens later and out of sight — during playback, or
 * the next time a source is asked for a track. A switch beside a label only says what a setting is
 * called, so the ones whose effect is not obvious carry a sentence saying what they do, and the
 * controls sit inside that prose instead of in a table of identical rows. That is also why there
 * are no cards, no chevrons and no rules: the app has two card shapes, a poster and a hero, and a
 * preference is neither.
 *
 * Nothing here is saved with a button. A press writes through and the screen shows the new value
 * at once, because a local preference cannot be refused and a control that waited for a file to be
 * written would be inventing a doubt.
 */
@Composable
fun SettingsScreen(
    state: SettingsUiState,
    onBack: () -> Unit,
    onSignOut: () -> Unit,
    onAutoplay: (Boolean) -> Unit,
    onPipOnLeave: (Boolean) -> Unit,
    onQuality: (Quality?) -> Unit,
    onThreshold: (Float) -> Unit,
    onStudioUp: (Int) -> Unit,
    onStudioDown: (Int) -> Unit,
    onStudioRemove: (Int) -> Unit,
    onStudioAdd: (String) -> Unit,
    onStudiosReset: () -> Unit,
    onKodikToken: (String) -> Unit,
    onRetryAccount: () -> Unit,
    onDownloads: () -> Unit,
) {
    var confirming by rememberSaveable { mutableStateOf(false) }
    Column(Modifier.fillMaxSize()) {
        KaeruTopBar(
            title = TITLE,
            navigationIcon = { IconAction(Icons.AutoMirrored.Filled.ArrowBack, BACK, onBack) },
        )
        Column(
            Modifier
                .verticalScroll(rememberScrollState())
                .padding(top = KaeruTokens.Space2, bottom = KaeruTokens.Space8),
            verticalArrangement = Arrangement.spacedBy(KaeruTokens.Space8),
        ) {
            AccountSection(state, onRetryAccount, onSignOutPressed = { confirming = true })
            PlaybackSection(state, onAutoplay, onPipOnLeave, onQuality, onThreshold)
            DownloadsSection(onDownloads)
            DubsSection(state, onStudioUp, onStudioDown, onStudioRemove, onStudioAdd, onStudiosReset)
            KodikSection(state.kodikToken, onKodikToken)
            AboutSection()
        }
    }
    if (confirming) {
        SignOutDialog(
            onDismiss = { confirming = false },
            onConfirm = {
                confirming = false
                onSignOut()
            },
        )
    }
}

/**
 * The way to the one settings screen that is not on this page.
 *
 * Downloads have a screen of their own because they are not only settings: what is on the device
 * has to be listed and deleted, and a list that grows with every episode does not belong inside a
 * page of switches. So this is a signpost, and the rules live beside the thing they govern.
 */
@Composable
private fun DownloadsSection(onDownloads: () -> Unit) {
    SettingsSection(DOWNLOADS) {
        SettingNote(DOWNLOADS_NOTE)
        SecondaryButton(OPEN_DOWNLOADS, onClick = onDownloads)
    }
}

@Composable
private fun AccountSection(state: SettingsUiState, onRetry: () -> Unit, onSignOutPressed: () -> Unit) {
    SettingsSection(ACCOUNT) {
        if (state.accountLoading) AccountSkeleton() else AccountBlock(state.account)
        // Offered only when Shikimori could not be reached and nothing was cached to fall back
        // on. With a name on screen there is nothing to retry — it is already the right answer.
        if (!state.accountLoading && state.account == null) {
            TextAction(RETRY, onRetry, Modifier.offset(x = -KaeruTokens.Space3))
        }
        // Signing out never waits on a nickname: knowing who you are is not a condition of leaving.
        DestructiveButton(SIGN_OUT, onClick = onSignOutPressed)
    }
}

@Composable
private fun PlaybackSection(
    state: SettingsUiState,
    onAutoplay: (Boolean) -> Unit,
    onPipOnLeave: (Boolean) -> Unit,
    onQuality: (Quality?) -> Unit,
    onThreshold: (Float) -> Unit,
) {
    SettingsSection(PLAYBACK) {
        SettingSwitchRow(AUTOPLAY, state.autoplayNext, onAutoplay)
        // Phone only: the television has no floating window, and its own screen does not offer this.
        SettingSwitchRow(PIP_ON_LEAVE, state.pipOnLeave, onPipOnLeave)
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
}

@Composable
private fun DubsSection(
    state: SettingsUiState,
    onStudioUp: (Int) -> Unit,
    onStudioDown: (Int) -> Unit,
    onStudioRemove: (Int) -> Unit,
    onStudioAdd: (String) -> Unit,
    onStudiosReset: () -> Unit,
) {
    var typed by rememberSaveable { mutableStateOf("") }
    // A name already on the list cannot be added again, and the control says so by going quiet
    // rather than by swallowing the press: the field keeps what was typed, and the row it
    // duplicates is on screen right above it.
    val canAdd = typed.isNotBlank() && state.studios.none { it.equals(typed.trim(), ignoreCase = true) }
    val add = {
        if (canAdd) {
            onStudioAdd(typed)
            typed = ""
        }
    }
    SettingsSection(DUBS) {
        SettingNote(DUBS_NOTE)
        // No spacing between rows: each is already a 48dp target, and nine of them with gaps
        // turns a short list into a scroll of its own.
        Column {
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
        }
        KaeruTextField(
            value = typed,
            onValueChange = { typed = it },
            placeholder = ADD_STUDIO,
            onSubmit = add,
            trailing = { IconAction(Icons.Default.Add, ADD_STUDIO, add, enabled = canAdd) },
        )
        // Offered only when there is a list of the viewer's own to undo. Nothing to reset is not
        // a disabled button, it is no button.
        if (state.studiosChosen) {
            // Nudged back by the text button's own padding so its first letter sits on the
            // gutter, in line with the studio names above it.
            TextAction(RESET, onStudiosReset, Modifier.offset(x = -KaeruTokens.Space3))
        }
    }
}

/**
 * The field holds no state of its own: every keystroke is the setting.
 *
 * Saving on submit or on losing focus would have left one reachable way to lose an edit — with the
 * keyboard up, the first back press is eaten by the keyboard and the second pops the screen, and a
 * screen going away is not a focus change. The view model refuses a write that changes nothing and
 * shows the new value optimistically, so typing into the setting directly costs a keystroke and
 * nothing else.
 */
@Composable
private fun KodikSection(token: String, onToken: (String) -> Unit) {
    SettingsSection(KODIK) {
        SettingNote(KODIK_NOTE)
        KaeruTextField(
            value = token,
            onValueChange = onToken,
            placeholder = KODIK_PLACEHOLDER,
            trailing = if (token.isEmpty()) {
                null
            } else {
                { IconAction(Icons.Default.Close, CLEAR, { onToken("") }) }
            },
        )
    }
}

@Composable
private fun AboutSection() {
    val context = LocalContext.current
    SettingsSection(ABOUT) {
        SettingNote("Kaeru ${BuildConfig.VERSION_NAME}")
        SecondaryButton(
            CHECK_UPDATES,
            // A device with no browser and no Custom Tabs provider has nowhere to send this. There
            // is nothing useful to say about that, so the press does nothing rather than crashing.
            onClick = {
                runCatching { CustomTabsIntent.Builder().build().launchUrl(context, RELEASES_URL.toUri()) }
            },
        )
    }
}

/**
 * Signing out asks first.
 *
 * It is the one action in the app that pressing the same button again does not undo, and the
 * dialog says what is lost (this device's copy) and what is not (the list itself, which lives on
 * Shikimori). The confirm is red rather than amber: amber is «go» everywhere else, and this is
 * not that.
 */
@Composable
private fun SignOutDialog(onDismiss: () -> Unit, onConfirm: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = { DestructiveButton(CONFIRM, onClick = onConfirm) },
        dismissButton = { SecondaryButton(CANCEL, onClick = onDismiss) },
        title = { Text(CONFIRM_TITLE, style = MaterialTheme.typography.headlineMedium) },
        text = { Text(CONFIRM_TEXT, style = MaterialTheme.typography.bodyMedium) },
        shape = KaeruTokens.CardShape,
        containerColor = KaeruSurface,
        // The app has no shadows and no tinted surfaces: depth is the three-step background ramp
        // and, here, the scrim the dialog already draws over the page.
        tonalElevation = 0.dp,
        titleContentColor = KaeruText,
        textContentColor = KaeruSecondary,
    )
}
