package app.kaeru.ui.common.update

import app.kaeru.domain.update.UpdateRelease
import app.kaeru.ui.common.design.formatBytes
import app.kaeru.ui.common.design.shortDate
import app.kaeru.ui.common.design.updateAvailableText
import java.time.Instant
import java.time.ZoneId

/**
 * Every word the «Обновления» screen says, in one file because two screens say them.
 *
 * The phone and the television draw this differently — one scrolls under a top bar, the other is
 * a list of focus stops — but they are the same page and have to read the same. A copy of the
 * wording in each screen would be two wordings that disagree the first time either is edited.
 *
 * The lines that are built out of a release are functions rather than templates at the call site,
 * so what a screen says can be read in a test instead of on a device.
 */

const val UPDATES_TITLE = "Обновления"
const val UPDATES_BACK = "Назад"

/** The section names, in the order the page is read. */
const val UPDATES_INSTALLED = "Установлено"
const val UPDATES_LATEST = "Последний выпуск"

const val UPDATES_CHECKING = "Проверяем…"
const val UPDATES_UP_TO_DATE = "У вас последняя версия"
const val UPDATES_UNKNOWN = "Пока ничего не известно о новых версиях"
const val UPDATES_CHECK = "Проверить"
const val UPDATES_DOWNLOAD = "Скачать и установить"
const val UPDATES_INSTALL = "Установить"
const val UPDATES_DOWNLOADING = "Скачиваем файл"
const val UPDATES_READY = "Файл скачан"
const val UPDATES_READY_NOTE = "Осталось подтвердить установку — её открывает сама система"

/**
 * The one sentence that explains a system prompt before it arrives.
 *
 * Android asks once, per app, and the question it asks — «разрешить установку неизвестных
 * приложений?» — sounds alarming with no context. Saying what it is for beforehand is the
 * difference between a viewer answering it and a viewer backing out of it.
 */
const val UPDATES_PERMISSION = "Android один раз спросит, можно ли Kaeru устанавливать приложения"
const val UPDATES_ALLOW = "Разрешить установку"

/** `Kaeru 0.3.0` — the same line the settings page shows, so the two agree on sight. */
fun installedLine(version: String): String = "Kaeru $version"

/**
 * `Доступна версия 0.4.0` — the headline of this screen, and the whole of the home row.
 *
 * The words themselves live in the design system, with the row that is made of nothing else. The
 * row is a signpost to this page, and a signpost worded differently from the page it leads to
 * would read as two separate pieces of news about one release.
 */
fun availableLine(version: String): String = updateAvailableText(version)

/**
 * `16 сентября 2026, 30 МБ` — when it came out and what it costs to fetch.
 *
 * Joined with a comma rather than a middle dot, as every two-fact line in this app is. A release
 * with no date prints only the size, and one with neither prints nothing at all rather than an
 * empty phrase with punctuation in it.
 */
fun releaseLine(release: UpdateRelease, zone: ZoneId = ZoneId.systemDefault()): String? {
    val date = release.publishedAt?.let { shortDate(it, zone) }
    val size = release.sizeBytes.takeIf { it > 0 }?.let(::formatBytes)
    return listOfNotNull(date, size).takeIf { it.isNotEmpty() }?.joinToString(", ")
}

/** `Проверено 16 сентября 2026`, or nothing on a device that has never managed a check. */
fun checkedLine(at: Instant?, zone: ZoneId = ZoneId.systemDefault()): String? =
    at?.let { "Проверено ${shortDate(it, zone)}" }

/**
 * `12 МБ из 30 МБ` — how far the download has got, in the units the rest of the app uses.
 *
 * A transfer whose total nobody knows says only what has arrived: `12 МБ`. Inventing a total for
 * the bar to fill would be the app promising a finish line it cannot see.
 */
fun downloadedLine(bytes: Long, totalBytes: Long): String =
    if (totalBytes > 0) "${formatBytes(bytes)} из ${formatBytes(totalBytes)}" else formatBytes(bytes)
