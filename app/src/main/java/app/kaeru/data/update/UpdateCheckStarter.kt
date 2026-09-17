package app.kaeru.data.update

import android.util.Log
import app.kaeru.domain.update.UpdateRepository
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton

private const val TAG = "KaeruUpdates"

/**
 * The quiet check: once per launch, and at most once a day.
 *
 * It is deliberately the smallest thing it could be. Nothing waits for it, nothing is shown while
 * it runs, and a failure is not reported anywhere — the result is written down and the home screen
 * picks it up from there whenever it lands, which may be after the screen is already on. An app
 * that blocked its first frame on a request to GitHub would be an app that starts slowly on the
 * one network where it matters least.
 *
 * The day-long throttle lives in the repository rather than here, so the viewer's own «Проверить»
 * goes past it and this does not.
 */
@Singleton
class UpdateCheckStarter @Inject constructor(private val repository: UpdateRepository) {

    /**
     * The handler is not defensive dressing, it is the whole safety of this class.
     *
     * This launches on the application scope, during `Application.onCreate`, with nothing in front
     * of the viewer and nothing to retry it. That scope is a `SupervisorJob` with no handler of its
     * own, so anything that escaped here would reach the thread's default handler and take the
     * process down at launch — over a background question about whether a newer APK exists. The
     * repository already returns failures rather than throwing them; this is what guarantees it
     * even if that stops being true.
     */
    private val quiet = CoroutineExceptionHandler { _, failure ->
        Log.w(TAG, "The launch-time update check failed", failure)
    }

    fun start(scope: CoroutineScope) {
        scope.launch(quiet) { repository.check(force = false) }
    }
}
