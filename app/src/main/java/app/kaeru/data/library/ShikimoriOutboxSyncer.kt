package app.kaeru.data.library

import app.kaeru.data.local.RateOutboxDao
import app.kaeru.data.local.UserRateDao
import app.kaeru.data.local.UserRateEntity
import app.kaeru.data.shikimori.ShikimoriApi
import app.kaeru.data.shikimori.UserRateDto
import app.kaeru.data.shikimori.UserRatePayload
import app.kaeru.data.shikimori.UserRateRequest
import app.kaeru.data.shikimori.toDomainFailure
import app.kaeru.domain.model.ListStatus
import app.kaeru.domain.repository.LibraryRepository
import app.kaeru.domain.sync.OutboxReplayPlan
import app.kaeru.domain.sync.OutboxSyncer
import app.kaeru.domain.sync.RateOp
import app.kaeru.domain.sync.RateOpKind
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import retrofit2.HttpException
import java.io.IOException
import java.time.Clock
import javax.inject.Inject
import javax.inject.Provider
import javax.inject.Singleton

/** Shikimori answers 4xx when it has looked at the write and refused it; 5xx means ask again later. */
private val HttpException.isRejection: Boolean get() = code() in 400..499

/**
 * Sends the writes a viewer made without a network, in the order they made them.
 *
 * One request at a time, on purpose: Shikimori allows five a second, and a queue built on a flight
 * can be long. A drain stops at the first sign the network is gone again and leaves everything
 * after it queued, so the order the viewer acted in survives however many attempts it takes.
 *
 * No account lock is taken here, and none can be: a refresh calls this from inside the lock it
 * already holds. The account is read rather than held, and the queue is emptied with the rest of
 * the account's rows on the way out, so a replay cannot outlive the account that filled it.
 */
@Singleton
class ShikimoriOutboxSyncer @Inject constructor(
    private val api: ShikimoriApi,
    private val userRateDao: UserRateDao,
    private val outboxDao: RateOutboxDao,
    private val prefs: AppPreferences,
    // Lazily: the library repository asks for a replay at the start of every refresh, and a
    // rejected write asks it back for the server's version of the title.
    private val library: Provider<LibraryRepository>,
    private val clock: Clock,
) : OutboxSyncer {
    private val draining = Mutex()

    override suspend fun replay(): Result<Int> = draining.withLock {
        try {
            Result.success(drain())
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            Result.failure(error.toDomainFailure())
        }
    }

    private suspend fun drain(): Int {
        val plan = OutboxReplayPlan.of(outboxDao.getAll().map { it.toDomain() })
        // A run of taps on one episode counter is one intent; only the last of them is worth a
        // request, and the rest go before anything is sent so a failed drain does not keep them.
        if (plan.superseded.isNotEmpty()) outboxDao.deleteByIds(plan.superseded.toList())
        if (plan.toSend.isEmpty()) return 0
        // No account, no queue to speak for: whatever is here belongs to a sign-in that has not
        // finished, and it waits rather than being sent as somebody else.
        val userId = prefs.userId() ?: return 0

        var sent = 0
        val refused = linkedSetOf<Int>()
        for ((index, op) in plan.toSend.withIndex()) {
            val local = userRateDao.getByAnimeId(op.animeId)
            val dto = try {
                send(op, local, userId)
            } catch (offline: IOException) {
                // Gone again. Everything from here on keeps its place.
                break
            } catch (http: HttpException) {
                if (!http.isRejection) break
                // Shikimori read the write and said no. Keeping it would mean sending it forever,
                // so it goes, and the title is re-read to show whatever the server does hold.
                outboxDao.deleteById(op.id)
                refused += op.animeId
                continue
            }
            outboxDao.deleteById(op.id)
            // The half this write did not speak for is only the server's to set if nothing else
            // is queued for it: otherwise the answer carries a value the viewer has already
            // changed, and taking it would undo a mark that is still on its way out.
            val queuedAfter = plan.toSend.drop(index + 1).filter { it.animeId == op.animeId }.map { it.kind }.toSet()
            userRateDao.apply {
                // A rate created here arrives with the server's own id, and the placeholder the
                // offline write left behind holds the anime's unique index until it is gone.
                if (local == null || local.id < 0) deleteByAnimeId(op.animeId)
                upsertAll(listOf(merge(local, dto, op.animeId, queuedAfter)))
            }
            sent++
        }
        // After the loop rather than inside it: the server's version of a title is worth one
        // request, however many of its writes it just refused.
        refused.forEach { library.get().refreshAnime(it) }
        return sent
    }

    private suspend fun send(op: RateOp, local: UserRateEntity?, userId: Long): UserRateDto = when (op.kind) {
        RateOpKind.STATUS ->
            if (local == null || local.id < 0) {
                api.createUserRate(UserRateRequest(UserRatePayload(
                    userId = userId, targetId = op.animeId, targetType = "Anime", status = op.value,
                )))
            } else {
                api.updateUserRate(local.id, UserRateRequest(UserRatePayload(status = op.value)))
            }

        RateOpKind.EPISODES -> {
            val rateId = local?.id?.takeIf { it >= 0 }
                ?: error("No Shikimori rate for anime ${op.animeId} to set episodes on")
            api.updateUserRate(rateId, UserRateRequest(UserRatePayload(episodes = op.value.toInt())))
        }
    }

    private fun merge(
        local: UserRateEntity?,
        dto: UserRateDto,
        animeId: Int,
        queuedAfter: Set<RateOpKind>,
    ) = UserRateEntity(
        id = dto.id,
        animeId = animeId,
        status = if (RateOpKind.STATUS in queuedAfter && local != null) local.status else ListStatus.fromApi(dto.status),
        episodes = if (RateOpKind.EPISODES in queuedAfter && local != null) local.episodes else dto.episodes,
        updatedAt = clock.instant(),
    )
}
