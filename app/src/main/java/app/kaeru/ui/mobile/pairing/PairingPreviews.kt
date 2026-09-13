package app.kaeru.ui.mobile.pairing

import androidx.compose.runtime.Composable
import androidx.compose.ui.tooling.preview.Preview
import app.kaeru.domain.pairing.PairingRequest
import app.kaeru.ui.common.pairing.PairingStage
import app.kaeru.ui.common.pairing.PairingUiState
import app.kaeru.ui.common.theme.KaeruTheme

private const val DARK = 0xFF0B0C10

private val Television = PairingRequest("192.168.1.7", 41_234, "nonce", "Гостиная")

@Composable
private fun Preview(state: PairingUiState) = KaeruTheme {
    PairingScreen(state = state, onConfirm = { null }, onDismiss = {})
}

/** The question, which is the screen ninety-nine times out of a hundred. */
@Preview(showBackground = true, backgroundColor = DARK, widthDp = 360, heightDp = 640)
@Composable
private fun PairingConfirmPreview() = Preview(PairingUiState(Television, PairingStage.CONFIRM))

/** A television whose name nobody ever set, which is most of them out of the box. */
@Preview(showBackground = true, backgroundColor = DARK, widthDp = 360, heightDp = 640)
@Composable
private fun PairingUnnamedPreview() =
    Preview(PairingUiState(Television.copy(name = ""), PairingStage.CONFIRM))

/** The browser is in front of this; what shows here is what a person comes back to. */
@Preview(showBackground = true, backgroundColor = DARK, widthDp = 360, heightDp = 640)
@Composable
private fun PairingWaitingPreview() = Preview(PairingUiState(Television, PairingStage.AWAITING_CODE))

@Preview(showBackground = true, backgroundColor = DARK, widthDp = 360, heightDp = 640)
@Composable
private fun PairingSendingPreview() = Preview(PairingUiState(Television, PairingStage.SENDING))

@Preview(showBackground = true, backgroundColor = DARK, widthDp = 360, heightDp = 640)
@Composable
private fun PairingDonePreview() = Preview(PairingUiState(Television, PairingStage.DONE))

/** The television is on another network, or was switched off between the scan and the tap. */
@Preview(showBackground = true, backgroundColor = DARK, widthDp = 360, heightDp = 640)
@Composable
private fun PairingErrorPreview() = Preview(
    PairingUiState(
        Television,
        PairingStage.CONFIRM,
        "Телевизор не отвечает. Проверьте, что телефон в той же сети Wi-Fi",
    ),
)

/** Something fired `kaeru://pair` that was not a television on this network. */
@Preview(showBackground = true, backgroundColor = DARK, widthDp = 360, heightDp = 640)
@Composable
private fun PairingBadLinkPreview() = Preview(
    PairingUiState(null, PairingStage.FAILED, "Эта ссылка не для входа на телевизоре"),
)
