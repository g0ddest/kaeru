package app.kaeru.data.shikimori

import app.kaeru.data.auth.TokenStore
import kotlinx.coroutines.runBlocking
import okhttp3.Interceptor
import okhttp3.Response

internal object ExplicitAuthorization

class AuthInterceptor(private val store: TokenStore) : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()
        if (request.url.encodedPath.startsWith("/oauth/")) return chain.proceed(request)
        if (request.header("Authorization") != null) {
            // A candidate identity must never fall back to the current account after a 401.
            return chain.proceed(request.newBuilder()
                .tag(ExplicitAuthorization::class.java, ExplicitAuthorization).build())
        }
        val token = runBlocking { store.get() }?.accessToken ?: return chain.proceed(request)
        return chain.proceed(request.newBuilder().header("Authorization", "Bearer $token").build())
    }
}
