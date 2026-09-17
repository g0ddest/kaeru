package app.kaeru.ui.common

import app.kaeru.domain.error.AccountSessionChanged
import app.kaeru.domain.error.AuthCallbackRejected
import app.kaeru.domain.error.CastLoadFailed
import app.kaeru.domain.error.DownloadLimitReached
import app.kaeru.domain.error.EpisodeNotAvailable
import app.kaeru.domain.error.HttpError
import app.kaeru.domain.error.NetworkUnavailable
import app.kaeru.domain.error.PairingFailed
import app.kaeru.domain.error.PairingFailureReason
import app.kaeru.domain.error.SourceFormatChanged
import app.kaeru.domain.error.SourceUnavailable
import app.kaeru.domain.error.SourceUnavailableReason
import app.kaeru.domain.error.StorageFailure
import app.kaeru.domain.update.UpdateFailed
import app.kaeru.domain.update.UpdateFailure

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
private const val CAST_LOAD_FAILED = "Chromecast не смог загрузить видео"
private const val SOURCE_CHANGED = "Источник обновился, ждите обновления приложения"
private const val STORAGE_FAILED = "Не удалось сохранить прогресс просмотра"
private const val PAIR_BAD_LINK = "Эта ссылка не для входа на телевизоре"
private const val PAIR_REFUSED = "Телевизор не принял вход. Покажите новый QR-код и попробуйте снова"
private const val PAIR_UNREACHABLE = "Телевизор не отвечает. Проверьте, что телефон в той же сети Wi-Fi"
private const val PAIR_NO_ADDRESS = "Телевизор не в локальной сети. Войдите по коду"
private const val UNKNOWN = "Что-то пошло не так. Повторите попытку"

/**
 * The «Обновления» screen names its own failures, and every one of them differently.
 *
 * Not because the screen is special but because each of these leads somewhere else: a rate limit
 * is over in an hour and only waiting fixes it, a release with no file on it is the maintainer's
 * problem, and a truncated download is worth pressing the button again for. «Нет связи» rather
 * than the app's usual «Нет соединения. Проверьте интернет» — the screen is one short block of
 * prose, and there is nothing to check but the obvious.
 */
private const val UPDATE_OFFLINE = "Нет связи"
private const val UPDATE_RATE_LIMITED = "GitHub ограничил запросы, попробуйте через час"
private const val UPDATE_NO_ASSET = "У выпуска нет файла для установки"
private const val UPDATE_DOWNLOAD_FAILED = "Не удалось скачать файл"
private const val UPDATE_CORRUPTED = "Файл повреждён, попробуйте ещё раз"
private const val UPDATE_INSTALLER_REFUSED = "Android не открыл установщик"
private const val UPDATE_UNKNOWN = "Не удалось проверить обновления"

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
    this is EpisodeNotAvailable -> EPISODE_MISSING
    this is CastLoadFailed -> CAST_LOAD_FAILED
    this is SourceFormatChanged -> SOURCE_CHANGED
    this is AccountSessionChanged -> SESSION_CHANGED
    this is StorageFailure -> STORAGE_FAILED
    this is DownloadLimitReached -> DOWNLOAD_LIMIT
    this is UpdateFailed -> updateFailureMessage(reason)
    this is PairingFailed && reason == PairingFailureReason.BAD_LINK -> PAIR_BAD_LINK
    this is PairingFailed && reason == PairingFailureReason.UNREACHABLE -> PAIR_UNREACHABLE
    this is PairingFailed && reason == PairingFailureReason.NO_LOCAL_ADDRESS -> PAIR_NO_ADDRESS
    this is PairingFailed -> PAIR_REFUSED
    else -> UNKNOWN
}

/**
 * The same words for a download stage, which carries a reason without being a throwable.
 *
 * One function for both, so «Файл повреждён» cannot come to be written two ways.
 */
fun updateFailureMessage(reason: UpdateFailure): String = when (reason) {
    UpdateFailure.NO_NETWORK -> UPDATE_OFFLINE
    UpdateFailure.RATE_LIMITED -> UPDATE_RATE_LIMITED
    UpdateFailure.NO_ASSET -> UPDATE_NO_ASSET
    UpdateFailure.DOWNLOAD_FAILED -> UPDATE_DOWNLOAD_FAILED
    UpdateFailure.CORRUPTED -> UPDATE_CORRUPTED
    UpdateFailure.INSTALLER_REFUSED -> UPDATE_INSTALLER_REFUSED
    UpdateFailure.UNKNOWN -> UPDATE_UNKNOWN
}

/** Null when the result succeeded; the mapped message otherwise. */
fun Result<*>.errorMessageOrNull(): String? = exceptionOrNull()?.toUserMessage()
