package app.kaeru.ui.tv.auth

import android.content.Context
import app.kaeru.domain.error.NetworkUnavailable
import app.kaeru.domain.error.PairingFailed
import app.kaeru.domain.error.PairingFailureReason
import app.kaeru.domain.pairing.PairingRequest
import app.kaeru.domain.pairing.PairingSession
import app.kaeru.domain.pairing.TvPairingServer
import app.kaeru.domain.repository.AuthRepository
import app.kaeru.domain.repository.MOBILE_REDIRECT
import app.kaeru.domain.repository.PairingAuthorization
import app.kaeru.test.MainDispatcherRule
import app.kaeru.test.MutableClock
import io.mockk.mockk
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import java.io.IOException
import java.time.Instant

@OptIn(ExperimentalCoroutinesApi::class)
class TvPairingViewModelTest {
    @get:Rule val main = MainDispatcherRule()

    private val now: Instant = Instant.parse("2026-09-13T20:00:00Z")
    private val clock = MutableClock(now)

    /**
     * A stand-in for the socket server. It records what it was asked to do and hands back the code
     * on demand, so the view model's own half — the offer, the countdown, the statuses — can be
     * driven without a port.
     */
    private class FakeServer : TvPairingServer {
        var endpoint: Result<TvPairingServer.Endpoint> =
            Result.success(TvPairingServer.Endpoint("192.168.1.7", 41_234))
        val sessions = mutableListOf<PairingSession>()
        var stops = 0
        private var onCode: (suspend (String, String) -> Result<Unit>)? = null

        override suspend fun start(
            session: PairingSession,
            onCode: suspend (code: String, redirectUri: String) -> Result<Unit>,
        ): Result<TvPairingServer.Endpoint> {
            sessions += session
            this.onCode = onCode
            return endpoint
        }

        override fun stop() {
            stops++
        }

        suspend fun deliver(code: String): Result<Unit> = onCode!!.invoke(code, MOBILE_REDIRECT)
    }

    private class FakeAuthRepository : AuthRepository {
        val loggedIn = MutableStateFlow(false)
        val paired = mutableListOf<Pair<String, String>>()
        var result: Result<Unit> = Result.success(Unit)
        override val isLoggedIn: Flow<Boolean> = loggedIn
        override fun authorizeUrl(redirectUri: String) = "https://auth.test/"
        override fun pairingAuthorization() = PairingAuthorization("https://auth.test/", "state")
        override suspend fun exchangeRedirectCode(code: String, state: String?) = Result.success(Unit)
        override suspend fun exchangeTypedCode(code: String) = Result.success(Unit)
        override suspend fun exchangePairedCode(code: String, redirectUri: String): Result<Unit> {
            paired += code to redirectUri
            return result.onSuccess { loggedIn.value = true }
        }
        override suspend fun logout() = Unit
    }

    private val server = FakeServer()
    private val auth = FakeAuthRepository()

    // `Settings.Global` and `Build.MODEL` both answer null under the unit-test android stubs, so
    // the name falls through to the last resort — which is the branch worth pinning anyway.
    private fun viewModel() = TvPairingViewModel(server, auth, clock, mockk<Context>(relaxed = true))

    @Test
    fun `starting puts an offer on screen that a phone can act on`() = runTest(main.dispatcher) {
        val vm = viewModel()
        vm.start()
        runCurrent()

        val state = vm.uiState.value
        assertEquals(TvPairingStatus.WAITING, state.status)
        assertEquals(now.plus(PairingSession.DEFAULT_TTL), state.expiresAt)
        assertNull(state.errorMessage)

        val request = PairingRequest.parse(state.pairingUri!!).getOrThrow()
        assertEquals("192.168.1.7", request.host)
        assertEquals(41_234, request.port)
        assertEquals(server.sessions.single().nonce, request.nonce)
        assertEquals(state.deviceName, request.name)
    }

