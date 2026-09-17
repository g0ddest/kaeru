package app.kaeru.ui.mobile.player

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import androidx.core.content.ContextCompat

/**
 * The buttons under the floating window, and the wiring that carries a press back to the player.
 *
 * A [android.app.RemoteAction] can carry nothing but a [PendingIntent], so the press arrives as a
 * broadcast and something in this process has to be listening for it. Three things about that are
 * easy to get wrong and impossible to see from the code that uses them, which is why they live in
 * a class of their own with a test on it rather than inline in the activity:
 *
 * - the filter is registered as not exported, which Android 14 and later require by name of every
 *   dynamic receiver of an app-defined broadcast, and refuse the registration without;
 * - each button gets its own request code, because a [PendingIntent] compares intents without
 *   their extras and one code for both would hand «следующая серия» the play button's intent;
 * - the intents are immutable and addressed to this package, so nothing outside the app can
 *   rewrite a press or answer one.
 *
 * Not a lifecycle observer and not an activity's inner class: [register] and [unregister] are
 * called by whoever owns the window, and the callbacks are whatever they want a press to do.
 */
class WindowControls(
    private val context: Context,
    private val onPlayPause: () -> Unit,
    private val onNext: () -> Unit,
) {

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            when (intent?.getIntExtra(EXTRA_CONTROL, 0)) {
                CONTROL_PLAY_PAUSE -> onPlayPause()
                CONTROL_NEXT -> onNext()
            }
        }
    }

    /** Registering twice would deliver every press twice; unregistering an idle one throws. */
    private var registered = false

    fun register() {
        if (registered) return
        registered = true
        ContextCompat.registerReceiver(
            context,
            receiver,
            IntentFilter(ACTION_WINDOW_CONTROL),
            ContextCompat.RECEIVER_NOT_EXPORTED,
        )
    }

    fun unregister() {
        if (!registered) return
        registered = false
        // A context already torn down refuses this, and a window being taken down is no place to
        // find out about it.
        runCatching { context.unregisterReceiver(receiver) }
    }

    /** What the system holds on the window's behalf and sends back when the button is pressed. */
    fun action(control: Int): PendingIntent = PendingIntent.getBroadcast(
        context,
        control,
        Intent(ACTION_WINDOW_CONTROL)
            .setPackage(context.packageName)
            .putExtra(EXTRA_CONTROL, control),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )

    companion object {
        /** Registered for this app only, so nothing outside it can drive the floating window. */
        const val ACTION_WINDOW_CONTROL = "app.kaeru.player.WINDOW_CONTROL"
        const val EXTRA_CONTROL = "control"
        const val CONTROL_PLAY_PAUSE = 1
        const val CONTROL_NEXT = 2
    }
}
