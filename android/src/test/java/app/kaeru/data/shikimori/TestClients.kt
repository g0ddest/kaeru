package app.kaeru.data.shikimori

import app.kaeru.data.auth.InMemoryTokenStore
import app.kaeru.data.auth.TokenStore
import app.kaeru.shared.data.network.HttpTransport
import app.kaeru.shared.data.shikimori.ShikimoriClient
import app.kaeru.shared.data.shikimori.ShikimoriRateLimiter
import app.kaeru.shared.data.shikimori.TokenResponseDto
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.http.HttpHeaders
import io.ktor.http.headersOf
import kotlinx.serialization.json.Json
import okhttp3.mockwebserver.MockWebServer
import java.time.Clock

/**
 * The shared client pointed at a [MockWebServer], through the real OkHttp engine: what a test
 * about sockets — a dropped connection, a dead host — has to go through to mean anything.
 *
 * Every client gets a limiter of its own rather than the process-wide one, so a hundred tests
 * do not queue behind each other's five-a-second.
 */
fun serverClient(
    server: MockWebServer,
    clientId: String = "cid",
    proxy: String = server.url("/").toString(),
): ShikimoriClient = ShikimoriClient(
    HttpTransport(HttpClient(OkHttp)), clientId, proxy, ShikimoriRateLimiter(),
    base = server.url("/").toString().removeSuffix("/"),
)

/** [serverClient] with a session on it. */
fun serverApi(
    server: MockWebServer,
    clock: Clock = Clock.systemUTC(),
    store: TokenStore = InMemoryTokenStore(),
): ShikimoriApi = SessionShikimoriApi(serverClient(server), store, clock)

/** A client whose token endpoint answers with whatever [exchange] produces — or throws. */
fun oauthClient(exchange: suspend () -> TokenResponseDto): ShikimoriClient = ShikimoriClient(
    HttpTransport(HttpClient(MockEngine {
        respond(
            Json.encodeToString(TokenResponseDto.serializer(), exchange()),
            headers = headersOf(HttpHeaders.ContentType, "application/json"),
        )
    })),
    "cid", "https://proxy.example", ShikimoriRateLimiter(),
)
