package app.kaeru.data.download

import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.HttpDataSource
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton

/** Why a download stopped, as far as a viewer needs to care. */
enum class DownloadFailureKind {
    /** Kodik's signature ran out and could not be replaced. */
    EXPIRED_LINK,

    /** The device has no room for the rest of the episode. */
    NO_SPACE,

    /** The network went away mid-download; it will pick up again by itself. */
    NETWORK,

    /** Something else. Nothing useful to say beyond that it did not work. */
    UNKNOWN,
}

/**
 * What a failure looks like, and what to say about it.
 *
 * Pure, and separate from the store below, because the classifying is the part worth testing and
 * the storing is a map.
 */
object DownloadFailureCopy {

    fun message(kind: DownloadFailureKind): String = when (kind) {
        DownloadFailureKind.EXPIRED_LINK -> "Ссылка устарела, попробуйте позже"
        DownloadFailureKind.NO_SPACE -> "Недостаточно места"
        DownloadFailureKind.NETWORK -> "Нет связи, загрузка продолжится позже"
        DownloadFailureKind.UNKNOWN -> "Не удалось скачать"
    }

    /**
     * Reads the exception media3 handed its listener.
     *
     * The chain is walked once and then asked in order of how specific the answer is: a refused
     * signature first, then a full disk, then anything that is an `IOException` at all, which at
     * this point means the transfer itself. Order matters — a 403 arrives wrapped in an
     * `IOException`, and answering «нет связи» to it would send the viewer to check their Wi-Fi
     * over a link that simply expired.
     */
    // `androidx.annotation.OptIn`, not Kotlin's: media3's marker is an androidx `@RequiresOptIn`,
    // which lint enforces and the Kotlin compiler does not, and only this one satisfies lint.
    @androidx.annotation.OptIn(UnstableApi::class)
    fun classify(cause: Throwable?): DownloadFailureKind {
        val chain = chainOf(cause)
        if (chain.any { it is HttpDataSource.InvalidResponseCodeException && it.responseCode in EXPIRED }) {
            return DownloadFailureKind.EXPIRED_LINK
        }
        if (chain.any { it.message.orEmpty().looksLikeNoSpace() }) return DownloadFailureKind.NO_SPACE
        if (chain.any { it is IOException }) return DownloadFailureKind.NETWORK
        return DownloadFailureKind.UNKNOWN
    }

    private fun chainOf(cause: Throwable?): List<Throwable> {
        val chain = mutableListOf<Throwable>()
        var current = cause
        // A visited set rather than a depth limit: an exception whose cause is itself is rare but
        // it is a hang, not a slow answer.
        val seen = HashSet<Throwable>()
        while (current != null && seen.add(current)) {
            chain += current
            current = current.cause
        }
        return chain
    }

    /**
     * Android says «no space» in more than one way — `ENOSPC` from the kernel, the wordier
     * `No space left on device` from the JDK layer — and media3 wraps both in a plain
     * `IOException` whose message is all that is left.
     */
    private fun String.looksLikeNoSpace(): Boolean =
        contains("ENOSPC") || contains("No space left", ignoreCase = true)

    private val EXPIRED = setOf(403, 410)
}

/**
 * Why each download last failed, for as long as this process lives.
 *
 * media3 keeps one bit — «unknown» — in the row it persists, and the exception that says more
 * only exists for the instant its listener fires. This is where that instant is kept, so a screen
 * reading the download back a minute later can still say something true.
 *
 * Process-local on purpose: a row that failed before a restart falls back to «Не удалось скачать»,
 * which is the honest thing to say about a failure nothing remembers.
 */
@Singleton
class DownloadFailures @Inject constructor() {

    private val kinds = ConcurrentHashMap<String, DownloadFailureKind>()

    fun record(id: String, kind: DownloadFailureKind) {
        kinds[id] = kind
    }

    /** Called when a download is retried, finishes or is removed: it is not a failure any more. */
    fun forget(id: String) {
        kinds.remove(id)
    }

    fun messageFor(id: String): String =
        DownloadFailureCopy.message(kinds[id] ?: DownloadFailureKind.UNKNOWN)
}
