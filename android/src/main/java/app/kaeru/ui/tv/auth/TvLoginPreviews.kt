package app.kaeru.ui.tv.auth

import androidx.compose.runtime.Composable
import androidx.compose.ui.tooling.preview.Devices
import androidx.compose.ui.tooling.preview.Preview
import app.kaeru.ui.common.auth.AuthFailure
import app.kaeru.ui.common.auth.AuthUiState
import app.kaeru.ui.common.theme.KaeruTvTheme
import java.time.Instant

private const val DARK = 0xFF0B0C10

private const val AUTHORIZE_URL =
    "https://shikimori.io/oauth/authorize?client_id=kaeru&redirect_uri=urn:ietf:wg:oauth:2.0:oob&response_type=code&scope=user_rates"

private val PAIRING_URI =
    "kaeru://pair?host=192.168.1.7&port=41234&nonce=9nKXr-_A0hVqTt2wY6mLpZbC4sD8eF1gH3jK5nO7qRs&name=%D0%93%D0%BE%D1%81%D1%82%D0%B8%D0%BD%D0%B0%D1%8F"

/**
 * Frozen four and a half minutes in, which is the state the screen spends nearly all its life in
 * and the one worth looking at across a room.
 */
private fun offer(
    status: TvPairingStatus = TvPairingStatus.WAITING,
    uri: String? = PAIRING_URI,
    error: String? = null,
) = TvPairingUiState(
    deviceName = "Гостиная",
    pairingUri = uri,
    expiresAt = if (uri == null) null else Instant.now().plusSeconds(272),
    status = status,
    errorMessage = error,
)

@Composable
private fun Preview(pairing: TvPairingUiState, state: AuthUiState = AuthUiState(loggedIn = false), code: String = "") =
    KaeruTvTheme {
        TvLoginScreen(
            authorizeUrl = AUTHORIZE_URL,
            state = state,
            pairing = pairing,
            code = code,
            onCode = {},
            onSubmit = {},
            onNewQr = {},
        )
    }

/** The screen as a television shows it while it waits for somebody to pick their phone up. */
@Preview(showBackground = true, backgroundColor = DARK, device = Devices.TV_1080p)
@Composable
private fun TvLoginWaitingPreview() = Preview(offer())

/** The second between a phone sending the code and the home screen replacing all of this. */
@Preview(showBackground = true, backgroundColor = DARK, device = Devices.TV_1080p)
@Composable
private fun TvLoginConfirmingPreview() = Preview(offer(status = TvPairingStatus.CONFIRMING))

/** Five minutes with nobody in the room, and the offer to start again. */
@Preview(showBackground = true, backgroundColor = DARK, device = Devices.TV_1080p)
@Composable
private fun TvLoginExpiredPreview() = Preview(offer(status = TvPairingStatus.EXPIRED, uri = null))

/** Ethernet unplugged: the big code falls back to the page the typed path uses. */
@Preview(showBackground = true, backgroundColor = DARK, device = Devices.TV_1080p)
@Composable
private fun TvLoginUnavailablePreview() = Preview(
    offer(
        status = TvPairingStatus.UNAVAILABLE,
        uri = null,
        error = "Телевизор не в локальной сети. Войдите по коду",
    ),
)

/** The old way through, mid-typing. */
@Preview(showBackground = true, backgroundColor = DARK, device = Devices.TV_1080p)
@Composable
private fun TvLoginTypedCodePreview() = Preview(pairing = offer(), code = "aBc1dEf2gHi3")

/**
 * A code Shikimori would not take. The field is empty because the code that was in it is spent,
 * and the line says where to get another one.
 */
@Preview(showBackground = true, backgroundColor = DARK, device = Devices.TV_1080p)
@Composable
private fun TvLoginCodeRefusedPreview() = Preview(
    pairing = offer(),
    state = AuthUiState(loggedIn = false, failure = AuthFailure.CODE_REJECTED),
)

/** Shikimori unreachable: the same red line, a different thing to do about it. */
@Preview(showBackground = true, backgroundColor = DARK, device = Devices.TV_1080p)
@Composable
private fun TvLoginCodeOfflinePreview() = Preview(
    pairing = offer(),
    state = AuthUiState(loggedIn = false, failure = AuthFailure.NO_CONNECTION),
)

/** The second the code spends in flight, with nothing on this half accepting a press. */
@Preview(showBackground = true, backgroundColor = DARK, device = Devices.TV_1080p)
@Composable
private fun TvLoginCheckingPreview() = Preview(
    pairing = offer(),
    state = AuthUiState(loggedIn = false, exchanging = true),
    code = "aBc1dEf2gHi3",
)
