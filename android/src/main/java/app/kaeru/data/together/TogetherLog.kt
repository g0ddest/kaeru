package app.kaeru.data.together

import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors

/**
 * A few lines about what a room actually did, kept on the device.
 *
 * iOS's `TogetherLog`, line for line: the same events in the same words, so the two journals of
 * one evening can be read side by side. A shared viewing fails on somebody else's phone, on
 * somebody else's network, against a relay that is a stranger's machine — «Связь с другом
 * потеряна» is all the screen can honestly say, and it is not enough to fix anything by. Which
 * phone paused, and whether that was a hold for a friend who was loading, an incoming `pause`
 * or the viewer's own thumb, is the difference between a bug and an evening.
 *
 * `together.log` in the app's external files directory, so a release build gives it up without
 * `run-as`: `adb pull /sdcard/Android/data/app.kaeru/files/together.log`. Small on purpose: one
 * file, started again past 64 KB, plain text, no personal data and no room keys — a room id is
 * the half of a link that is safe to write down. Written off the calling thread, one line at a
 * time, and a write that fails is a line lost and nothing else.
 */
object TogetherLog {
    private const val NAME = "together.log"

    /** Beyond this the file is started again: this is for the last evening, not for all of them. */
    private const val LIMIT = 512 * 1024
    private const val PREFERENCES = "kaeru.together"
    private const val KEY_ENABLED = "log"

    @Volatile
    private var file: File? = null

    private val writer = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "together-log").apply { isDaemon = true }
    }

    /** Only ever touched on the writer's thread. */
    private val stamp = SimpleDateFormat("HH:mm:ss.SSS", Locale.US)

    /** Where the journal lives. Called once by whoever knows the app's directories; null keeps it off. */
    @Volatile
    private var preferences: android.content.SharedPreferences? = null

    /**
     * Off unless the viewer turned it on in Settings. A journal is for the evening something goes
     * wrong, not for every evening — a file that grows on every viewing is a file nobody asked for.
     */
    val enabled: Boolean
        get() = forced ?: (preferences?.getBoolean(KEY_ENABLED, false) == true)

    /** Set by the test seam below, where there are no preferences to read the switch from. */
    @Volatile
    private var forced: Boolean? = null

    fun install(context: android.content.Context) {
        file = context.getExternalFilesDir(null)?.let { File(it, NAME) }
        preferences = context.getSharedPreferences(PREFERENCES, android.content.Context.MODE_PRIVATE)
        forced = null
    }

    /** A directory and no switch: the journal is simply on. For tests, which have no `Context`. */
    fun install(directory: File?, enabled: Boolean = true) {
        file = directory?.let { File(it, NAME) }
        preferences = null
        forced = enabled
    }

    fun setEnabled(on: Boolean) {
        preferences?.edit()?.putBoolean(KEY_ENABLED, on)?.apply()
        if (!on) writer.execute { file?.delete() }
    }

    fun write(line: String) {
        if (!enabled) return
        val target = file ?: return
        val at = System.currentTimeMillis()
        runCatching { writer.execute { append(target, at, line) } }
    }

    /** What the last evening said, newest last. For a screen that offers to share it. */
    fun read(): String = file?.let { runCatching { it.readText() }.getOrNull() } ?: ""

    fun clear() {
        val target = file ?: return
        runCatching { writer.execute { target.delete() } }
    }

    /** Waits for everything written so far to be on disk. For tests, and for a share sheet. */
    fun flush() {
        runCatching { writer.submit {}.get() }
    }

    private fun append(target: File, at: Long, line: String) {
        runCatching {
            if (target.length() > LIMIT) target.delete()
            target.parentFile?.mkdirs()
            target.appendText("${stamp.format(Date(at))} $line\n")
        }
    }
}
