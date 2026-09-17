package app.kaeru.data.update

import app.kaeru.domain.update.UpdateFailed
import app.kaeru.domain.update.UpdateFailure
import app.kaeru.domain.update.UpdatePolicy
import app.kaeru.domain.update.UpdateRepository
import app.kaeru.domain.update.UpdateResult
import app.kaeru.domain.update.isNewerVersion
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import retrofit2.HttpException
import java.io.IOException
import java.time.Clock
import java.time.Instant
import javax.inject.Inject
import javax.inject.Named
import javax.inject.Singleton

/** GitHub says how much of the hourly budget is left in this header, and zero is the refusal. */
private const val RATE_LIMIT_REMAINING = "X-RateLimit-Remaining"

/** How the secondary limit says the same thing: come back in this many seconds. */
private const val RETRY_AFTER = "Retry-After"

/**
 * What GitHub says about releases of this app, and what this device remembers of the answer.
 *
 * The rule the whole screen rests on is here and nowhere else: a release is offered when its tag
 * is a greater version than the one this build was compiled with. The installed version is
 * injected rather than read from `BuildConfig` in place, so the rule can be tested against any
 * pair of versions instead of against whatever the tree happens to be at.
 */
@Singleton
class GitHubUpdateRepository @Inject constructor(
    private val api: GitHubReleasesApi,
    private val prefs: UpdatePreferences,
    private val policy: UpdatePolicy,
    private val clock: Clock,
    @param:Named("versionName") private val installedVersion: String,
) : UpdateRepository {

    /**
     * The stored answer, re-read against the build that is actually running.
     *
     * This is the update installing itself out of existence. A record written by 0.3.0 offering
     * 0.4.0 stays on disk through the install, and the new process reads it before it has had time
     * to ask GitHub anything — so without this the home screen says «Доступна версия 0.4.0» while
     * running 0.4.0, and goes on saying it for as long as the next check keeps failing.
     *
     * The comparison is the one the whole feature rests on, applied a second time at the point of
     * reading rather than only at the point of writing. What survives is the check's date, which
     * is still true: the app did ask, on that day, and the answer just stopped being an offer.
     */
    override val lastResult: Flow<UpdateResult?> = prefs.lastResult.map { stored ->
        stored?.let { result ->
            result.copy(release = result.release?.takeIf { isNewerVersion(it.version, installedVersion) })
        }
    }

    /**
     * Everything is inside the `try`, including the read from disk.
     *
     * A method that hands back a `Result` is a method nobody guards, and the one caller that runs
     * without a screen in front of it is the launch-time check on the application scope. DataStore
     * throws on a file it cannot read or parse, and the read used to sit outside here — so a
     * corrupt preferences file was an uncaught exception during `Application.onCreate`, which is a
     * boot loop rather than a missed update.
     */
    override suspend fun check(force: Boolean): Result<UpdateResult> = try {
        val now = clock.instant()
        // Read through `lastResult`, so the one filter above governs both readers. The record
        // keeps the version it was written by, which is what the throttle below is asking about:
        // an app updated since the last check has to go and ask again rather than sit out the day
        // on an answer about the build it replaced.
        val usable = lastResult.first()?.takeIf { it.installedVersion == installedVersion }
        if (!force && usable != null && !policy.due(usable.checkedAt, now)) {
            Result.success(usable)
        } else {
            val result = resultOf(api.releases(), now)
            prefs.save(result)
            Result.success(result)
        }
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (error: Exception) {
        Result.failure(error.toUpdateFailure())
    }

    /**
     * The answer, given what GitHub listed.
     *
     * Three outcomes and no fourth: there is nothing newer, there is something newer with a file
     * on it, or there is something newer with nothing attached. The last one is a failure rather
     * than a silent «up to date», because the alternative is a viewer told they have the latest
     * version while the release notes on GitHub say otherwise.
     */
    private fun resultOf(releases: List<GitHubReleaseDto>, now: Instant): UpdateResult {
        val newest = newestRelease(releases)
        val upToDate = UpdateResult(checkedAt = now, installedVersion = installedVersion, release = null)
        if (newest == null || !isNewerVersion(newest.tagName, installedVersion)) return upToDate
        val release = newest.toUpdateRelease() ?: throw UpdateFailed(UpdateFailure.NO_ASSET)
        return UpdateResult(checkedAt = now, installedVersion = installedVersion, release = release)
    }
}

/**
 * A transport failure as the reason the screen names.
 *
 * The rate limit is the one worth telling apart: GitHub answers it with a `403`, which on every
 * other endpoint means «signed out» — so the header is what separates «wait an hour» from a real
 * refusal. A `429` is the same thing said the modern way, and is read the same.
 */
internal fun Throwable.toUpdateFailure(): Throwable = when {
    this is UpdateFailed -> this
    this is HttpException && rateLimited() -> UpdateFailed(UpdateFailure.RATE_LIMITED, this)
    this is IOException -> UpdateFailed(UpdateFailure.NO_NETWORK, this)
    else -> UpdateFailed(UpdateFailure.UNKNOWN, this)
}

private fun HttpException.rateLimited(): Boolean = when (code()) {
    429 -> true
    // Two different limits answer with a 403. The hourly budget spends itself down to a
    // `X-RateLimit-Remaining` of zero; the secondary limit, which is about asking too fast rather
    // than too often, leaves that count alone and sends `Retry-After` instead. Both mean the same
    // thing to a viewer — wait — so both are read the same way.
    403 -> {
        val headers = response()?.headers()
        headers?.get(RATE_LIMIT_REMAINING) == "0" || headers?.get(RETRY_AFTER) != null
    }
    else -> false
}
