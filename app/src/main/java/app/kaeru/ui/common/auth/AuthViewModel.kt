package app.kaeru.ui.common.auth

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.kaeru.domain.error.HttpError
import app.kaeru.domain.error.NetworkUnavailable
import app.kaeru.domain.repository.AuthRepository
import app.kaeru.domain.repository.MOBILE_REDIRECT
import app.kaeru.domain.repository.OOB_REDIRECT
import app.kaeru.ui.common.errorMessageOrNull
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * Why a sign-in attempt failed, in the only two ways the person in front of the screen can act on.
 *
 * The difference matters on a television: one of them means «fetch another code», the other means
 * «try the same thing again in a moment», and a single generic line sends half the viewers down
 * the wrong path.
 */
enum class AuthFailure {
    /** Shikimori was reached and turned the code away: wrong, expired, or already spent. */
    CODE_REJECTED,

    /** Shikimori was never reached, or answered that it is having a bad day. */
    NO_CONNECTION,
}

data class AuthUiState(
    val loggedIn: Boolean? = null,
    val exchanging: Boolean = false,
    val errorMessage: String? = null,
    val failure: AuthFailure? = null,
)

private const val NO_CODE = "Shikimori не вернул код. Попробуйте войти ещё раз"
private const val ALREADY_SIGNED_IN = "Вход уже выполнен. Запрос авторизации отклонён"

@HiltViewModel
class AuthViewModel @Inject constructor(private val repository: AuthRepository) : ViewModel() {
    private val exchanging = MutableStateFlow(false)
    private val error = MutableStateFlow<String?>(null)
    private val failure = MutableStateFlow<AuthFailure?>(null)
    private val code = MutableStateFlow("")

    /** The TV code is typed by the user, so one QR for the lifetime of the screen is enough. */
    val tvAuthorizeUrl: String = repository.authorizeUrl(OOB_REDIRECT)

    /**
     * The code being typed on the television, held here rather than in the screen because what
     * clears it is the answer to an attempt, which arrives long after the keystroke.
     */
    val tvCode: StateFlow<String> = code.asStateFlow()

    val uiState: StateFlow<AuthUiState> = combine(
        repository.isLoggedIn.map<Boolean, Boolean?> { it },
        exchanging,
        error,
        failure,
    ) { loggedIn, busy, message, kind -> AuthUiState(loggedIn, busy, message, kind) }
        .stateIn(viewModelScope, SharingStarted.Eagerly, AuthUiState())

    /** Call per sign-in attempt: every call arms a new `state` for the callback to echo. */
    fun mobileAuthorizeUrl(): String = repository.authorizeUrl(MOBILE_REDIRECT)

    /**
     * Handles one `kaeru://oauth` deep link. Anything can fire that link, so the callback is
     * always consumed but only exchanged when it plausibly belongs to a sign-in we started; the
     * repository then has the final say on whether its `state` matches.
     */
    fun onMobileCallback(code: String?, state: String?, consumed: () -> Unit) {
        if (exchanging.value) return
        consumed()
        if (uiState.value.loggedIn == true) {
            error.value = ALREADY_SIGNED_IN
            return
        }
        if (code.isNullOrBlank()) {
            error.value = NO_CODE
            return
        }
        exchange { repository.exchangeRedirectCode(code, state) }
    }

    fun setTvCode(value: String) {
        code.value = value
    }

    fun exchangeTvCode() {
        val typed = code.value
        if (typed.isBlank() || exchanging.value) return
        exchange { repository.exchangeTypedCode(typed) }
    }

    private fun exchange(block: suspend () -> Result<Unit>) {
        viewModelScope.launch {
            exchanging.value = true
            error.value = null
            failure.value = null
            val result = block()
            error.value = result.errorMessageOrNull()
            failure.value = result.exceptionOrNull()?.let(::classify)
            // An authorization code is worth one attempt whatever became of it. Leaving the spent
            // one in the field invites pressing the button on it again, which cannot work and says
            // nothing new when it does not.
            code.value = ""
            exchanging.value = false
        }
    }

    /**
     * A failure the viewer can do something about. Anything that means Shikimori was not reached —
     * including Shikimori answering that it is broken — is the connection; everything else is the
     * code, because that is the half of this the viewer can replace.
     */
    private fun classify(error: Throwable): AuthFailure = when {
        error is NetworkUnavailable -> AuthFailure.NO_CONNECTION
        error is HttpError && error.code in 500..599 -> AuthFailure.NO_CONNECTION
        else -> AuthFailure.CODE_REJECTED
    }

    fun logout() { viewModelScope.launch { repository.logout() } }
}
