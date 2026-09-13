package app.kaeru.ui.common.settings

import app.kaeru.domain.model.Account
import app.kaeru.domain.model.Quality
import app.kaeru.domain.playback.TranslationRanker
import app.kaeru.domain.repository.AccountRepository
import app.kaeru.domain.repository.AuthRepository
import app.kaeru.domain.settings.FakeSettingsStore
import app.kaeru.test.MainDispatcherRule
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class SettingsViewModelTest {
    @get:Rule val main = MainDispatcherRule()

    private val defaults = TranslationRanker.DEFAULT_STUDIOS

    private class FakeAccountRepository(cached: Account? = null) : AccountRepository {
        val cache = MutableStateFlow(cached)
        var result: Result<Unit> = Result.success(Unit)
        var refreshed: Account? = null
        var refreshes = 0

        /** Held open by a test that needs to see the screen while an answer is still on its way. */
        var gate: CompletableDeferred<Unit>? = null
        override val account: Flow<Account?> = cache

        override suspend fun refresh(): Result<Unit> {
            refreshes++
            gate?.await()
            return result.onSuccess { refreshed?.let { cache.value = it } }
        }
    }

    private class FakeAuthRepository : AuthRepository {
        var logouts = 0
        override val isLoggedIn: Flow<Boolean> = MutableStateFlow(true)
        override fun authorizeUrl(redirectUri: String) = "https://auth.test/"
        override suspend fun exchangeRedirectCode(code: String, state: String?) = Result.success(Unit)
        override suspend fun exchangeTypedCode(code: String) = Result.success(Unit)
        override suspend fun logout() { logouts++ }
    }

    private fun viewModel(
        store: FakeSettingsStore = FakeSettingsStore(),
        accounts: FakeAccountRepository = FakeAccountRepository(),
        auth: FakeAuthRepository = FakeAuthRepository(),
    ) = SettingsViewModel(store, accounts, auth)

    @Test
    fun `the dub order starts as the one the app ships with, and there is nothing to reset`() =
        runTest(main.dispatcher) {
            val vm = viewModel()
            advanceUntilIdle()

            assertEquals(defaults, vm.uiState.value.studios)
            assertFalse(vm.uiState.value.studiosChosen)
        }

    @Test
    fun `a stored order replaces the app's and can be reset`() = runTest(main.dispatcher) {
        val vm = viewModel(FakeSettingsStore(studios = listOf("JAM", "AniDUB")))
        advanceUntilIdle()

        assertEquals(listOf("JAM", "AniDUB"), vm.uiState.value.studios)
        assertTrue(vm.uiState.value.studiosChosen)
    }

    @Test
    fun `the first move writes the whole order down, so the app's guess becomes the viewer's list`() =
        runTest(main.dispatcher) {
            val store = FakeSettingsStore()
            val vm = viewModel(store)
            advanceUntilIdle()

            vm.moveStudioUp(1)
            advanceUntilIdle()

            val expected = listOf(defaults[1], defaults[0]) + defaults.drop(2)
            assertEquals(expected, vm.uiState.value.studios)
            assertTrue(vm.uiState.value.studiosChosen)
            assertEquals(listOf("studios=$expected"), store.writes)
        }

    @Test
    fun `a move that changes nothing writes nothing`() = runTest(main.dispatcher) {
        val store = FakeSettingsStore(studios = listOf("JAM", "AniDUB"))
        val vm = viewModel(store)
        advanceUntilIdle()

        vm.moveStudioUp(0)
        vm.moveStudioDown(1)
        vm.removeStudio(9)
        vm.addStudio("  ")
        vm.addStudio("jam")
        advanceUntilIdle()

        assertEquals(emptyList<String>(), store.writes)
        assertEquals(listOf("JAM", "AniDUB"), vm.uiState.value.studios)
    }

    @Test
    fun `moving down, removing and adding all land on the store`() = runTest(main.dispatcher) {
        val store = FakeSettingsStore(studios = listOf("JAM", "AniDUB", "SHIZA Project"))
        val vm = viewModel(store)
        advanceUntilIdle()

        vm.moveStudioDown(0)
        advanceUntilIdle()
        assertEquals(listOf("AniDUB", "JAM", "SHIZA Project"), vm.uiState.value.studios)

        vm.removeStudio(2)
        advanceUntilIdle()
        assertEquals(listOf("AniDUB", "JAM"), vm.uiState.value.studios)

        vm.addStudio(" Dream Cast ")
        advanceUntilIdle()
        assertEquals(listOf("AniDUB", "JAM", "Dream Cast"), vm.uiState.value.studios)
    }

    @Test
    fun `the last studio cannot be taken away, because an empty list is a reset in disguise`() =
        runTest(main.dispatcher) {
            val vm = viewModel(FakeSettingsStore(studios = listOf("JAM")))
            advanceUntilIdle()

            assertFalse(vm.uiState.value.canRemoveStudio)
        }

    @Test
    fun `resetting stores nothing and shows the app's order again`() = runTest(main.dispatcher) {
        val store = FakeSettingsStore(studios = listOf("JAM", "AniDUB"))
        val vm = viewModel(store)
        advanceUntilIdle()

        vm.resetStudios()
        advanceUntilIdle()

        assertEquals(defaults, vm.uiState.value.studios)
        assertFalse(vm.uiState.value.studiosChosen)
        assertEquals(listOf("studios=[]"), store.writes)
    }

    @Test
    fun `resetting an order that was never chosen writes nothing`() = runTest(main.dispatcher) {
        val store = FakeSettingsStore()
        val vm = viewModel(store)
        advanceUntilIdle()

        vm.resetStudios()
        advanceUntilIdle()

        assertEquals(emptyList<String>(), store.writes)
    }

    @Test
    fun `a setting shows its new value before the store says so`() = runTest(main.dispatcher) {
        val store = FakeSettingsStore(quality = Quality.P720, echo = false)
        val vm = viewModel(store)
        advanceUntilIdle()

        vm.setAutoplayNext(false)
        vm.setDefaultQuality(null)
        vm.setWatchedThreshold(0.8f)
        vm.setKodikToken("typed-by-hand")
        advanceUntilIdle()

        assertFalse(vm.uiState.value.autoplayNext)
        assertNull(vm.uiState.value.defaultQuality)
        assertEquals(0.8f, vm.uiState.value.watchedThreshold, 0.0001f)
        assertEquals("typed-by-hand", vm.uiState.value.kodikToken)
        assertEquals(
            listOf("autoplay=false", "quality=null", "threshold=0.8", "token=typed-by-hand"),
            store.writes,
        )
    }

    @Test
    fun `choosing what is already chosen writes nothing`() = runTest(main.dispatcher) {
        val store = FakeSettingsStore(autoplay = true, quality = Quality.P480, threshold = 0.9f, token = "t")
        val vm = viewModel(store)
        advanceUntilIdle()

        vm.setAutoplayNext(true)
        vm.setDefaultQuality(Quality.P480)
        vm.setWatchedThreshold(0.9f)
        vm.setKodikToken("  t  ")
        advanceUntilIdle()

        assertEquals(emptyList<String>(), store.writes)
    }

    @Test
    fun `an emptied token field clears the key rather than storing a blank one`() =
        runTest(main.dispatcher) {
            val store = FakeSettingsStore(token = "typed-by-hand")
            val vm = viewModel(store)
            advanceUntilIdle()

            vm.setKodikToken("   ")
            advanceUntilIdle()

            assertEquals(listOf("token=null"), store.writes)
            assertEquals("", vm.uiState.value.kodikToken)
        }

    @Test
    fun `an absent token reads as an empty field`() = runTest(main.dispatcher) {
        val vm = viewModel()
        advanceUntilIdle()

        assertEquals("", vm.uiState.value.kodikToken)
    }

    @Test
    fun `the account is a skeleton until somebody can be named`() = runTest(main.dispatcher) {
        val accounts = FakeAccountRepository()
        accounts.refreshed = Account(42, "kaeru", "https://shikimori.io/a.png")
        val vm = viewModel(accounts = accounts)

        assertTrue(vm.uiState.value.accountLoading)

        advanceUntilIdle()

        assertFalse(vm.uiState.value.accountLoading)
        assertEquals(Account(42, "kaeru", "https://shikimori.io/a.png"), vm.uiState.value.account)
        assertEquals(1, accounts.refreshes)
    }

    @Test
    fun `a cached account is shown at once and Shikimori is asked anyway`() = runTest(main.dispatcher) {
        val accounts = FakeAccountRepository(Account(42, "kaeru", null))
        val vm = viewModel(accounts = accounts)
        advanceUntilIdle()

        assertFalse(vm.uiState.value.accountLoading)
        assertEquals(Account(42, "kaeru", null), vm.uiState.value.account)
        assertEquals(1, accounts.refreshes)
    }

    @Test
    fun `a refresh that fails with nothing cached stops the skeleton and names nobody`() =
        runTest(main.dispatcher) {
            val accounts = FakeAccountRepository()
            accounts.result = Result.failure(IllegalStateException("offline"))
            val vm = viewModel(accounts = accounts)
            advanceUntilIdle()

            assertFalse(vm.uiState.value.accountLoading)
            assertNull(vm.uiState.value.account)
        }

    @Test
    fun `asking again puts the skeleton back and can succeed where the first try failed`() =
        runTest(main.dispatcher) {
            val accounts = FakeAccountRepository()
            accounts.result = Result.failure(IllegalStateException("offline"))
            val vm = viewModel(accounts = accounts)
            advanceUntilIdle()
            assertFalse(vm.uiState.value.accountLoading)

            accounts.result = Result.success(Unit)
            accounts.refreshed = Account(42, "kaeru", null)
            val answering = CompletableDeferred<Unit>()
            accounts.gate = answering

            vm.refreshAccount()
            advanceUntilIdle()
            // The question is out and nobody is named yet, so the skeleton is back rather than a
            // line saying the name could not be loaded.
            assertTrue(vm.uiState.value.accountLoading)

            answering.complete(Unit)
            advanceUntilIdle()

            assertEquals(Account(42, "kaeru", null), vm.uiState.value.account)
            assertFalse(vm.uiState.value.accountLoading)
            assertEquals(2, accounts.refreshes)
        }

    @Test
    fun `nothing signs the viewer out until the confirmation does`() = runTest(main.dispatcher) {
        val auth = FakeAuthRepository()
        val store = FakeSettingsStore()
        val vm = viewModel(store, auth = auth)
        advanceUntilIdle()

        vm.setAutoplayNext(false)
        vm.addStudio("JAM")
        vm.resetStudios()
        advanceUntilIdle()
        assertEquals(0, auth.logouts)

        vm.signOut()
        advanceUntilIdle()

        assertEquals(1, auth.logouts)
        // Signing out is the auth repository's business and nothing else's: the settings this
        // device keeps are not the account's to take.
        assertFalse(store.writes.any { it.startsWith("token=") })
    }
}
