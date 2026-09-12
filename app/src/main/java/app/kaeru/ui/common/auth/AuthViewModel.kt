package app.kaeru.ui.common.auth

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.kaeru.domain.repository.AuthRepository
import app.kaeru.domain.repository.MOBILE_REDIRECT
import app.kaeru.domain.repository.OOB_REDIRECT
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

@HiltViewModel
class AuthViewModel @Inject constructor(private val repository: AuthRepository) : ViewModel() {
    private val exchanging = MutableStateFlow(false)
    private val error = MutableStateFlow<String?>(null)
    val mobileAuthorizeUrl: String = repository.authorizeUrl(MOBILE_REDIRECT)
    val tvAuthorizeUrl: String = repository.authorizeUrl(OOB_REDIRECT)

    val uiState: StateFlow<AuthUiState> = combine(
        repository.isLoggedIn.map<Boolean, Boolean?> { it },
        exchanging,
        error,
    ) { loggedIn, busy, message -> AuthUiState(loggedIn, busy, message) }
        .stateIn(viewModelScope, SharingStarted.Eagerly, AuthUiState())

    fun exchangeMobileCode(code: String, consumed: () -> Unit) = exchange(code, MOBILE_REDIRECT, consumed)
    fun exchangeTvCode(code: String) = exchange(code.trim(), OOB_REDIRECT) {}

    private fun exchange(code: String, redirect: String, consumed: () -> Unit) {
        if (code.isBlank() || exchanging.value) return
        consumed()
        viewModelScope.launch {
            exchanging.value = true
            error.value = null
            val result = repository.exchangeCode(code, redirect)
            error.value = result.exceptionOrNull()?.message ?: result.exceptionOrNull()?.let { "Не удалось войти" }
            exchanging.value = false
        }
    }

    fun logout() { viewModelScope.launch { repository.logout() } }
}
