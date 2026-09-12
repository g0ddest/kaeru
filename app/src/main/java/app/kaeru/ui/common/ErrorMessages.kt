package app.kaeru.ui.common

import app.kaeru.domain.error.AccountSessionChanged
import app.kaeru.domain.error.AuthCallbackRejected
import app.kaeru.domain.error.EpisodeNotAvailable
import app.kaeru.domain.error.HttpError
import app.kaeru.domain.error.NetworkUnavailable
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
private const val EPISODE_MISSING = "Серия ещё не появилась в Kodik"
private const val SOURCE_CHANGED = "Источник обновился, ждите обновления приложения"
private const val STORAGE_FAILED = "Не удалось сохранить прогресс просмотра"
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
    this is SourceUnavailable -> SOURCE_REJECTED
    this is EpisodeNotAvailable -> EPISODE_MISSING
    this is SourceFormatChanged -> SOURCE_CHANGED
    this is AccountSessionChanged -> SESSION_CHANGED
    this is StorageFailure -> STORAGE_FAILED
    else -> UNKNOWN
}

/** Null when the result succeeded; the mapped message otherwise. */
fun Result<*>.errorMessageOrNull(): String? = exceptionOrNull()?.toUserMessage()
