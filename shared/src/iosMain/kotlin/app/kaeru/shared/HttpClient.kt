package app.kaeru.shared.data.network

import io.ktor.client.HttpClient
import io.ktor.client.engine.darwin.Darwin

internal actual fun platformHttpClient(): HttpClient = HttpClient(Darwin)
