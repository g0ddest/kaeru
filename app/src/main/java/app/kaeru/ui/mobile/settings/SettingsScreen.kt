package app.kaeru.ui.mobile.settings

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import app.kaeru.ui.common.design.DestructiveButton
import app.kaeru.ui.common.design.IconAction
import app.kaeru.ui.common.design.KaeruTokens
import app.kaeru.ui.common.design.KaeruTopBar
import app.kaeru.ui.common.design.SecondaryButton
import app.kaeru.ui.common.theme.KaeruSecondary
import app.kaeru.ui.common.theme.KaeruSurface
import app.kaeru.ui.common.theme.KaeruText
import app.kaeru.ui.common.theme.KaeruTheme

private const val TITLE = "Настройки"
private const val BACK = "Назад"
private const val SIGN_OUT = "Выйти из аккаунта"
private const val CONFIRM_TITLE = "Выйти из аккаунта?"
private const val CONFIRM_TEXT =
    "Kaeru забудет вход в Shikimori. Список и прогресс останутся на сервере — они вернутся при следующем входе."
private const val CONFIRM = "Выйти"
private const val CANCEL = "Отмена"

/**
 * Settings, with the one setting that already has somewhere to go: signing out.
 *
 * The screen exists now because the home screen's top bar needs a destination for its gear; task 6
 * of this plan fills it with the account card, dub priority, autoplay, quality, the watched
 * threshold, the Kodik token and the update check. Nothing else belongs here until then.
 *
 * Signing out asks first. It is the one action in the app that cannot be undone by pressing the
 * same button again, and the dialog says what is lost (the session) and what is not (the list). The
 * confirm is red rather than amber: amber is «go» everywhere else, and this is not that.
 */
@Composable
fun SettingsScreen(onBack: () -> Unit, onLogout: () -> Unit) {
    var confirming by rememberSaveable { mutableStateOf(false) }
    Column(Modifier.fillMaxSize()) {
        KaeruTopBar(
            title = TITLE,
            navigationIcon = { IconAction(Icons.AutoMirrored.Filled.ArrowBack, BACK, onBack) },
        )
        SecondaryButton(
            SIGN_OUT,
            onClick = { confirming = true },
            modifier = Modifier.padding(
                horizontal = KaeruTokens.GutterPhone,
                vertical = KaeruTokens.Space6,
            ),
        )
    }
    if (confirming) {
        AlertDialog(
            onDismissRequest = { confirming = false },
            confirmButton = {
                DestructiveButton(CONFIRM, onClick = { confirming = false; onLogout() })
            },
            dismissButton = { SecondaryButton(CANCEL, onClick = { confirming = false }) },
            title = { Text(CONFIRM_TITLE, style = MaterialTheme.typography.headlineMedium) },
            text = { Text(CONFIRM_TEXT, style = MaterialTheme.typography.bodyMedium) },
            shape = KaeruTokens.CardShape,
            containerColor = KaeruSurface,
            titleContentColor = KaeruText,
            textContentColor = KaeruSecondary,
        )
    }
}

@Preview(showBackground = true, backgroundColor = 0xFF0B0C10)
@Composable
private fun SettingsPreview() = KaeruTheme { SettingsScreen(onBack = {}, onLogout = {}) }
