package app.kaeru.data.auth

import app.kaeru.data.library.AppPreferences
import app.kaeru.data.local.KaeruDatabase
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.concurrent.atomic.AtomicLong
import javax.inject.Inject
import javax.inject.Singleton

/** Serializes account transitions and the entire lifetime of account-owned repository writes. */
@Singleton
class AccountSession @Inject constructor(
    private val store: TokenStore,
    private val prefs: AppPreferences,
    private val db: KaeruDatabase,
) {
    private val lock = Mutex()
    private val generation = AtomicLong()

    // Comparing identities also fails closed for legacy/unbound tokens and interrupted preparation.
    val userId: Flow<Long?> = combine(store.tokens, prefs.userId) { tokens, cachedId ->
        tokens?.userId?.takeIf { it == cachedId }
    }.distinctUntilChanged()

    suspend fun <T> withAccount(block: suspend (Long) -> T): T {
        val started = generation.get()
        return lock.withLock {
            check(started == generation.get()) { "Account session changed" }
            val id = checkNotNull(store.get()?.userId) { "No verified account session" }
            check(id == prefs.userId()) { "Account identity is not prepared" }
            block(id)
        }
    }

    /** Candidate credentials stay private until identity verification and cache preparation finish. */
    suspend fun login(verify: suspend () -> AuthTokens) = transition {
        val candidate = verify()
        val id = requireNotNull(candidate.userId)
        // Invalidates refresh CAS snapshots and publishes logged-out before changing account stores.
        store.set(null)
        if (prefs.userId() != id) clearAccount()
        prefs.setUserId(id)
        store.set(candidate)
    }

    suspend fun logout() = transition {
        store.set(null)
        clearAccount()
    }

    private suspend fun clearAccount() {
        // Keep the old identity until both Room tables are gone. A crash/failure cannot label A as B.
        db.clearAccountData()
        prefs.clearAccount()
    }

    private suspend fun <T> transition(block: suspend () -> T): T = lock.withLock {
        generation.incrementAndGet()
        try {
            block()
        } finally {
            // Reject writes queued during the transition, including a cancelled/failed transition.
            generation.incrementAndGet()
        }
    }
}
