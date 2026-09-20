package app.kaeru.domain.update

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import java.time.Instant

/**
 * The stored answer, set by hand.
 *
 * It exists for two kinds of test: the home screen's, which cares only whether a version is known,
 * and the view model's, which drives the check itself. [result] is what the next [check] returns;
 * [failure], when set, is what it fails with instead.
 */
class FakeUpdateRepository(
    stored: UpdateResult? = null,
    var result: UpdateResult? = stored,
    var failure: Throwable? = null,
) : UpdateRepository {

    private val stored = MutableStateFlow(stored)

    /** Every call, with the value of `force` it was made with, in order. */
    val checks = mutableListOf<Boolean>()

    override val lastResult: Flow<UpdateResult?> = this.stored

    override suspend fun check(force: Boolean): Result<UpdateResult> {
        checks += force
        failure?.let { return Result.failure(it) }
        val answer = result ?: return Result.failure(UpdateFailed(UpdateFailure.UNKNOWN))
        stored.value = answer
        return Result.success(answer)
    }

    /** Puts an answer on disk without anybody having asked for it, as a previous launch would. */
    fun remember(answer: UpdateResult?) {
        stored.value = answer
        result = answer
    }

    companion object {
        fun release(
            version: String = "0.4.0",
            sizeBytes: Long = 31_457_280,
            notes: String = "Что нового\n• быстрее",
        ) = UpdateRelease(
            version = version,
            publishedAt = Instant.parse("2026-09-16T08:00:00Z"),
            notes = notes,
            apkUrl = "https://example.test/Kaeru-$version.apk",
            apkName = "Kaeru-$version.apk",
            sizeBytes = sizeBytes,
        )

        fun found(
            release: UpdateRelease? = release(),
            at: Instant = Instant.parse("2026-09-16T09:00:00Z"),
            installed: String = "0.3.0",
        ) = UpdateResult(checkedAt = at, installedVersion = installed, release = release)
    }
}
