package app.kaeru.data.auth

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow

class InMemoryTokenStore(initial: AuthTokens? = null) : TokenStore {
    private val state = MutableStateFlow(initial)
    override val tokens: Flow<AuthTokens?> = state
    override suspend fun get(): AuthTokens? = state.value
    override suspend fun set(tokens: AuthTokens?) { state.value = tokens }
}
