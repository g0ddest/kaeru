package app.kaeru.ui.common.together

import app.kaeru.domain.together.LostReason
import app.kaeru.domain.together.NoticeKind
import app.kaeru.domain.together.TogetherEvent
import app.kaeru.ui.common.design.formatTime

/**
 * Everything a shared viewing says, in one place.
 *
 * Here rather than in the screens because the same sentence is said in two activities: the player
 * overlay and the join screen are different windows with different view model instances, and a
 * copy of «Смотреть одному» in each is two strings that will one day disagree about what the exit
 * out of a broken session is called.
 *
 * The rule the whole feature is arranged around shows up in the naming: [KEEP_WATCHING] and
 * [WATCH_ALONE] are the only two ways out of a wait, and every wait state below is paired with
 * exactly one of them.
 *
 * Verbs are written «поставил(а)» rather than guessing. The name comes from Shikimori, which has
 * no gender on it, and a phone that tells a woman she «поставил на паузу» is a phone that got a
 * fact wrong to save two characters.
 */
object TogetherCopy {

    // --- entering ------------------------------------------------------------------------------

    const val WATCH_TOGETHER = "Смотреть вместе"
    const val LEAVE = "Выйти из совместного просмотра"

    /** The one name for somebody Shikimori never named. */
    const val SOMEBODY = "Друг"

    // --- waiting -------------------------------------------------------------------------------

    const val WAITING_FRIEND = "Ждём друга…"
    const val CONNECTING = "Подключаемся…"
    const val KEEP_WATCHING = "Смотреть дальше"
    const val WATCH_ALONE = "Смотреть одному"

    // --- joining -------------------------------------------------------------------------------

    const val JOIN = "Присоединиться"
    const val NOT_NOW = "Не сейчас"
    const val MIC_NOTE = "Микрофон включается только пока держите кнопку."
    const val BAD_LINK = "Ссылка не подходит"
    const val UNREACHABLE = "Не удалось подключиться"
    const val RETRY = "Повторить"
    const val CLOSE = "Закрыть"
    const val CANCEL = "Отмена"

    // --- talking -------------------------------------------------------------------------------

    const val WRITE_PLACEHOLDER = "Написать…"
    const val YOU = "Вы"
    const val EMPTY_HISTORY = "Пока тихо"
    const val HISTORY = "Показать переписку"
    const val REACTIONS = "Реакции"
    const val VOICE = "Записать голосовое"
    const val VOICE_HINT = "Удерживайте, чтобы записать"
    const val VOICE_LOCK = "↑ закрепить"
    const val VOICE_CANCEL = "◀ отмена"
    const val REPLAY = "Послушать ещё раз"
    const val MIC_DENIED = "Нужен доступ к микрофону"
    const val SEND = "Отправить"

    /** Three taps that cover most of what gets said, so the keyboard stays shut. */
    val PRESETS = listOf("😂", "Стоп, что?", "Дальше!")

    /** What a chat message may be before the protocol says no. */
    const val MAX_CHARS = 200

    // --- ending --------------------------------------------------------------------------------

    const val ENDED = "Сессия закончилась"
    const val LEFT_SESSION = "Вы вышли из совместного просмотра"

    /**
     * The episode by its number, as the player's own top bar says it.
     *
     * Not `pluralEpisodes`: that one counts episodes, and «7 серий» means seven of them. This is
     * the seventh one, where the numeral is an ordinal and the noun does not agree with it —
     * «7 серия» — which is also why the accusative is a separate string rather than a flag.
     */
    fun episodeNominative(episode: Int): String = "$episode серия"

    fun episodeAccusative(episode: Int): String = "$episode серию"

    /** «Смотрим «Проводы в последний путь», 7 серию. Открой в Kaeru: …» */
    fun shareText(title: String, episode: Int, link: String): String =
        "Смотрим «$title», ${episodeAccusative(episode)}. Открой в Kaeru: $link"

    /** «Вася смотрит «Проводы в последний путь», 7 серия, 12:04» */
    fun joinLine(peerName: String, title: String, episode: Int, positionMs: Long): String =
        "${name(peerName)} смотрит «$title», ${episodeNominative(episode)}, ${formatTime(positionMs)}"

    /**
     * What the other phone just did.
     *
     * One line, no punctuation at the end, and always with a name in front of it except for the
     * one line that is about this viewer's own stream rather than about the other person.
     */
    fun notice(event: TogetherEvent.Notice): String {
        val who = name(event.peerName)
        return when (event.kind) {
            NoticeKind.PAUSED -> "$who поставил(а) на паузу"
            NoticeKind.PLAYED -> "$who включил(а)"
            NoticeKind.SEEKED -> "$who перемотал(а) на ${formatTime(event.positionMs ?: 0)}"
            NoticeKind.EPISODE -> "$who включил(а) ${episodeAccusative(event.episode ?: 0)}"
            NoticeKind.JOINED -> "$who подключился(ась)"
            NoticeKind.LEFT -> "$who вышел(ла)"
            NoticeKind.CATCHING_UP -> "$who догоняет…"
            NoticeKind.OTHER_VOICE -> "У тебя другая озвучка"
        }
    }

    /** Why it stopped. Never «что-то пошло не так»: each of these is a different thing to do next. */
    fun lost(reason: LostReason): String = when (reason) {
        LostReason.CONNECTION -> "Связь с другом потеряна"
        LostReason.WAIT_TIMEOUT -> UNREACHABLE
        LostReason.ROOM_FULL -> "В этой сессии уже двое"
        LostReason.NOT_CONFIGURED -> "Сервер совместного просмотра не настроен"
        // The same two sentences as on iOS: one room, one story about why it stopped.
        LostReason.EXPIRED -> ROOM_EXPIRED
        LostReason.SAME_SIDE -> "Ссылку открыли оба — комнату держит тот, кто её создал"
    }

    /** The relay's idle close, 4408. Not «связь потеряна»: nothing was lost, the room ran out. */
    const val ROOM_EXPIRED = "Комната закрылась: в ней шесть часов ничего не происходило"

    /** The label under a wait. There is always one. */
    fun exitLabel(exit: WaitExit): String = when (exit) {
        WaitExit.KEEP_WATCHING -> KEEP_WATCHING
        WaitExit.WATCH_ALONE -> WATCH_ALONE
    }

    /** `0:07` — a clip's length, read the same way as a position on the timeline. */
    fun clipLength(durationMs: Int): String = formatTime(durationMs.toLong())

    /**
     * What the player's own control says while something is going on, or null for the icon.
     *
     * A chip in the row that already carries the dub and the quality, because the person on the
     * other phone is the same kind of fact about this session: what is currently the case. A
     * session that has ended shows nothing, so the control goes back to being an invitation.
     */
    fun sessionChip(phase: TogetherPhase, peerName: String?): String? = when (phase) {
        TogetherPhase.LIVE -> name(peerName)
        TogetherPhase.HOSTING -> WAITING_FRIEND
        TogetherPhase.JOINING -> CONNECTING
        TogetherPhase.IDLE, TogetherPhase.LOST, TogetherPhase.ENDED -> null
    }

    /** Whoever is on the other phone, named. */
    fun name(peerName: String?): String = peerName?.trim().orEmpty().ifEmpty { SOMEBODY }
}
