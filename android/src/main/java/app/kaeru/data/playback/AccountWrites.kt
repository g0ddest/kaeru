package app.kaeru.data.playback

import app.kaeru.data.auth.AccountSession
import app.kaeru.domain.error.AccountSessionChanged
import app.kaeru.domain.error.StorageFailure
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext

/**
 * A write to rows that belong to the signed-in account, in the words the domain uses for failure.
 *
 * Two things every such write needs and no caller should be repeating. It is serialized against
 * account transitions through [AccountSession] — the rows go when the account does — and whatever
 * SQLite throws comes back out as [StorageFailure], because nothing above the data layer knows
 * SQLite and the copy for a failed disk write is not the copy for a failed request.
 *
 * Reads deliberately do not come through here. A library refresh can hold the account lock for
 * seconds, and the player must never wait on it to learn where to resume.
 */
internal suspend fun accountWrite(
    session: AccountSession,
    io: CoroutineDispatcher,
    block: suspend () -> Unit,
) = try {
    session.withAccount { withContext(io) { block() } }
} catch (cancelled: CancellationException) {
    throw cancelled
} catch (rejected: AccountSessionChanged) {
    // The account guard already speaks the domain's language.
    throw rejected
} catch (error: Exception) {
    throw StorageFailure(error)
}
