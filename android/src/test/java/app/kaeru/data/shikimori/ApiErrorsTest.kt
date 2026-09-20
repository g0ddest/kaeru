package app.kaeru.data.shikimori

import app.kaeru.domain.error.HttpError
import app.kaeru.domain.error.NetworkUnavailable
import app.kaeru.shared.ApiException
import app.kaeru.shared.data.network.NetworkException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class ApiErrorsTest {
    @Test
    fun `http failures carry their status code into the domain`() {
        val mapped = ApiException(429).toDomainFailure()
        assertTrue(mapped is HttpError)
        assertEquals(429, (mapped as HttpError).code)
    }

    @Test
    fun `network failures become a network failure that keeps its cause`() {
        val cause = NetworkException()
        val mapped = cause.toDomainFailure()
        assertTrue(mapped is NetworkUnavailable)
        assertSame(cause, mapped.cause)
    }

    @Test
    fun `other failures pass through unchanged`() {
        val original = IllegalStateException("No anime 100 returned by Shikimori")
        assertSame(original, original.toDomainFailure())
    }
}
