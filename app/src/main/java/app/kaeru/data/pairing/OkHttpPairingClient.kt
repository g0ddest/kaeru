package app.kaeru.data.pairing

import app.kaeru.di.IoDispatcher
import app.kaeru.di.PairingHttp
import app.kaeru.domain.error.PairingFailed
import app.kaeru.domain.error.PairingFailureReason
import app.kaeru.domain.pairing.PairingClient
import app.kaeru.domain.pairing.PairingRequest
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import okhttp3.HttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton

/**
 * One POST, to a television on the same Wi-Fi, carrying a code that is worthless a second later.
 *
 * The address is checked here as well as in [PairingRequest.parse], because this is the layer that
 * actually opens a socket and it should not depend on somebody upstream having been careful. The
 * request goes out in the clear: there is no certificate a television could present for an address
 * a router made up this morning, and what travels is a single-use authorization code on a link that
 * never leaves the building.
 */
@Singleton
class OkHttpPairingClient @Inject constructor(
    @param:PairingHttp private val client: OkHttpClient,
    private val json: Json,
    @param:IoDispatcher private val dispatcher: CoroutineDispatcher,
) : PairingClient {

    override suspend fun send(request: PairingRequest, code: String, redirectUri: String): Result<Unit> {
        if (!PairingRequest.isLanAddress(request.host)) {
            return Result.failure(PairingFailed(PairingFailureReason.BAD_LINK))
        }
        val body = json.encodeToString(PairingPayload(request.nonce, code, redirectUri))
        val call = client.newCall(
            Request.Builder().url(pairUrl(request)).post(body.toRequestBody(JSON)).build(),
        )
        return withContext(dispatcher) {
            try {
                call.execute().use { response ->
                    if (response.isSuccessful) {
                        Result.success(Unit)
                    } else {
                        Result.failure(PairingFailed(PairingFailureReason.REFUSED))
                    }
                }
            } catch (error: IOException) {
                Result.failure(PairingFailed(PairingFailureReason.UNREACHABLE, error))
            }
        }
    }

    internal fun pairUrl(request: PairingRequest): HttpUrl = HttpUrl.Builder()
        .scheme("http")
        .host(request.host)
        .port(request.port)
        .addPathSegment("pair")
        .build()

    private companion object {
        val JSON = "application/json; charset=utf-8".toMediaType()
    }
}
