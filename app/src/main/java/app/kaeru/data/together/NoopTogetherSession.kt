package app.kaeru.data.together

import app.kaeru.domain.together.ReactionKind
import app.kaeru.domain.together.RoomLink
import app.kaeru.domain.together.SessionState
import app.kaeru.domain.together.TogetherEvent
import app.kaeru.domain.together.TogetherSessionApi
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import java.security.SecureRandom
import javax.inject.Inject
import javax.inject.Singleton

/**
 * A shared viewing with nobody on the other end.
 *
 * The screens, the share sheet and the link are all real against this; the socket is not, so
 * hosting opens a room that produces a valid link and then waits for a friend who never arrives —
 * which is, usefully, exactly the state the wait timeout and its way out are there to handle.
 *
 * It is what the app assembles with until the session engine lands, and what a build without one
 * would fall back to rather than crashing on a missing binding.
 */
@Singleton
class NoopTogetherSession @Inject constructor() : TogetherSessionApi {

    private val random = SecureRandom()
    private val _state = MutableStateFlow<SessionState>(SessionState.Idle)
    private val _events = MutableSharedFlow<TogetherEvent>()

    override val state: StateFlow<SessionState> = _state.asStateFlow()

    override val events: SharedFlow<TogetherEvent> = _events.asSharedFlow()

    override suspend fun host(name: String): RoomLink {
        val link = RoomLink.random(random)
        _state.value = SessionState.Hosting(link, waiting = true)
        return link
    }

    override suspend fun join(link: RoomLink, name: String) {
        _state.value = SessionState.Joining(link, hello = null)
    }

    override suspend fun leave() {
        _state.value = SessionState.Idle
    }

    override suspend fun sendChat(text: String) = Unit

    override suspend fun sendReaction(kind: ReactionKind) = Unit

    override suspend fun sendVoice(bytes: ByteArray, durationMs: Int) = Unit

    override fun watchAlone() {
        if (_state.value is SessionState.Hosting) return
        _state.value = SessionState.Idle
    }
}
