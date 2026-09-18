package app.kaeru.data.auth

import app.kaeru.data.library.AppPreferences
import app.kaeru.data.local.KaeruDatabase
import app.kaeru.domain.error.AccountSessionChanged
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import javax.inject.Inject
import javax.inject.Singleton

internal data class SessionObservation(val revision: Long, val userId: Long?)

/** Serializes account transitions and the entire lifetime of account-owned repository writes. */
@Singleton
class AccountSession @Inject constructor(
    private val store: TokenStore,
    private val prefs: AppPreferences,
    private val db: KaeruDatabase,
) {
    private val lock = Mutex()
    private val generation = MutableStateFlow(0L)

    // Comparing identities also fails closed for legacy/unbound tokens and interrupted preparation.
    val userId: Flow<Long?> = combine(store.tokens, prefs.userId) { tokens, cachedId ->
        tokens?.userId?.takeIf { it == cachedId }
    }.distinctUntilChanged()

    internal val observations: Flow<SessionObservation> = combine(store.fence.revision, userId) { epoch, id ->
        SessionObservation(epoch, id)
    }

    /** Identity preflight only; [emitIfCurrent] performs the atomic final handoff. */
    internal suspend fun isCurrent(observation: SessionObservation): Boolean {
        if (observation.revision != store.fence.revision.value) return false
        val currentId = store.get()?.userId?.takeIf { it == prefs.userId() }
        // Includes token set/CAS, even when no account transition acquires the write mutex.
        return observation.userId == currentId && observation.revision == store.fence.revision.value
    }

    internal suspend fun emitIfCurrent(observation: SessionObservation, emit: suspend () -> Unit) {
        if (isCurrent(observation)) store.fence.deliver(observation.revision, emit)
    }

    suspend fun <T> withAccount(block: suspend (Long) -> T): T {
        val started = generation.value
        return lock.withLock {
            if (started != generation.value) throw AccountSessionChanged("Account session changed")
            val id = store.get()?.userId ?: throw AccountSessionChanged("No verified account session")
            if (id != prefs.userId()) throw AccountSessionChanged("Account identity is not prepared")
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
        // The one place that knows a sign-in has actually happened, rather than the app having
        // opened on an account that was already there. The screen that puts the notification
        // question reads it from here, so the question survives a rotation and a process death
        // between the login screen and the shell.
        prefs.setNotificationQuestionOwed(true)
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
        store.fence.change {
            generation.value += 1
            try {
                block()
            } finally {
                // Reject writes queued during the transition, including a cancelled/failed transition.
                generation.value += 1
            }
        }
    }
}
