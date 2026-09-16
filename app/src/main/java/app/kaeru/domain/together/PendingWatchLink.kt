package app.kaeru.domain.together

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.getAndUpdate
import javax.inject.Inject
import javax.inject.Singleton

/**
 * An invitation that has arrived but has nowhere to go yet.
 *
 * The case this exists for is the ordinary one, not an edge: the landing page tells a person to
 * install the app and open the link again, and an app that has just been installed is signed out.
 * The link would otherwise be read by the activity, handed to a shell that is showing the login
 * screen, and thrown away — leaving the room joined, the thirty-second clock running against
 * nobody, and no way back to the invitation once the person signs in.
 *
 * So it is held here, for the life of the process rather than of an activity, and taken only when
 * there is a signed-in shell to put it on screen. [take] is the whole of the consumption: reading
 * it clears it, so a rotation cannot open the same invitation twice.
 *
 * In `domain` because that is where a plain holder of a plain string belongs and because the screen
 * that reads it lives in `ui.common`, which depends on `domain` and nothing below it.
 */
@Singleton
class PendingWatchLink @Inject constructor() {

    private val _link = MutableStateFlow<String?>(null)

    /** Whether something is waiting, for a login screen that should say so. */
    val link: StateFlow<String?> = _link.asStateFlow()

    fun offer(uri: String) {
        _link.value = uri
    }

    /** Hands the invitation over exactly once. */
    fun take(): String? = _link.getAndUpdate { null }
}
