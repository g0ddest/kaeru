package app.kaeru.ui.common

import app.kaeru.domain.error.AccountSessionChanged
import app.kaeru.domain.error.AuthCallbackRejected
import app.kaeru.domain.error.CastLoadFailed
import app.kaeru.domain.error.DownloadLimitReached
import app.kaeru.domain.error.EpisodeNotAvailable
import app.kaeru.domain.error.EpisodeUnavailableReason
import app.kaeru.domain.error.HttpError
import app.kaeru.domain.error.NetworkUnavailable
import app.kaeru.domain.error.PairingFailed
import app.kaeru.domain.error.PairingFailureReason
import app.kaeru.domain.error.SourceFormatChanged
import app.kaeru.domain.error.SourceUnavailable
import app.kaeru.domain.error.SourceUnavailableReason
import app.kaeru.domain.error.StorageFailure

private const val OFFLINE = "Нет соединения. Проверьте интернет"
private const val SIGNED_OUT = "Сессия истекла, войдите снова"
private const val THROTTLED = "Слишком много запросов, попробуйте позже"
private const val SHIKIMORI_DOWN = "Shikimori недоступен, попробуйте позже"
private const val SESSION_CHANGED = "Сессия изменилась, обновите экран"
private const val CALLBACK_REJECTED = "Не удалось подтвердить вход. Войдите заново"
private const val SOURCE_NO_KEY = "Kodik недоступен: не удалось получить ключ"
private const val SOURCE_REJECTED = "Kodik временно недоступен, попробуйте позже"
private const val SOURCE_OFFLINE = "Нет сети. Скачайте серию заранее"
private const val DOWNLOAD_LIMIT = "Лимит места исчерпан. Удалите загрузки или увеличьте лимит в настройках"
private const val EPISODE_MISSING = "Серия ещё не появилась в Kodik"
private const val EPISODE_NOT_IN_TRACK = "Этой серии ещё нет в выбранной озвучке"
private const val EPISODE_NOWHERE = "Серия пока не вышла ни в одной озвучке"
private const val CAST_LOAD_FAILED = "Chromecast не смог загрузить видео"
private const val SOURCE_CHANGED = "Источник обновился, ждите обновления приложения"
private const val STORAGE_FAILED = "Не удалось сохранить прогресс просмотра"
private const val PAIR_BAD_LINK = "Эта ссылка не для входа на телевизоре"
private const val PAIR_REFUSED = "Телевизор не принял вход. Покажите новый QR-код и попробуйте снова"
private const val PAIR_UNREACHABLE = "Телевизор не отвечает. Проверьте, что телефон в той же сети Wi-Fi"
private const val PAIR_NO_ADDRESS = "Телевизор не в локальной сети. Войдите по коду"
private const val UNKNOWN = "Что-то пошло не так. Повторите попытку"

/**
 * The single place where a failure becomes user-facing copy. Exception text is never shown:
 * it is English, often a stack-trace fragment, and sometimes carries request details.
 */
fun Throwable.toUserMessage(): String = when {
    this is NetworkUnavailable -> OFFLINE
    this is HttpError && (code == 401 || code == 403) -> SIGNED_OUT
    this is HttpError && code == 429 -> THROTTLED
    this is HttpError && code in 500..599 -> SHIKIMORI_DOWN
    this is AuthCallbackRejected -> CALLBACK_REJECTED
    this is SourceUnavailable && reason == SourceUnavailableReason.NO_KEY -> SOURCE_NO_KEY
    this is SourceUnavailable && reason == SourceUnavailableReason.OFFLINE -> SOURCE_OFFLINE
    this is SourceUnavailable -> SOURCE_REJECTED
    this is EpisodeNotAvailable -> episodeMissingCopy()
    this is CastLoadFailed -> CAST_LOAD_FAILED
    this is SourceFormatChanged -> SOURCE_CHANGED
    this is AccountSessionChanged -> SESSION_CHANGED
    this is StorageFailure -> STORAGE_FAILED
    this is DownloadLimitReached -> DOWNLOAD_LIMIT
    this is PairingFailed && reason == PairingFailureReason.BAD_LINK -> PAIR_BAD_LINK
    this is PairingFailed && reason == PairingFailureReason.UNREACHABLE -> PAIR_UNREACHABLE
    this is PairingFailed && reason == PairingFailureReason.NO_LOCAL_ADDRESS -> PAIR_NO_ADDRESS
    this is PairingFailed -> PAIR_REFUSED
    else -> UNKNOWN
}

/**
 * How much of nothing the source had, as one sentence each.
 *
 * The number goes in wherever it is known: «Серия 5 пока не вышла ни в одной озвучке» is a fact
 * the viewer can check against the studios' own pages, and it is only said after every one of
 * them was asked. The first sentence is the old one and still right for a title Kodik does not
 * have at all — no dub picker helps there, and none is offered.
 */
private fun EpisodeNotAvailable.episodeMissingCopy(): String = when (reason) {
    EpisodeUnavailableReason.TITLE_NOT_ON_SOURCE -> EPISODE_MISSING
    EpisodeUnavailableReason.NOT_IN_TRANSLATION ->
        episode?.let { "Серии $it ещё нет в этой озвучке" } ?: EPISODE_NOT_IN_TRACK
    EpisodeUnavailableReason.NOT_IN_ANY_TRANSLATION ->
        episode?.let { "Серия $it пока не вышла ни в одной озвучке" } ?: EPISODE_NOWHERE
}

/** Null when the result succeeded; the mapped message otherwise. */
fun Result<*>.errorMessageOrNull(): String? = exceptionOrNull()?.toUserMessage()
