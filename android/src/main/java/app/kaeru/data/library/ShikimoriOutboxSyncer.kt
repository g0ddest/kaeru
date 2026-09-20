package app.kaeru.data.library

import android.util.Log
import androidx.room.withTransaction
import app.kaeru.data.local.KaeruDatabase
import app.kaeru.data.local.RateOutboxDao
import app.kaeru.data.local.RateOutboxEntity
import app.kaeru.data.local.UserRateDao
import app.kaeru.data.local.UserRateEntity
import app.kaeru.data.shikimori.ShikimoriApi
import app.kaeru.data.shikimori.toDomainFailure
import app.kaeru.domain.model.ListStatus
import app.kaeru.domain.sync.OutboxReplayPlan
import app.kaeru.domain.sync.OutboxSyncer
import app.kaeru.domain.sync.RateOp
import app.kaeru.domain.sync.RateOpKind
import app.kaeru.domain.sync.ReplayOutcome
import app.kaeru.shared.ApiException
import app.kaeru.shared.data.network.NetworkException
import app.kaeru.shared.data.shikimori.UserRateDto
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.time.Clock
import javax.inject.Inject
import javax.inject.Singleton

private const val TAG = "OutboxSyncer"

/** SQLite binds at most 999 variables to one statement, and a superseded run has no bound. */
private const val DELETE_CHUNK = 500

/** Shikimori answers 4xx when it has looked at the write and refused it; 5xx means ask again later. */
private val ApiException.isRejection: Boolean get() = status in 400..499

/**
 * Sends the writes a viewer made without a network, in the order they made them.
 *
 * One request at a time, on purpose: Shikimori allows five a second, and a queue built on a flight
 * can be long. A drain stops at the first sign the network is gone again and leaves everything
 * after it queued, so the order the viewer acted in survives however many attempts it takes.
 *
 * Nothing here takes the account lock, and nothing here calls back into anything that does. A
 * refresh replays from inside that lock, which is not reentrant, so a drain that reached back
 * through `LibraryRepository` would suspend forever and take every later account write with it.
 * What the drain cannot do itself it reports in [ReplayOutcome] for the caller to do outside.
 *
 * The account is read rather than held: it is re-read before every op, and a drain stops the
 * moment it changes, so a queue cannot be sent as somebody else. A logout empties the queue in the
 * same transaction as the rest of the account's rows, so there is usually nothing left to stop.
 */
