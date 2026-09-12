package app.kaeru.data.auth

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update

class InMemoryTokenStore(initial: AuthTokens? = null) : TokenStore {
    private val state = MutableStateFlow(TokenSnapshot(initial, 0))
    override val tokens: Flow<AuthTokens?> = state.map { it.tokens }.distinctUntilChanged()
    override suspend fun get(): AuthTokens? = state.value.tokens
    override suspend fun set(tokens: AuthTokens?) {
        state.update { TokenSnapshot(tokens, it.revision + 1) }
    }
    override suspend fun snapshot(): TokenSnapshot = state.value
    override suspend fun compareAndSet(expected: TokenSnapshot, tokens: AuthTokens?): Boolean =
        state.compareAndSet(expected, TokenSnapshot(tokens, expected.revision + 1))
}
