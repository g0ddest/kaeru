package app.kaeru.data.shikimori

import app.kaeru.domain.error.HttpError
import app.kaeru.domain.error.NetworkUnavailable
import retrofit2.HttpException
import java.io.IOException

/**
 * Translates transport exceptions into domain failures so a repository never hands an OkHttp or
 * Retrofit type to the UI. Anything else passes through unchanged.
 */
internal fun Throwable.toDomainFailure(): Throwable = when (this) {
    is HttpException -> HttpError(code(), this)
    is IOException -> NetworkUnavailable(this)
    else -> this
}
