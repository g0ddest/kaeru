package app.kaeru.ui.tv

import app.kaeru.ui.common.auth.AuthFailure
import app.kaeru.ui.common.auth.AuthUiState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

class TvScreenTest {

    @Test
    fun `nothing is shown until the account is known either way`() {
        assertEquals(TvScreen.LOADING, tvScreen(AuthUiState(loggedIn = null)))
        assertEquals(TvScreen.LOADING, tvScreen(AuthUiState(loggedIn = null, exchanging = true)))
    }

    @Test
    fun `a signed-out television gets the login screen`() {
        assertEquals(TvScreen.LOGIN, tvScreen(AuthUiState(loggedIn = false)))
        assertEquals(TvScreen.LOGIN, tvScreen(AuthUiState(loggedIn = false, exchanging = true)))
    }

    /**
     * The invariant a phone signing this television in depends on: the instant the account exists,
     * the login screen is gone, whatever else the last exchange left behind on the state.
     */
    @Test
    fun `a signed-in television never shows the login screen again`() {
        val signedIn = listOf(
            AuthUiState(loggedIn = true),
            AuthUiState(loggedIn = true, exchanging = true),
            AuthUiState(loggedIn = true, errorMessage = "Не удалось подтвердить вход. Войдите заново"),
            AuthUiState(loggedIn = true, failure = AuthFailure.CODE_REJECTED),
            AuthUiState(loggedIn = true, failure = AuthFailure.NO_CONNECTION, exchanging = true),
        )
        signedIn.forEach { state ->
            assertEquals(state.toString(), TvScreen.APP, tvScreen(state))
            assertNotEquals(state.toString(), TvScreen.LOGIN, tvScreen(state))
        }
    }
}
