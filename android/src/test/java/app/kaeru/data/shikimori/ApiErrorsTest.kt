package app.kaeru.data.shikimori

import app.kaeru.domain.error.HttpError
import app.kaeru.domain.error.NetworkUnavailable
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import retrofit2.HttpException
import java.io.IOException
import java.net.SocketTimeoutException

class ApiErrorsTest {
    @Test
    fun `http failures carry their status code into the domain`() {
        val mapped = httpException(429).toDomainFailure()
        assertTrue(mapped is HttpError)
        assertEquals(429, (mapped as HttpError).code)
    }

    @Test
    fun `io failures become a network failure that keeps its cause`() {
        val cause = SocketTimeoutException("timeout")
        val mapped = cause.toDomainFailure()
        assertTrue(mapped is NetworkUnavailable)
        assertSame(cause, mapped.cause)
        assertTrue(IOException("closed").toDomainFailure() is NetworkUnavailable)
    }

    @Test
    fun `other failures pass through unchanged`() {
        val original = IllegalStateException("No anime 100 returned by Shikimori")
        assertSame(original, original.toDomainFailure())
    }

    private fun httpException(code: Int): HttpException {
        val request = Request.Builder().url("https://shikimori.io/api/animes").build()
        val raw = Response.Builder()
            .request(request).protocol(Protocol.HTTP_1_1).code(code).message("error").build()
        return HttpException(retrofit2.Response.error<Unit>("".toResponseBody("application/json".toMediaType()), raw))
    }
}
