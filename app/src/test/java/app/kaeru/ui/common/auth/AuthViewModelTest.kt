package app.kaeru.ui.common.auth

import app.kaeru.domain.repository.AuthRepository
import app.kaeru.domain.repository.MOBILE_REDIRECT
import app.kaeru.test.MainDispatcherRule
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class AuthViewModelTest {
    @get:Rule val main = MainDispatcherRule()

    private class FakeAuthRepository : AuthRepository {
        val loggedIn = MutableStateFlow(false)
        var exchangeResult: Result<Unit> = Result.success(Unit)
        var exchange: Pair<String, String>? = null
        override val isLoggedIn: Flow<Boolean> = loggedIn
        override fun authorizeUrl(redirectUri: String) = "https://auth.test/?redirect=$redirectUri"
        override suspend fun exchangeCode(code: String, redirectUri: String): Result<Unit> {
            exchange = code to redirectUri
            exchangeResult.onSuccess { loggedIn.value = true }
            return exchangeResult
        }
        override suspend fun logout() { loggedIn.value = false }
    }

    @Test
    fun `mobile code is exchanged with mobile redirect and consumed once`() = runTest(main.dispatcher) {
        val repo = FakeAuthRepository()
        val vm = AuthViewModel(repo)
        var consumed = 0
        vm.exchangeMobileCode("abc") { consumed++ }
        advanceUntilIdle()
        assertEquals("abc" to MOBILE_REDIRECT, repo.exchange)
        assertEquals(1, consumed)
        assertTrue(vm.uiState.value.loggedIn == true)
        assertFalse(vm.uiState.value.exchanging)
    }

    @Test
    fun `exchange error remains visible without logging in`() = runTest(main.dispatcher) {
        val repo = FakeAuthRepository().also { it.exchangeResult = Result.failure(Exception("bad code")) }
        val vm = AuthViewModel(repo)
        vm.exchangeMobileCode("bad") {}
        advanceUntilIdle()
        assertEquals("bad code", vm.uiState.value.errorMessage)
        assertTrue(vm.uiState.value.loggedIn == false)
    }
}
