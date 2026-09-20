package app.kaeru.ui.common.update

import app.kaeru.domain.update.UpdateRelease
import java.time.Instant

/**
 * Which of the six things the «Обновления» screen is saying at this moment.
 *
 * Six rather than «loading, content, error», because five of them lead to a different control
 * under them and the sixth is the absence of one. What separates [UP_TO_DATE] from [UNKNOWN] is
 * the difference between «мы спросили, и нового нет» and «мы не смогли спросить» — the same
 * distinction the home screen draws between an empty list and a list nobody has read yet, and it
 * matters here for the same reason: telling somebody they have the latest version when nothing
 * was ever checked is a sentence that is simply untrue.
 */
enum class UpdateStage {
    /** Asking GitHub. Nothing is known yet, or what is known is being replaced. */
    CHECKING,

    /** Nothing was ever learned: a first check that failed, on a device with no stored answer. */
    UNKNOWN,

    /** A check finished and found nothing newer. [UpdateUiState.checkedAt] says when. */
    UP_TO_DATE,

    /** There is a newer release, with a file on it. */
    AVAILABLE,

    /** That file is being fetched. */
    DOWNLOADING,

    /** It is on the device, and the system installer is what happens next. */
    READY,
}

/**
 * Everything the screen draws, with the decisions already made.
 *
 * [message] and [permissionNeeded] sit alongside the stage rather than replacing it, and that is
 * the point: a check that failed over a release already known leaves both the failure and the
 * release on screen, so a viewer on a train reads «доступна версия 0.4.0» and «нет связи» at once
 * instead of losing the first to the second.
 */
data class UpdateUiState(
    /** The version of the build the viewer is running, which never changes while it runs. */
    val installedVersion: String = "",
    val stage: UpdateStage = UpdateStage.CHECKING,
    /** When the last completed check ran, or null on a device that has never managed one. */
    val checkedAt: Instant? = null,
    /** The newer release, when there is one. Null in every other stage. */
    val release: UpdateRelease? = null,
    val downloadedBytes: Long = 0,
    /** What went wrong, already in Russian, or null. */
    val message: String? = null,
    /**
     * Android has not been told this app may install packages.
     *
     * True only after a press that needed it, never on arrival: a screen that opened with a
     * permission request on it would be asking for something before the viewer said they wanted
     * anything.
     */
    val permissionNeeded: Boolean = false,
) {
    /**
     * How much of the file is here, as a fraction.
     *
     * Determinate because it can be: the asset's size comes with the release, so the bar is a bar
     * and not a barber's pole. A release GitHub reported no size for reads as nothing downloaded,
     * which is the honest shape for a length nobody knows.
     */
    val progress: Float
        get() {
            val total = release?.sizeBytes ?: 0
            return if (total > 0) (downloadedBytes.toFloat() / total).coerceIn(0f, 1f) else 0f
        }
}
