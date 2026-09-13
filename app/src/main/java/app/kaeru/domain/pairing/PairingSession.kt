package app.kaeru.domain.pairing

import java.security.MessageDigest
import java.security.SecureRandom
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.util.Base64

/**
 * The offer a television makes while its login screen is on: one secret, good for five minutes.
 *
 * Time-limited rather than merely single-use, because a television is left on. A QR code
 * photographed from across the room an hour ago should buy nobody an account, so the code on
 * screen stops meaning anything long before the screen does.
 */
data class PairingSession(
    val nonce: String,
    val createdAt: Instant,
    val ttl: Duration = DEFAULT_TTL,
) {
    val expiresAt: Instant get() = createdAt.plus(ttl)

    fun isExpired(now: Instant): Boolean = !now.isBefore(expiresAt)

    /** What is left of the five minutes, floored at zero so a countdown never goes negative. */
    fun remaining(now: Instant): Duration =
        Duration.between(now, expiresAt).let { if (it.isNegative) Duration.ZERO else it }

    /**
     * Whether [nonce] is this session's, saying nothing about whether the session is still open.
     *
     * Asked on its own so that a caller which has not read the code off the television screen can
     * be turned away before anything describes the state of the offer behind it.
     *
     * Compared without short-circuiting: the comparison runs against a value a caller on the
     * network chose, and telling it how many leading characters it got right is free information
     * it should not have.
     */
    fun matches(nonce: String): Boolean =
        MessageDigest.isEqual(this.nonce.toByteArray(Charsets.UTF_8), nonce.toByteArray(Charsets.UTF_8))

    /** Whether [nonce] is this session's, and the session is still open. */
    fun isValid(now: Instant, nonce: String): Boolean = !isExpired(now) && matches(nonce)

    companion object {
        /** Long enough to find the phone and unlock it; short enough that a photograph goes stale. */
        val DEFAULT_TTL: Duration = Duration.ofMinutes(5)

        private const val NONCE_BYTES = 32
        private val random = SecureRandom()

        fun create(clock: Clock, ttl: Duration = DEFAULT_TTL): PairingSession = PairingSession(
            nonce = Base64.getUrlEncoder().withoutPadding()
                .encodeToString(ByteArray(NONCE_BYTES).also(random::nextBytes)),
            createdAt = clock.instant(),
            ttl = ttl,
        )
    }
}
