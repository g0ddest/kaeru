package app.kaeru.ui.common.pairing

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.kaeru.domain.error.AuthCallbackRejected
import app.kaeru.domain.pairing.PairingClient
import app.kaeru.domain.pairing.PairingRequest
import app.kaeru.domain.repository.AuthRepository
import app.kaeru.domain.repository.MOBILE_REDIRECT
import app.kaeru.ui.common.errorMessageOrNull
import app.kaeru.ui.common.toUserMessage
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.security.MessageDigest
import javax.inject.Inject

/** Where a hand-off has got to. Nothing here is reached without somebody pressing something. */
enum class PairingStage {
    /** No television has asked for anything. */
    IDLE,

    /** A television asked, and the person has not answered yet. */
    CONFIRM,

    /** The browser is open and a code is expected back. */
    AWAITING_CODE,

    /** The code is on its way across the local network. */
    SENDING,

    /** The television is signed in. */
    DONE,

    /** The link itself was no good, so there is nothing to confirm. */
    FAILED,
}

data class PairingUiState(
    val request: PairingRequest? = null,
    val stage: PairingStage = PairingStage.IDLE,
    val errorMessage: String? = null,
)

/**
 * The phone's side of signing a television in: read the link, ask, fetch one code, hand it over.
 *
 * The phone never signs itself in here and never keeps the code. It is a courier — which is the
 * point of the whole arrangement, because the alternative is typing an authorization code into a
 * television with a remote control.
 *
 * The `state` of the authorization is held and checked here rather than in the repository,
 * because this code is deliberately never exchanged on this device. Without the check, any
 * application able to fire `kaeru://oauth` could hand this phone a code of its own while a pairing
 * is open and have the television sign into somebody else's account. And because the repository is
 * never asked to remember it — [AuthRepository.pairingAuthorization] arms nothing — a hand-off
 * leaves no live `state` behind for something to replay at the phone's own sign-in later.
 */
@HiltViewModel
class PairingViewModel @Inject constructor(
    private val auth: AuthRepository,
    private val pairing: PairingClient,
) : ViewModel() {

    private val state = MutableStateFlow(PairingUiState())
    val uiState: StateFlow<PairingUiState> = state.asStateFlow()

    /** The `state` of the authorization this screen started, held only while it is in flight. */
    private var expectedState: String? = null

    /** Reads one `kaeru://pair` deep link. Anything can fire it, so the link is checked, not trusted. */
    fun open(uri: String) {
        expectedState = null
        PairingRequest.parse(uri).fold(
            onSuccess = { request -> state.value = PairingUiState(request, PairingStage.CONFIRM) },
            onFailure = { error ->
                state.value = PairingUiState(null, PairingStage.FAILED, error.toUserMessage())
            },
        )
    }

    /**
     * Answers «Войти»: arms a fresh authorization and returns the page to open in the browser.
     *
     * Null when no television is waiting — the screen has nothing to open, and nothing should be
     * armed for a callback that would then have somewhere to go.
     */
    fun confirm(): String? {
        val request = state.value.request ?: return null
        val authorization = auth.pairingAuthorization()
        expectedState = authorization.state
        state.value = PairingUiState(request, PairingStage.AWAITING_CODE)
        return authorization.url
    }

    /**
     * Offers this view model the `kaeru://oauth` callback. True when it was taken, which is the
     * caller's signal to leave the phone's own sign-in alone.
     */
    fun onMobileCallback(code: String?, callbackState: String?, consumed: () -> Unit): Boolean {
        val request = state.value.request ?: return false
        if (state.value.stage != PairingStage.AWAITING_CODE) return false
        consumed()
        val expected = expectedState
        expectedState = null // Single use: a replayed callback finds nothing waiting.
        if (expected == null || callbackState == null || !matches(expected, callbackState) || code.isNullOrBlank()) {
            return true.also { rejected() }
        }
        state.value = PairingUiState(request, PairingStage.SENDING)
        viewModelScope.launch {
            val sent = pairing.send(request, code, MOBILE_REDIRECT)
            state.value = if (sent.isSuccess) {
                PairingUiState(request, PairingStage.DONE)
            } else {
                // Back to the start rather than to a dead end: the code is spent either way, so
                // «Повторить» has to mean a new authorization, which is exactly `confirm()`.
                PairingUiState(request, PairingStage.CONFIRM, sent.errorMessageOrNull())
            }
        }
        return true
    }

    /** «Отмена» and «Готово» are the same act: the television is no longer this screen's business. */
    fun dismiss() {
        expectedState = null
        state.value = PairingUiState()
    }

    private fun rejected() {
        val message = AuthCallbackRejected("Callback does not belong to this pairing").toUserMessage()
        state.update { PairingUiState(it.request, PairingStage.CONFIRM, message) }
    }

    private fun matches(expected: String, actual: String) = MessageDigest.isEqual(
        expected.toByteArray(Charsets.UTF_8),
        actual.toByteArray(Charsets.UTF_8),
    )
}