    @Test
    fun `a television with nowhere to be reached says so and offers nothing to scan`() =
        runTest(main.dispatcher) {
            server.endpoint = Result.failure(PairingFailed(PairingFailureReason.NO_LOCAL_ADDRESS))
            val vm = viewModel()
            vm.start()
            runCurrent()

            assertEquals(TvPairingStatus.UNAVAILABLE, vm.uiState.value.status)
            assertNull(vm.uiState.value.pairingUri)
            assertNull(vm.uiState.value.expiresAt)
            assertEquals("Телевизор не в локальной сети. Войдите по коду", vm.uiState.value.errorMessage)
        }

    @Test
    fun `a phone that sends a code moves the screen on before the tokens arrive`() =
        runTest(main.dispatcher) {
            val vm = viewModel()
            vm.start()
            runCurrent()

            assertTrue(server.deliver("fresh-code").isSuccess)
            runCurrent()

            assertEquals(TvPairingStatus.CONFIRMING, vm.uiState.value.status)
            assertEquals(listOf("fresh-code" to MOBILE_REDIRECT), auth.paired)
            assertNull(vm.uiState.value.errorMessage)
        }

    @Test
    fun `an exchange the television could not finish goes back to waiting with the reason`() =
        runTest(main.dispatcher) {
            auth.result = Result.failure(NetworkUnavailable(IOException("shikimori.io")))
            val vm = viewModel()
            vm.start()
            runCurrent()

            assertTrue(server.deliver("fresh-code").isFailure)
            runCurrent()

            assertEquals(TvPairingStatus.WAITING, vm.uiState.value.status)
            assertEquals("Нет соединения. Проверьте интернет", vm.uiState.value.errorMessage)
            // The offer is still up: the code was spent, the QR was not.
            assertTrue(vm.uiState.value.pairingUri != null)
        }

    @Test
    fun `five minutes with nobody in the room closes the port and offers a new code`() =
        runTest(main.dispatcher) {
            val vm = viewModel()
            vm.start()
            runCurrent()
            assertEquals(0, server.stops)

            advanceTimeBy(PairingSession.DEFAULT_TTL.toMillis() + 1)
            runCurrent()

            assertEquals(TvPairingStatus.EXPIRED, vm.uiState.value.status)
            assertNull(vm.uiState.value.pairingUri)
            assertNull(vm.uiState.value.expiresAt)
            assertEquals(1, server.stops)
        }

    @Test
    fun `pressing for a new code replaces the offer rather than adding one`() = runTest(main.dispatcher) {
        val vm = viewModel()
        vm.start()
        runCurrent()
        val first = vm.uiState.value.pairingUri

        vm.start()
        runCurrent()

        assertEquals(2, server.sessions.size)
        assertNotEquals(server.sessions[0].nonce, server.sessions[1].nonce)
        assertNotEquals(first, vm.uiState.value.pairingUri)
        assertEquals(TvPairingStatus.WAITING, vm.uiState.value.status)
    }

    @Test
    fun `the old offer's timer cannot expire the new one`() = runTest(main.dispatcher) {
        val vm = viewModel()
        vm.start()
        runCurrent()
        advanceTimeBy(PairingSession.DEFAULT_TTL.toMillis() / 2)

        vm.start()
        runCurrent()
        advanceTimeBy(PairingSession.DEFAULT_TTL.toMillis() / 2 + 1)
        runCurrent()

        // The first offer's five minutes are up; the second one's are not.
        assertEquals(TvPairingStatus.WAITING, vm.uiState.value.status)
    }

    @Test
    fun `leaving the screen closes the port`() = runTest(main.dispatcher) {
        val vm = viewModel()
        vm.start()
        runCurrent()

        vm.stop()
        advanceTimeBy(PairingSession.DEFAULT_TTL.toMillis() + 1)
        runCurrent()

        assertEquals(1, server.stops)
        // The expiry timer went with it: nothing rewrites the state of a screen that is gone.
        assertEquals(TvPairingStatus.WAITING, vm.uiState.value.status)
    }
}
