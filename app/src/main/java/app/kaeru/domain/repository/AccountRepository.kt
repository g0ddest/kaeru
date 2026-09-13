package app.kaeru.domain.repository

import app.kaeru.domain.model.Account
import kotlinx.coroutines.flow.Flow

/**
 * Who is signed in, cached so a screen can name them without waiting for the network.
 *
 * [account] is the cache and answers immediately; [refresh] goes and asks Shikimori, and is
 * expected to fail whenever the connection is down. A caller that has an account on screen should
 * ignore that failure — the nickname beside an avatar is not worth an error banner, and the
 * cached one is still the right answer.
 */
interface AccountRepository {
    /** The signed-in account, or null when nobody is signed in and nothing was cached. */
    val account: Flow<Account?>

    /** Asks Shikimori who this is and updates the cache. Quiet on failure: the cache stands. */
    suspend fun refresh(): Result<Unit>
}
