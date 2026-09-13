package app.kaeru.ui.common.pairing

import app.kaeru.domain.error.PairingFailed
import app.kaeru.domain.error.PairingFailureReason
import app.kaeru.domain.pairing.PairingClient
import app.kaeru.domain.pairing.PairingRequest
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
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class PairingViewModelTest {
    @get:Rule val main = MainDispatcherRule()

    private val television = PairingRequest("192.168.1.7", 41234, "nonce-1", "Гостиная")
    private val link = television.toUri()

    private class FakeAuthRepository : AuthRepository {
        var attempts = 0
        override val isLoggedIn: Flow<Boolean> = MutableStateFlow(true)
        override fun authorizeUrl(redirectUri: String): String {
            attempts++
            return "https://auth.test/?redirect_uri=$redirectUri&state=state-$attempts"
        }
        override suspend fun exchangeRedirectCode(code: String, state: String?) = Result.success(Unit)
        override suspend fun exchangeTypedCode(code: String) = Result.success(Unit)
        override suspend fun exchangePairedCode(code: String, redirectUri: String) = Result.success(Unit)
        override suspend fun logout() = Unit
    }

    private class FakePairingClient : PairingClient {
        val sent = mutableListOf<Triple<PairingRequest, String, String>>()
        var result: Result<Unit> = Result.success(Unit)
        override suspend fun send(request: PairingRequest, code: String, redirectUri: String): Result<Unit> {
            sent += Triple(request, code, redirectUri)
            return result
        }
    }

    private val auth = FakeAuthRepository()
    private val client = FakePairingClient()
    private fun viewModel() = PairingViewModel(auth, client)

    private fun stateOf(url: String) = url.substringAfter("state=")

    @Test
    fun `a television link asks the person before anything is authorized`() {
        val vm = viewModel()
        vm.open(link)
        assertEquals(television, vm.uiState.value.request)
        assertEquals(PairingStage.CONFIRM, vm.uiState.value.stage)
        assertEquals(0, auth.attempts)
    }

    @Test
    fun `a link pointing anywhere but a television on this network is explained and dropped`() {
        val vm = viewModel()
        vm.open("kaeru://pair?host=8.8.8.8&port=80&nonce=n&name=TV")
        assertNull(vm.uiState.value.request)
        assertEquals(PairingStage.FAILED, vm.uiState.value.stage)
        assertEquals("Эта ссылка не для входа на телевизоре", vm.uiState.value.errorMessage)
        assertEquals(0, auth.attempts)
    }

    @Test
    fun `confirming opens a sign-in and starts waiting for the code it will return`() {
        val vm = viewModel()
        vm.open(link)
        val url = vm.confirm()
        assertEquals("https://auth.test/?redirect_uri=$MOBILE_REDIRECT&state=state-1", url)
        assertEquals(PairingStage.AWAITING_CODE, vm.uiState.value.stage)
    }

    @Test
    fun `nothing is authorized when no television asked`() {
        val vm = viewModel()
        assertNull(vm.confirm())
        assertEquals(0, auth.attempts)
    }

    @Test
    fun `the code that comes back goes to the television instead of signing this phone in`() =
        runTest(main.dispatcher) {
            val vm = viewModel()
            vm.open(link)
            val state = stateOf(vm.confirm()!!)
            var consumed = 0

            assertTrue(vm.onMobileCallback("fresh-code", state) { consumed++ })
            advanceUntilIdle()

            assertEquals(1, consumed)
            assertEquals(listOf(Triple(television, "fresh-code", MOBILE_REDIRECT)), client.sent)
            assertEquals(PairingStage.DONE, vm.uiState.value.stage)
            assertNull(vm.uiState.value.errorMessage)
        }

    @Test
    fun `a callback arriving with no pairing in flight is left to the phone's own sign-in`() =
        runTest(main.dispatcher) {
            val vm = viewModel()
            assertFalse(vm.onMobileCallback("fresh-code", "state-1") { })

            vm.open(link)
            assertFalse("a link that was only offered, not confirmed, takes nothing", vm.onMobileCallback("c", "s") { })
            assertTrue(client.sent.isEmpty())
        }

    @Test
    fun `a code from an authorization this phone did not start never reaches the television`() =
        runTest(main.dispatcher) {
            val vm = viewModel()
            vm.open(link)
            vm.confirm()

            assertTrue(vm.onMobileCallback("attacker-code", "guessed") { })
            advanceUntilIdle()

            assertTrue(client.sent.isEmpty())
            assertEquals("Не удалось подтвердить вход. Войдите заново", vm.uiState.value.errorMessage)
            assertEquals(PairingStage.CONFIRM, vm.uiState.value.stage)
        }

    @Test
    fun `a sign-in that came back without a code says so rather than sending nothing`() =
        runTest(main.dispatcher) {
            val vm = viewModel()
            vm.open(link)
            val state = stateOf(vm.confirm()!!)

            assertTrue(vm.onMobileCallback(null, state) { })
            advanceUntilIdle()

            assertTrue(client.sent.isEmpty())
            assertEquals("Не удалось подтвердить вход. Войдите заново", vm.uiState.value.errorMessage)
            assertEquals(PairingStage.CONFIRM, vm.uiState.value.stage)
        }

    @Test
    fun `a television that cannot be reached is explained and the attempt can be made again`() =
        runTest(main.dispatcher) {
            client.result = Result.failure(PairingFailed(PairingFailureReason.UNREACHABLE))
            val vm = viewModel()
            vm.open(link)
            val state = stateOf(vm.confirm()!!)
            vm.onMobileCallback("fresh-code", state) { }
            advanceUntilIdle()

            assertEquals(
                "Телевизор не отвечает. Проверьте, что телефон в той же сети Wi-Fi",
                vm.uiState.value.errorMessage,
            )
            assertEquals(PairingStage.CONFIRM, vm.uiState.value.stage)

            // Retrying is a new authorization, because the code that failed is spent either way.
            client.result = Result.success(Unit)
            val again = vm.confirm()
            assertNotEquals(state, stateOf(again!!))
            assertNull(vm.uiState.value.errorMessage)
            vm.onMobileCallback("second-code", stateOf(again)) { }
            advanceUntilIdle()
            assertEquals(PairingStage.DONE, vm.uiState.value.stage)
        }

    @Test
    fun `a code arriving after the television is already in takes nothing`() = runTest(main.dispatcher) {
        val vm = viewModel()
        vm.open(link)
        vm.onMobileCallback("fresh-code", stateOf(vm.confirm()!!)) { }
        advanceUntilIdle()

        assertFalse(vm.onMobileCallback("another-code", "state-1") { })
        assertEquals(1, client.sent.size)
    }

    @Test
    fun `dismissing puts the phone back where it was`() = runTest(main.dispatcher) {
        val vm = viewModel()
        vm.open(link)
        vm.confirm()
        vm.dismiss()

        assertEquals(PairingUiState(), vm.uiState.value)
        assertFalse(vm.onMobileCallback("fresh-code", "state-1") { })
    }
}
