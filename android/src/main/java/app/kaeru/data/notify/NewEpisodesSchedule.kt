package app.kaeru.data.notify

/**
 * The background check, as something that can be on or off.
 *
 * Behind an interface so the rule about *when* it should be on — signed in, not a television, and
 * (once there is one) the setting — can be tested without WorkManager. The implementation is three
 * lines of `WorkManager`; the rule around it is the part that can be wrong.
 */
interface NewEpisodesSchedule {
    /** Idempotent: asking for a check that is already scheduled leaves the existing one alone. */
    fun enable()

    fun disable()
}
