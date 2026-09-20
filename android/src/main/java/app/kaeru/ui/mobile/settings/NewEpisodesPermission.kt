package app.kaeru.ui.mobile.settings

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.ContextCompat

/**
 * Inlined on purpose: the name is a plain string that older platforms simply do not know, and
 * nothing ever asks for it there — [pressOfNewEpisodes] and [shouldOfferNotifications] are what
 * keep the request on the versions that have it.
 */
@SuppressLint("InlinedApi")
internal const val POST_NOTIFICATIONS = Manifest.permission.POST_NOTIFICATIONS

/** Whether Android would currently let this app post anything at all. */
internal fun notificationsGranted(context: Context): Boolean =
    ContextCompat.checkSelfPermission(context, POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED

/** What a press of «Новые серии» actually does, once the platform has had its say. */
internal enum class NewEpisodesPress { TURN_ON, TURN_OFF, ASK_FIRST }

/**
 * Turning the switch on is two decisions, not one.
 *
 * The setting is this app's and the permission is Android's, and on Android 13 and later the
 * second can be refused. So the switch only goes on once there is a permission behind it: a
 * setting that said «on» while the system dropped everything would be a control that lies, and the
 * viewer would be left waiting for notifications that could never come.
 *
 * Turning it off asks nothing. A refusal is not a reason to keep checking in the background, and
 * there is no permission needed to stop.
 */
internal fun pressOfNewEpisodes(wanted: Boolean, sdkInt: Int, granted: Boolean): NewEpisodesPress = when {
    !wanted -> NewEpisodesPress.TURN_OFF
    granted || sdkInt < Build.VERSION_CODES.TIRAMISU -> NewEpisodesPress.TURN_ON
    else -> NewEpisodesPress.ASK_FIRST
}

/**
 * Whether to put the system's question without being asked to, straight after a sign-in.
 *
 * The setting ships on, so a viewer who never opens settings would otherwise never be asked and
 * would never hear a thing. Once, and only where the question exists, and only while the setting
 * still wants it — not at launch, which is the one moment a permission dialog is pure noise.
 */
internal fun shouldOfferNotifications(sdkInt: Int, granted: Boolean, alreadyAsked: Boolean, wanted: Boolean): Boolean =
    sdkInt >= Build.VERSION_CODES.TIRAMISU && !granted && !alreadyAsked && wanted