@Singleton
class ShikimoriOutboxSyncer @Inject constructor(
    private val api: ShikimoriApi,
    private val db: KaeruDatabase,
    private val userRateDao: UserRateDao,
    private val outboxDao: RateOutboxDao,
    private val prefs: AppPreferences,
    private val clock: Clock,
) : OutboxSyncer {
    private val draining = Mutex()

    override suspend fun replay(): Result<ReplayOutcome> = draining.withLock {
        try {
            Result.success(drain())
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            Result.failure(error.toDomainFailure())
        }
    }

    private suspend fun drain(): ReplayOutcome {
        val rows = outboxDao.getAll()
        // A row this build cannot read is dropped rather than skipped: skipping would leave its
        // anime pending forever, and a refresh never takes the server's rate for a pending anime.
        val (readable, unreadable) = rows.partition { it.toDomainOrNull() != null }
        if (unreadable.isNotEmpty()) {
            Log.w(TAG, "Dropping ${unreadable.size} queued writes of an unknown kind")
            delete(unreadable.map { it.id })
        }
        val plan = OutboxReplayPlan.of(readable.mapNotNull(RateOutboxEntity::toDomainOrNull))
        // A run of taps on one episode counter is one intent; only the last of them is worth a
        // request, and the rest go before anything is sent so a failed drain does not keep them.
        delete(plan.superseded.toList())
        val refused = linkedSetOf<Int>()
        if (plan.toSend.isEmpty()) return ReplayOutcome(sent = 0, refused = refused)
        // No account, no queue to speak for: whatever is here belongs to a sign-in that has not
        // finished, and it waits rather than being sent as somebody else.
        val account = prefs.userId() ?: return ReplayOutcome(sent = 0, refused = refused)

        var sent = 0
        for ((index, op) in plan.toSend.withIndex()) {
            // Re-read rather than trusted: a logout partway through a long queue must not send the
            // rest as whoever signs in next. What is left keeps its place, and a logout has
            // usually deleted it already.
            if (prefs.userId() != account) break
            val local = userRateDao.getByAnimeId(op.animeId)
            if (op.kind == RateOpKind.EPISODES && local.hasNoServerRate) {
                // Nothing to PATCH and nothing that can create one — the status write that would
                // have is either not queued or was itself refused. Dropped rather than retried:
                // one unsendable row must never block the rows behind it.
                Log.w(TAG, "Dropping an episode count for anime ${op.animeId}: Shikimori has no rate for it")
                outboxDao.deleteById(op.id)
                refused += op.animeId
                continue
            }
            val dto = try {
                send(op, local, account)
            } catch (offline: NetworkException) {
                // Gone again. Everything from here on keeps its place.
                break
            } catch (http: ApiException) {
                if (!http.isRejection) break
                // Shikimori read the write and said no. Keeping it would mean sending it forever,
                // so it goes, and the caller re-reads the title to show what the server does hold.
                outboxDao.deleteById(op.id)
                refused += op.animeId
                continue
            }
            // The half this write did not speak for is only the server's to set if nothing else
            // is queued for it: otherwise the answer carries a value the viewer has already
            // changed, and taking it would undo a mark that is still on its way out.
            val queuedAfter = plan.toSend.drop(index + 1).filter { it.animeId == op.animeId }.map { it.kind }.toSet()
            db.withTransaction {
                outboxDao.deleteById(op.id)
                // A rate created here arrives with the server's own id, and the placeholder the
                // offline write left behind holds the anime's unique index until it is gone.
                if (local.hasNoServerRate) userRateDao.deleteByAnimeId(op.animeId)
                userRateDao.upsertAll(listOf(merge(local, dto, op, queuedAfter)))
            }
            sent++
        }
        return ReplayOutcome(sent, refused)
    }

    private suspend fun delete(ids: List<Long>) =
        ids.chunked(DELETE_CHUNK).forEach { outboxDao.deleteByIds(it) }

    private suspend fun send(op: RateOp, local: UserRateEntity?, userId: Long): UserRateDto = when (op.kind) {
        RateOpKind.STATUS ->
            if (local.hasNoServerRate) api.createUserRate(userId, op.animeId, op.value)
            else api.updateUserRate(requireNotNull(local).id, status = op.value)

        RateOpKind.EPISODES -> api.updateUserRate(requireNotNull(local).id, episodes = op.value.toInt())
    }

    private fun merge(
        local: UserRateEntity?,
        dto: UserRateDto,
        op: RateOp,
        queuedAfter: Set<RateOpKind>,
    ) = UserRateEntity(
        id = dto.id,
        animeId = op.animeId,
        status = if (RateOpKind.STATUS in queuedAfter && local != null) local.status else ListStatus.fromApi(dto.status),
        episodes = if (RateOpKind.EPISODES in queuedAfter && local != null) local.episodes else dto.episodes,
        // The moment the viewer acted, not the moment the queue happened to drain. The home rows
        // are ordered by this, and a flight's worth of marks landing at once would otherwise
        // reshuffle the screen to the order the network came back in. The later of the two,
        // because a write still queued for the other half was made after this one.
        updatedAt = maxOf(op.createdAt, local?.updatedAt ?: op.createdAt),
    )
}

/** A missing row, or the negative-id placeholder an offline status change leaves: nothing to PATCH. */
private val UserRateEntity?.hasNoServerRate: Boolean get() = this == null || id < 0
