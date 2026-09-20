package app.kaeru.domain.update

/**
 * Why an update could not be checked, downloaded or installed.
 *
 * Every one of these reads differently to a viewer and leads somewhere different, which is why
 * they are separate rather than one «не удалось». A rate limit is over in an hour and nothing but
 * waiting fixes it; a release with no file attached is the maintainer's problem and not the
 * viewer's; a truncated download is worth pressing the button again for. The wording lives in the
 * UI layer, as every user-facing string in this app does.
 */
enum class UpdateFailure {
    /** The network could not be reached at all. */
    NO_NETWORK,

    /** GitHub turned the unauthenticated request away: sixty an hour, per address. */
    RATE_LIMITED,

    /** There is a newer release, and nobody attached an APK to it. */
    NO_ASSET,

    /** The transfer stopped, or the server answered with something that was not the file. */
    DOWNLOAD_FAILED,

    /** The file arrived and is not the length GitHub said it would be. */
    CORRUPTED,

    /** The system installer was handed the file and did not open. */
    INSTALLER_REFUSED,

    /** Something else — an answer that would not parse, a device that would not write. */
    UNKNOWN,
}

/** A failure carrying the one thing the UI has to know about it. */
class UpdateFailed(val reason: UpdateFailure, cause: Throwable? = null) :
    Exception("Update failed: $reason", cause)
