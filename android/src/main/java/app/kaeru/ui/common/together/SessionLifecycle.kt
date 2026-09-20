package app.kaeru.ui.common.together

/**
 * When a shared viewing has outlived the screen it belonged to.
 *
 * A session is one per process and runs on a scope that never ends, so nothing stops it by itself:
 * left alone it keeps pinging, reporting and applying a friend's episode changes to a player
 * nobody is looking at, and holds the room until the relay's own idle alarm hours later. The
 * question is only ever «has the viewer finished with the player», and that has exactly one honest
 * answer on Android.
 */
object SessionLifecycle {

    /**
     * Whether the player going away is the viewer leaving the shared viewing for good.
     *
     * @param finishing the activity is being destroyed for the last time, rather than stopped.
     *   False for the two ways a player goes away and comes back: sent to the background, and
     *   folded into a floating window. Entering a floating window does not finish anything —
     *   closing that window does, and that is the viewer putting the player down.
     * @param changingConfigurations a rotation or a theme change, where the activity is destroyed
     *   and rebuilt within the second. [finishing] is already false for those on every Android
     *   worth supporting, so this is belt and braces rather than the load-bearing term — and a
     *   session ended by turning a phone sideways is exactly the bug worth two lines of defence.
     */
    fun endsSession(finishing: Boolean, changingConfigurations: Boolean): Boolean =
        finishing && !changingConfigurations
}
