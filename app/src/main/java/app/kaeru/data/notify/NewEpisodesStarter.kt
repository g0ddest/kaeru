package app.kaeru.data.notify

import android.util.Log
import app.kaeru.domain.repository.AuthRepository
import app.kaeru.domain.settings.SettingsStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicBoolean
import javax.inject.Inject
import javax.inject.Singleton

private const val TAG = "NewEpisodes"

/**
 * Keeps the six-hourly check on for as long as it should be, and off the rest of the time.
 *
 * Started once from the application, beside the outbox drain, because the answer changes without
 * anybody being on a screen: signing out from settings has to take the work off, turning the
 * switch off has to take it off too, and the check has to be there again the moment either comes
 * back.
 *
 * A television gets nothing. Notifications there are a banner over whatever is playing, for an
 * episode nobody is going to start from the remote in their hand — and refusing to schedule the
 * work at all is a cheaper guarantee than refusing to publish at the other end: there is then
 * nothing to publish.
 */
@Singleton
class NewEpisodesStarter @Inject constructor(
    private val auth: AuthRepository,
    private val settings: SettingsStore,
    private val schedule: NewEpisodesSchedule,
    private val television: Television,
) {
    private val started = AtomicBoolean(false)

    fun start(scope: CoroutineScope) {
        if (!started.compareAndSet(false, true)) return
        if (television.isTelevision()) {
            // Said once rather than watched: a build that used to be a phone is not a case, but a
            // television that once ran an older build might still be holding the work.
            schedule.disable()
            return
        }
        scope.launch {
            // Two conditions and one answer: there has to be a list to check, and the viewer has
            // to want to hear about it. Combined rather than watched separately, or the two
            // watchers would each undo the other's decision on every change.
            combine(auth.isLoggedIn, settings.newEpisodeNotifications) { loggedIn, wanted -> loggedIn && wanted }
                .distinctUntilChanged()
                // Nothing here is worth taking the process down for. A watch that throws leaves
                // the schedule exactly as it was, which is the state it was last told to be in.
                .catch { error -> Log.w(TAG, "Stopped watching whether to check for new episodes", error) }
                .collect { wanted -> if (wanted) schedule.enable() else schedule.disable() }
        }
    }
}
