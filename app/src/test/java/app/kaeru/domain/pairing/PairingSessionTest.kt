package app.kaeru.domain.pairing

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneOffset

class PairingSessionTest {
    private val start: Instant = Instant.parse("2026-09-13T20:00:00Z")
    private val session = PairingSession("nonce", start, Duration.ofMinutes(5))

    @Test
    fun `the nonce the television handed out is the only one that matches`() {
        assertTrue(session.matches("nonce"))
        assertFalse(session.matches("Nonce"))
        assertFalse(session.matches("nonce "))
        assertFalse(session.matches(""))
        // Still this session's nonce an hour later, even though the offer is long gone — which is
        // what lets a stranger's guess be turned away before anything describes the offer.
        assertTrue(session.matches("nonce"))
        assertTrue(session.isExpired(start.plus(Duration.ofHours(1))))
    }

    @Test
    fun `the offer stops being open five minutes after it appeared`() {
        assertFalse(session.isExpired(start))
        assertFalse(session.isExpired(start.plus(Duration.ofMinutes(4).plusSeconds(59))))
        assertTrue(session.isExpired(start.plus(Duration.ofMinutes(5))))
        assertTrue(session.isExpired(start.plus(Duration.ofHours(1))))
        assertTrue(session.isExpired(session.expiresAt))
        assertEquals(start.plus(Duration.ofMinutes(5)), session.expiresAt)
    }

    @Test
    fun `what is left of the five minutes never goes below zero`() {
        assertEquals(Duration.ofMinutes(5), session.remaining(start))
        assertEquals(Duration.ofMinutes(1), session.remaining(start.plus(Duration.ofMinutes(4))))
        assertEquals(Duration.ZERO, session.remaining(start.plus(Duration.ofHours(1))))
    }

    @Test
    fun `a fresh session is unguessable and starts now`() {
        val clock = Clock.fixed(start, ZoneOffset.UTC)
        val one = PairingSession.create(clock)
        val two = PairingSession.create(clock)
        assertNotEquals(one.nonce, two.nonce)
        assertTrue(one.nonce, one.nonce.length >= 40)
        assertTrue(one.nonce, one.nonce.all { it.isLetterOrDigit() || it == '-' || it == '_' })
        assertEquals(start, one.createdAt)
        assertEquals(PairingSession.DEFAULT_TTL, one.ttl)
    }
}
