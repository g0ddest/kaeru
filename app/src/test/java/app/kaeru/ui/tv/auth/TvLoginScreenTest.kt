package app.kaeru.ui.tv.auth

import app.kaeru.ui.common.auth.AuthFailure
import app.kaeru.ui.common.auth.AuthUiState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Duration
import java.time.Instant

class TvLoginScreenTest {
    private val now: Instant = Instant.parse("2026-09-13T20:00:00Z")
    private val authorizeUrl = "https://shikimori.io/oauth/authorize?client_id=cid"
    private val pairingUri = "kaeru://pair?host=192.168.1.7&port=41234&nonce=n&name=%D0%93%D0%BE%D1%81%D1%82%D0%B8%D0%BD%D0%B0%D1%8F"

    private fun waiting(
        status: TvPairingStatus = TvPairingStatus.WAITING,
        uri: String? = pairingUri,
        expiresAt: Instant? = now.plus(Duration.ofMinutes(5)),
        error: String? = null,
        name: String = "Гостиная",
    ) = TvPairingUiState(name, uri, expiresAt, status, error)

    @Test
    fun `the big code is the pairing link while there is one`() {
        assertEquals(pairingUri, tvLoginQr(waiting(), authorizeUrl))
    }

    @Test
    fun `a television with nowhere to be reached shows the code that still works`() {
        val stranded = waiting(status = TvPairingStatus.UNAVAILABLE, uri = null, expiresAt = null)
        assertEquals(authorizeUrl, tvLoginQr(stranded, authorizeUrl))
        assertEquals(
            "Отсканируйте камерой телефона и введите код с экрана Shikimori",
            tvLoginHint(stranded),
        )
    }

    @Test
    fun `the hint tells the phone what to point at`() {
        assertEquals("Отсканируйте камерой телефона с Kaeru", tvLoginHint(waiting()))
    }

    @Test
    fun `the status line says what the television is waiting for`() {
        assertEquals("Готовим код…", tvLoginStatus(waiting(status = TvPairingStatus.STARTING)))
        assertEquals("Ждём телефон…", tvLoginStatus(waiting()))
        assertEquals("Телефон подтвердил, входим…", tvLoginStatus(waiting(status = TvPairingStatus.CONFIRMING)))
        assertEquals("Код устарел", tvLoginStatus(waiting(status = TvPairingStatus.EXPIRED, expiresAt = null)))
    }

    @Test
    fun `a failure replaces the status rather than hiding under it`() {
        val failed = waiting(error = "Нет соединения. Проверьте интернет")
        assertEquals("Нет соединения. Проверьте интернет", tvLoginStatus(failed))
    }

    @Test
    fun `the five minutes are counted down quietly and stop at nothing`() {
        assertEquals("Ещё 5:00", tvLoginCountdown(waiting(), now))
        assertEquals("Ещё 0:23", tvLoginCountdown(waiting(expiresAt = now.plusSeconds(23)), now))
        assertNull(tvLoginCountdown(waiting(expiresAt = now), now))
        assertNull(tvLoginCountdown(waiting(expiresAt = null), now))
        assertNull("a television nobody can reach is not counting anything down",
            tvLoginCountdown(waiting(status = TvPairingStatus.UNAVAILABLE, uri = null, expiresAt = null), now))
    }

    @Test
    fun `a new code is offered only once the old one stopped working`() {
        assertFalse(tvLoginOffersNewQr(waiting()))
        assertFalse(tvLoginOffersNewQr(waiting(status = TvPairingStatus.CONFIRMING)))
        assertTrue(tvLoginOffersNewQr(waiting(status = TvPairingStatus.EXPIRED, expiresAt = null)))
        assertTrue("a television that found a network later deserves another go",
            tvLoginOffersNewQr(waiting(status = TvPairingStatus.UNAVAILABLE, uri = null, expiresAt = null)))
    }

    @Test
    fun `a television with no name of its own still says which one it is`() {
        assertEquals("Гостиная", tvLoginName(waiting()))
        assertEquals("Этот телевизор", tvLoginName(waiting(name = "")))
        assertEquals("Этот телевизор", tvLoginName(waiting(name = "   ")))
    }

    @Test
    fun `a code that did not work says so, and says what to do about it`() {
        assertEquals(
            "Код не подошёл или уже использован. Получите новый код и попробуйте ещё раз",
            tvCodeStatus(AuthUiState(loggedIn = false, failure = AuthFailure.CODE_REJECTED)),
        )
    }

    @Test
    fun `a code that never got through blames the connection rather than the viewer`() {
        assertEquals(
            "Нет связи с Shikimori. Повторить",
            tvCodeStatus(AuthUiState(loggedIn = false, failure = AuthFailure.NO_CONNECTION)),
        )
    }

    @Test
    fun `a throttled Shikimori is told apart from a code that was actually refused`() {
        assertEquals(
            "Shikimori просит подождать. Повторите через минуту",
            tvCodeStatus(AuthUiState(loggedIn = false, failure = AuthFailure.THROTTLED)),
        )
    }

    @Test
    fun `every kind of failure gets a line of its own`() {
        val lines = AuthFailure.entries.map { failure ->
            tvCodeStatus(AuthUiState(loggedIn = false, failure = failure))
        }
        assertEquals(AuthFailure.entries.size, lines.toSet().size)
    }

    @Test
    fun `an exchange in flight says so instead of leaving the last failure on screen`() {
        val busy = AuthUiState(loggedIn = false, exchanging = true, failure = AuthFailure.CODE_REJECTED)
        assertEquals("Входим…", tvCodeStatus(busy))
    }

    @Test
    fun `a field nobody has failed at yet says nothing at all`() {
        assertNull(tvCodeStatus(AuthUiState(loggedIn = false)))
    }

    @Test
    fun `nothing can be typed or submitted while the code is being checked`() {
        val busy = AuthUiState(loggedIn = false, exchanging = true)
        assertFalse(tvCodeFieldEnabled(busy))
        assertFalse(tvCodeSubmitEnabled(busy, "aBc1dEf2"))
    }

    @Test
    fun `the button waits for something to submit`() {
        val idle = AuthUiState(loggedIn = false)
        assertTrue(tvCodeFieldEnabled(idle))
        assertFalse(tvCodeSubmitEnabled(idle, ""))
        assertFalse(tvCodeSubmitEnabled(idle, "   "))
        assertTrue(tvCodeSubmitEnabled(idle, "aBc1dEf2"))
    }

    @Test
    fun `nothing on this screen uses the separator the design system forbids`() {
        TvPairingStatus.entries.forEach { status ->
            val state = waiting(status = status)
            assertTrue(status.name, '·' !in tvLoginStatus(state) && '·' !in tvLoginHint(state))
        }
        AuthFailure.entries.forEach { failure ->
            val line = tvCodeStatus(AuthUiState(loggedIn = false, failure = failure))
            assertTrue(failure.name, line != null && '·' !in line)
        }
    }
}
