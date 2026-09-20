package app.kaeru.data.shikimori

import app.kaeru.domain.error.HttpError
import app.kaeru.domain.error.NetworkUnavailable
import app.kaeru.shared.ApiException
import app.kaeru.shared.data.network.NetworkException

/**
 * Translates the shared module's failures into domain ones so a repository never hands a
 * transport type to the UI. Anything else passes through unchanged.
 */
internal fun Throwable.toDomainFailure(): Throwable = when (this) {
    is ApiException -> HttpError(status, this)
    is NetworkException -> NetworkUnavailable(this)
    else -> this
}
