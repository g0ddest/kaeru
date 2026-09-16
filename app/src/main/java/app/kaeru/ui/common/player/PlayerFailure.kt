package app.kaeru.ui.common.player


/** Said when an episode that is on the device will not play and the network is not the problem. */
private const val BROKEN_DOWNLOAD =
    "Не удалось воспроизвести скачанную серию. Удалите загрузку и скачайте заново"

/**
 * The second way forward on the surface over a failed video. «Повторить» is always the first.
 *
 * Two, because a failure has two different shapes here and each has its own escape. A stream that
 * will not load is a Kodik problem, and another voice is a different stream; a downloaded file that
 * will not decode is a problem with this device's copy, and the way past it is to stop using that
 * copy.
 */
enum class PlayerRecovery {
    /** Another voice from the source, for a stream that will not load. */
    CHANGE_TRANSLATION,

    /** Take the broken copy away and play from the source instead. */
    REMOVE_DOWNLOAD,
}

/** What the surface over a failed video says, and what it offers besides trying again. */
data class PlayerFailure(val message: String, val recovery: PlayerRecovery)

/**
 * What to show over a video that would not play, or null when nothing went wrong.
 *
 * One case is worth spelling out, because the honest message for it is the opposite of the one the
 * app used to give. An episode already on the device plays with no network at all, so when it
 * fails *with* a network the failure is the file: the bytes were evicted, the download finished
 * against a truncated stream, the container will not decode. «Нет сети. Скачайте серию заранее» is
 * then false twice over — there is a network, and the episode has already been downloaded — and it
 * sends the viewer to do the one thing they have already done.
 *
 * The condition is the controller's word for it and not a guess made here. «Эта серия скачана» is
 * not the same fact as «сломалось то, что скачано»: a downloaded episode plays from Kodik whenever
 * the viewer picks another voice, and always while casting, and a failure on either of those paths
 * belongs to the source with the file sitting there untouched. Offering to delete it — which is
 * what this branch offers — would then destroy a working download over somebody else's fault.
 *
 * The message for everything else is the one the failure already carried: it comes from
 * `toUserMessage()`, which names the cause and the next step, and there is nothing to add to it.
 */
fun playerFailure(state: PlayerUiState): PlayerFailure? {
    val message = state.errorMessage ?: return null
    return if (!state.offline && state.failedReadingDownload) {
        PlayerFailure(BROKEN_DOWNLOAD, PlayerRecovery.REMOVE_DOWNLOAD)
    } else {
        PlayerFailure(message, PlayerRecovery.CHANGE_TRANSLATION)
    }
}
