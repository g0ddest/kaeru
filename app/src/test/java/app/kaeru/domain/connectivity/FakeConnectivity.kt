package app.kaeru.domain.connectivity

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow

/** A network the test switches on and off, which is the whole of what the app knows about one. */
class FakeConnectivity(online: Boolean = true) : Connectivity {
    val state = MutableStateFlow(online)
    override val online: Flow<Boolean> = state

    fun goOffline() {
        state.value = false
    }

    fun goOnline() {
        state.value = true
    }
}
