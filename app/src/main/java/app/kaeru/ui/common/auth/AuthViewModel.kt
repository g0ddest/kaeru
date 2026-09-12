package app.kaeru.ui.common.auth

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.kaeru.domain.repository.AuthRepository
import app.kaeru.domain.repository.MOBILE_REDIRECT
import app.kaeru.domain.repository.OOB_REDIRECT
import app.kaeru.ui.common.errorMessageOrNull
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

data class AuthUiState(
    val loggedIn: Boolean? = null,
    val exchanging: Boolean = false,
    val errorMessage: String? = null,
)

private const val NO_CODE = "Shikimori не вернул код. Попробуйте войти ещё раз"
private const val ALREADY_SIGNED_IN = "Вход уже выполнен. Запрос авторизации отклонён"

@HiltViewModel
class AuthViewModel @Inject constructor(private val repository: AuthRepository) : ViewModel() {
    private val exchanging = MutableStateFlow(false)
    private val error = MutableStateFlow<String?>(null)

    /** The TV code is typed by the user, so one QR for the lifetime of the screen is enough. */
    val tvAuthorizeUrl: String = repository.authorizeUrl(OOB_REDIRECT)

    val uiState: StateFlow<AuthUiState> = combine(
        repository.isLoggedIn.map<Boolean, Boolean?> { it },
        exchanging,
        error,
    ) { loggedIn, busy, message -> AuthUiState(loggedIn, busy, message) }
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

    fun exchangeTvCode(code: String) {
        if (code.isBlank() || exchanging.value) return
        exchange { repository.exchangeTypedCode(code) }
    }

    private fun exchange(block: suspend () -> Result<Unit>) {
        viewModelScope.launch {
            exchanging.value = true
            error.value = null
            error.value = block().errorMessageOrNull()
            exchanging.value = false
        }
    }

    fun logout() { viewModelScope.launch { repository.logout() } }
}
