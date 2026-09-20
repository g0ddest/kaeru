package app.kaeru.ui.tv.auth

import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.assertIsNotFocused
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import app.kaeru.ui.common.auth.AuthUiState
import app.kaeru.ui.common.theme.KaeruTvTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.time.Instant

/**
 * Where the D-pad is when the login screen appears.
 *
 * On the real television the answer used to be the typed-code field, because nothing else on the
 * screen was focusable while a code was good — and arriving in a text field opens the system
 * keyboard over the code the screen exists to show.
 */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w960dp-h540dp-television-notnight-mdpi")
class TvLoginFocusTest {
    @get:Rule val compose = createComposeRule()

    private val authorizeUrl = "https://shikimori.io/oauth/authorize?client_id=kaeru"
    private val pairingUri = "kaeru://pair?host=192.168.1.7&port=41234&nonce=abc&name=Living"

    private fun show(status: TvPairingStatus, uri: String? = pairingUri) = compose.setContent {
        KaeruTvTheme {
            TvLoginScreen(
                authorizeUrl = authorizeUrl,
                state = AuthUiState(loggedIn = false),
                pairing = TvPairingUiState(
                    deviceName = "Гостиная",
                    pairingUri = uri,
                    expiresAt = uri?.let { Instant.now().plusSeconds(272) },
                    status = status,
                ),
                code = "",
                onCode = {},
                onSubmit = {},
                onNewQr = {},
            )
        }
    }

    @Test
    fun `the code, not the field, has the D-pad when the screen opens`() {
        show(TvPairingStatus.WAITING)

        compose.onNodeWithContentDescription("QR-код для входа с телефона").assertIsFocused()
        compose.onNodeWithText("Код авторизации").assertIsNotFocused()
    }

    @Test
    fun `the same is true once the offer has expired and there is a button below it`() {
        show(TvPairingStatus.EXPIRED, uri = null)

        compose.onNodeWithContentDescription("QR-код для входа с телефона").assertIsFocused()
        compose.onNodeWithText("Новый QR").assertIsNotFocused()
    }
}
