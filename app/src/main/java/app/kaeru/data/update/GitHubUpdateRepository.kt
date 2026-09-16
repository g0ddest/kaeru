package app.kaeru.data.update

import app.kaeru.domain.update.UpdateFailed
import app.kaeru.domain.update.UpdateFailure
import app.kaeru.domain.update.UpdatePolicy
import app.kaeru.domain.update.UpdateRepository
import app.kaeru.domain.update.UpdateResult
import app.kaeru.domain.update.isNewerVersion
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import retrofit2.HttpException
import java.io.IOException
import java.time.Clock
import java.time.Instant
import javax.inject.Inject
import javax.inject.Named
import javax.inject.Singleton

/** GitHub says how much of the hourly budget is left in this header, and zero is the refusal. */
private const val RATE_LIMIT_REMAINING = "X-RateLimit-Remaining"

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

    override val lastResult: Flow<UpdateResult?> = prefs.lastResult

    override suspend fun check(force: Boolean): Result<UpdateResult> {
        val now = clock.instant()
        val stored = prefs.last()
        // The stored answer is only an answer while it is about this build. An app updated since
        // the last check would otherwise go on offering the version it is already running.
        val usable = stored?.takeIf { it.installedVersion == installedVersion }
        if (!force && usable != null && !policy.due(usable.checkedAt, now)) return Result.success(usable)

        return try {
            val result = resultOf(api.releases(), now)
            prefs.save(result)
            Result.success(result)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            Result.failure(error.toUpdateFailure())
        }
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
    403 -> response()?.headers()?.get(RATE_LIMIT_REMAINING) == "0"
    else -> false
}
