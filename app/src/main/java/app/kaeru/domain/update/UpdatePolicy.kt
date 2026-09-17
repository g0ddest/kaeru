package app.kaeru.domain.update

import java.time.Duration
import java.time.Instant

/** A day, which is how often an app that ships a release a week is worth asking about. */
private val DEFAULT_INTERVAL: Duration = Duration.ofHours(24)

/**
 * When the quiet check is allowed to run.
 *
 * The check itself is a few kilobytes, so the interval is not about bandwidth — it is about the
 * rate limit. GitHub gives an unauthenticated address sixty requests an hour, and that address is
 * shared by everyone behind one router; an app that asked on every launch would spend somebody
 * else's budget as well as its own. Once a day is more than often enough for a release schedule
 * measured in weeks.
 *
 * The viewer's own «Проверить» never comes through here. A press is a question the app has been
 * asked directly, and answering it with a day-old cached result would be the button doing nothing.
 */
class UpdatePolicy(private val interval: Duration = DEFAULT_INTERVAL) {

    /**
     * Whether a check is owed, given when the last one finished.
     *
     * A [lastCheckedAt] in the future is due rather than never: the only way to get one is a clock
     * that was wrong and has since been corrected, and a device that fell into that hole would
     * otherwise stop checking for as long as the wrong reading stayed in the future.
     */
    fun due(lastCheckedAt: Instant?, now: Instant): Boolean = when {
        lastCheckedAt == null -> true
        now.isBefore(lastCheckedAt) -> true
        else -> !now.isBefore(lastCheckedAt.plus(interval))
    }
}
