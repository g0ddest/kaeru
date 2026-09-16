package app.kaeru.data.update

import app.kaeru.domain.update.UpdateRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton

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

    fun start(scope: CoroutineScope) {
        scope.launch { repository.check(force = false) }
    }
}
