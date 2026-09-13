package app.kaeru.ui.common.design

import app.kaeru.domain.model.FeedItem
import app.kaeru.domain.model.FeedKind
import app.kaeru.domain.model.LibraryEntry
import java.time.Instant
import java.time.ZoneId
import java.time.temporal.ChronoUnit

/**
 * Every user-facing string the component library builds out of numbers and dates.
 *
 * Two rules from the design system are enforced here rather than left to each screen:
 *
 * 1. **No middle dot.** A status line never joins facts with `·`. Where two facts belong on one
 *    line they are two phrases joined by a comma — `7 серия, осталось 14 мин` — so the line can be
 *    read aloud as a sentence and does not turn into the templated `A · B · C` meta string.
 * 2. **Numbers are never a promise.** Minutes left are floored, so a line that says `14 мин` means
 *    at least fourteen; a length nobody knows prints `?` rather than `0`.
 *
 * The functions are pure: they take the clock and the zone as arguments so a test can put the
 * viewer on any evening it likes.
 */

private const val MINUTE_MS = 60_000L
private const val HOUR_MS = 3_600_000L

/** `1:02:34` for anything an hour or longer, `12:34` otherwise. */
fun formatTime(ms: Long): String {
    if (ms <= 0) return "0:00"
    val total = ms / 1000
    val seconds = total % 60
    val minutes = (total / 60) % 60
    val hours = total / 3600
    return if (hours > 0) {
        "%d:%02d:%02d".format(hours, minutes, seconds)
    } else {
        "%d:%02d".format(minutes, seconds)
    }
}

/**
 * Russian counts three ways. `11..14` are the exception that every naive implementation gets
 * wrong — `11 серий`, not `11 серия` — so they are checked before the last digit.
 */
private fun plural(count: Int, one: String, few: String, many: String): String {
    val n = kotlin.math.abs(count)
    if (n % 100 in 11..14) return many
    return when (n % 10) {
        1 -> one
        2, 3, 4 -> few
        else -> many
    }
}

/** `12 серий`, `1 серия`, `24 серии` — a season length that can stand in a chip on its own. */
fun pluralEpisodes(count: Int): String = "$count ${plural(count, "серия", "серии", "серий")}"

/** `5 из 12`; a season whose length the catalogue does not know prints `5 из ?`. */
fun episodesLabel(watched: Int, total: Int): String = "$watched из ${if (total > 0) total else "?"}"

/**
 * `осталось 14 мин` for an episode in progress, or null when there is nothing honest to say —
 * no known duration, or the episode is already over.
 */
fun remainingLine(positionMs: Long, durationMs: Long): String? {
    if (durationMs <= 0) return null
    val left = durationMs - positionMs
    return when {
        left <= 0 -> null
        left < MINUTE_MS -> "осталось меньше минуты"
        left < HOUR_MS -> "осталось ${left / MINUTE_MS} мин"
        else -> {
            val hours = left / HOUR_MS
            val minutes = (left % HOUR_MS) / MINUTE_MS
            if (minutes == 0L) "осталось $hours ч" else "осталось $hours ч $minutes мин"
        }
    }
}

/**
 * How far off a date is in whole days as the viewer counts them: two moments four hours apart can
 * still be `завтра`, which is what a release schedule means by tomorrow.
 */
fun relativeDay(target: Instant, now: Instant, zone: ZoneId = ZoneId.systemDefault()): String {
    val days = ChronoUnit.DAYS.between(now.atZone(zone).toLocalDate(), target.atZone(zone).toLocalDate()).toInt()
    return when {
        days == 0 -> "сегодня"
        days == 1 -> "завтра"
        days == -1 -> "вчера"
        days > 1 -> "через $days ${plural(days, "день", "дня", "дней")}"
        else -> "${-days} ${plural(days, "день", "дня", "дней")} назад"
    }
}

/**
 * The one-line state of a feed item, as the hero and the poster cards say it.
 *
 * Each kind answers a different question, so each gets its own phrasing rather than one string
 * with optional pieces: what is left of an episode already started, which episode just landed,
 * which one is next, when the next one arrives, how long a planned season runs.
 */
fun episodeLine(item: FeedItem, now: Instant, zone: ZoneId = ZoneId.systemDefault()): String {
    val anime = item.entry.anime
    return when (item.kind) {
        FeedKind.CONTINUE -> {
            val watch = item.entry.watch?.takeIf { it.episode == item.episode }
            val left = watch?.let { remainingLine(it.positionMs, it.durationMs) }
            if (left == null) "${item.episode} серия" else "${item.episode} серия, $left"
        }
        FeedKind.NEW_EPISODE -> "Вышла ${item.episode} серия"
        FeedKind.NEXT_UP -> "${item.episode} серия"
        FeedKind.UPCOMING -> {
            val day = anime.nextEpisodeAt?.let { relativeDay(it, now, zone) } ?: "скоро"
            "${item.episode} серия $day"
        }
        FeedKind.PLANNED -> {
            val season = anime.episodes.takeIf { it > 0 } ?: anime.availableEpisodes
            if (season > 0) "В планах, ${pluralEpisodes(season)}" else "В планах"
        }
    }
}

/**
 * What the watch button says it will do. The label always names the thing that happens, so the
 * viewer can tell a resume from a restart without reading anything else on the screen.
 */
fun primaryActionLabel(entry: LibraryEntry?, threshold: Float): String {
    if (entry == null) return "Смотреть"
    val next = entry.nextEpisode(threshold)
    val position = entry.watch
        ?.takeIf { entry.progressFraction(threshold) != null && it.positionMs > 0 }
        ?.positionMs
    return when {
        position != null -> "Продолжить с ${formatTime(position)}"
        next <= 1 && entry.rate.episodes == 0 -> "Смотреть 1 серию"
        else -> "Продолжить $next серию"
    }
}
