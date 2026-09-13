package app.kaeru.ui.common.auth

import app.kaeru.domain.error.AuthCallbackRejected
import app.kaeru.domain.error.NetworkUnavailable
import app.kaeru.domain.repository.AuthRepository
import app.kaeru.test.MainDispatcherRule
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import java.net.UnknownHostException

@OptIn(ExperimentalCoroutinesApi::class)
class AuthViewModelTest {
    @get:Rule val main = MainDispatcherRule()

    private class FakeAuthRepository : AuthRepository {
        val loggedIn = MutableStateFlow(false)
        var exchangeResult: Result<Unit> = Result.success(Unit)
        val redirectExchanges = mutableListOf<Pair<String, String?>>()
        val typedExchanges = mutableListOf<String>()
        val pairedExchanges = mutableListOf<Pair<String, String>>()
        private var attempts = 0
        override val isLoggedIn: Flow<Boolean> = loggedIn

        override fun authorizeUrl(redirectUri: String): String {
            attempts++
            return "https://auth.test/?redirect=$redirectUri&state=state-$attempts"
        }

        override suspend fun exchangeRedirectCode(code: String, state: String?): Result<Unit> {
            redirectExchanges += code to state
            if (state != "state-$attempts") return Result.failure(AuthCallbackRejected("state mismatch"))
            return exchangeResult.onSuccess { loggedIn.value = true }
        }

        override suspend fun exchangeTypedCode(code: String): Result<Unit> {
            typedExchanges += code
            return exchangeResult.onSuccess { loggedIn.value = true }
        }

        override suspend fun exchangePairedCode(code: String, redirectUri: String): Result<Unit> {
            pairedExchanges += code to redirectUri
            return exchangeResult.onSuccess { loggedIn.value = true }
        }

        override suspend fun logout() { loggedIn.value = false }
    }

    @Test
    fun `every sign-in attempt asks for a fresh authorize url`() {
        val vm = AuthViewModel(FakeAuthRepository())
        assertNotEquals(vm.mobileAuthorizeUrl(), vm.mobileAuthorizeUrl())
    }

    @Test
    fun `callback is exchanged with its state and consumed once`() = runTest(main.dispatcher) {
        val repo = FakeAuthRepository()
        val vm = AuthViewModel(repo)
        val state = vm.mobileAuthorizeUrl().substringAfter("state=")
        var consumed = 0
        vm.onMobileCallback("abc", state) { consumed++ }
        advanceUntilIdle()
        assertEquals(listOf("abc" to state), repo.redirectExchanges)
        assertEquals(1, consumed)
        assertTrue(vm.uiState.value.loggedIn == true)
        assertFalse(vm.uiState.value.exchanging)
        assertNull(vm.uiState.value.errorMessage)
    }

    @Test
    fun `callback while logged in is refused without reaching the repository`() = runTest(main.dispatcher) {
        val repo = FakeAuthRepository().also { it.loggedIn.value = true }
        val vm = AuthViewModel(repo)
        advanceUntilIdle()
        var consumed = 0
        vm.onMobileCallback("attacker", "state-1") { consumed++ }
        advanceUntilIdle()
        assertTrue(repo.redirectExchanges.isEmpty())
        assertEquals(1, consumed)
        assertEquals("Вход уже выполнен. Запрос авторизации отклонён", vm.uiState.value.errorMessage)
    }

    @Test
    fun `blank or missing code clears the pending callback and reports an error`() = runTest(main.dispatcher) {
        val repo = FakeAuthRepository()
        val vm = AuthViewModel(repo)
        var consumed = 0
        vm.onMobileCallback("", "state-1") { consumed++ }
        advanceUntilIdle()
        assertEquals("Shikimori не вернул код. Попробуйте войти ещё раз", vm.uiState.value.errorMessage)
        vm.onMobileCallback(null, null) { consumed++ }
        advanceUntilIdle()
        assertEquals("Shikimori не вернул код. Попробуйте войти ещё раз", vm.uiState.value.errorMessage)
        assertEquals(2, consumed)
        assertTrue(repo.redirectExchanges.isEmpty())
        assertFalse(vm.uiState.value.exchanging)
    }

    @Test
    fun `a mismatched state surfaces sign-in copy instead of exception text`() = runTest(main.dispatcher) {
        val repo = FakeAuthRepository()
        val vm = AuthViewModel(repo)
        vm.mobileAuthorizeUrl()
        vm.onMobileCallback("attacker", "guessed") {}
        advanceUntilIdle()
        assertEquals("Не удалось подтвердить вход. Войдите заново", vm.uiState.value.errorMessage)
        assertTrue(vm.uiState.value.loggedIn == false)
    }

    @Test
    fun `exchange failures are mapped to user copy without logging in`() = runTest(main.dispatcher) {
        val repo = FakeAuthRepository().also {
            it.exchangeResult = Result.failure(NetworkUnavailable(UnknownHostException("shikimori.io")))
        }
        val vm = AuthViewModel(repo)
        val state = vm.mobileAuthorizeUrl().substringAfter("state=")
        vm.onMobileCallback("abc", state) {}
        advanceUntilIdle()
        assertEquals("Нет соединения. Проверьте интернет", vm.uiState.value.errorMessage)
        assertTrue(vm.uiState.value.loggedIn == false)
    }

    @Test
    fun `typed tv code is exchanged through the out-of-band path`() = runTest(main.dispatcher) {
        val repo = FakeAuthRepository()
        val vm = AuthViewModel(repo)
        vm.exchangeTvCode("  typed  ")
        advanceUntilIdle()
        assertEquals(listOf("  typed  "), repo.typedExchanges)
        assertTrue(vm.uiState.value.loggedIn == true)
    }
}
