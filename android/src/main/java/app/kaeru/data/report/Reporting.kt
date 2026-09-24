package app.kaeru.data.report

import android.content.Context
import android.content.SharedPreferences
import android.os.Bundle
import android.util.Log
import com.google.firebase.FirebaseApp
import com.google.firebase.analytics.FirebaseAnalytics
import com.google.firebase.crashlytics.FirebaseCrashlytics

/**
 * What the app tells its developer about itself: which screens are opened, when an episode starts
 * or fails, how a shared viewing goes — and every crash, with the stack that caused it.
 *
 * Firebase underneath, and only when the build has a project to report to: a checkout without
 * `google-services.json` builds the same app, and every call here is then nothing at all.
 *
 * **What never leaves the phone.** No room id, no room key, no nickname, no chat line, no token,
 * nothing typed. What an event carries is a catalogue id, an episode number, a reason from a fixed
 * list — facts about the app, not about the person holding it.
 *
 * **Off means off from the first frame.** The manifest starts both collectors disabled, and they
 * are switched on here, in `Application.onCreate`, only when the viewer has not said no. Deciding
 * it the other way round — on by default, off once the setting was read — would have sent the
 * first seconds of every launch regardless.
 *
 * An object rather than something injected, for the same reason as `TogetherLog`: the player, the
 * shared session and the navigation all want to say one line from wherever they are, and none of
 * them should need a constructor parameter for it. Unit tests never install it, so it stays silent
 * there.
 */
object Reporting {
    private const val TAG = "Reporting"
    private const val PREFERENCES = "kaeru.reporting"
    private const val KEY = "enabled"

    /** Event names: lowercase and underscores, as Firebase wants them, and never more than these. */
    const val PLAY_START = "play_start"
    const val PLAY_ERROR = "play_error"
    const val TOGETHER = "together"

    private var preferences: SharedPreferences? = null
    private var analytics: FirebaseAnalytics? = null
    private var crashlytics: FirebaseCrashlytics? = null

    /** On unless the viewer turned it off. Off where nothing was installed — in tests. */
    val enabled: Boolean get() = preferences?.getBoolean(KEY, true) ?: false

    /** Whether this build can report at all: it was built with a Firebase project. */
    val available: Boolean get() = analytics != null

    fun install(context: Context, television: Boolean) {
        preferences = context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)
        // FirebaseInitProvider has run by now when the build carries a config, and not otherwise.
        if (FirebaseApp.getApps(context).isEmpty()) return
        runCatching {
            analytics = FirebaseAnalytics.getInstance(context)
            crashlytics = FirebaseCrashlytics.getInstance()
        }.onFailure { Log.w(TAG, "Firebase is linked but would not start", it) }
        apply(enabled)
        // One phone and one television are two different apps to use, and the numbers are no use
        // mixed.
        analytics?.setUserProperty("device", if (television) "tv" else "phone")
    }

    fun setEnabled(on: Boolean) {
        preferences?.edit()?.putBoolean(KEY, on)?.apply()
        apply(on)
    }

    private fun apply(on: Boolean) {
        analytics?.setAnalyticsCollectionEnabled(on)
        crashlytics?.isCrashlyticsCollectionEnabled = on
    }

    /**
     * A screen came up. [name] is a route template or a fixed name — `details/{animeId}`, never
     * the id filled in — so the list of screens stays a list of screens.
     */
    fun screen(name: String) {
        if (!enabled) return
        analytics?.logEvent(FirebaseAnalytics.Event.SCREEN_VIEW, Bundle().apply {
            putString(FirebaseAnalytics.Param.SCREEN_NAME, name)
            putString(FirebaseAnalytics.Param.SCREEN_CLASS, name)
        })
        crashlytics?.log("screen $name")
    }

    /** One thing that happened, with at most a handful of plain values. Nulls are left out. */
    fun event(name: String, vararg params: Pair<String, Any?>) {
        if (!enabled) return
        val bundle = Bundle()
        params.forEach { (key, value) ->
            when (value) {
                null -> Unit
                is Int -> bundle.putLong(key, value.toLong())
                is Long -> bundle.putLong(key, value)
                is Boolean -> bundle.putString(key, value.toString())
                else -> bundle.putString(key, value.toString().take(100))
            }
        }
        analytics?.logEvent(name, bundle)
        crashlytics?.log("$name ${params.filter { it.second != null }.joinToString { "${it.first}=${it.second}" }}")
    }

    /**
     * Something that went wrong without taking the app down, and is worth a stack: a stream that
     * would not resolve, a player that gave up. Shows up in Crashlytics beside the crashes.
     */
    fun problem(error: Throwable) {
        if (!enabled) return
        crashlytics?.recordException(error)
    }
}
