package app.kaeru.ui.tv

import android.util.Log
import androidx.compose.ui.focus.FocusRequester

internal const val TV_TAG = "KaeruTv"

/**
 * Requests focus and reports the miss instead of swallowing it. [FocusRequester.requestFocus]
 * throws when nothing focusable is attached yet, which on TV means the screen is left with no
 * D-pad focus at all: a silent failure here is invisible in logs and fatal to navigation.
 */
internal fun FocusRequester.requestFocusOrLog(what: String) {
    try {
        requestFocus()
    } catch (error: IllegalStateException) {
        Log.w(TV_TAG, "No attached focus target for $what; screen starts without D-pad focus", error)
    }
}
