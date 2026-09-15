package app.kaeru.ui.common.design

import app.kaeru.domain.discover.Season
import app.kaeru.domain.discover.SeasonKind
import app.kaeru.domain.model.Anime
import app.kaeru.domain.model.FeedItem
import app.kaeru.domain.model.FeedKind
import app.kaeru.domain.model.LibraryEntry
import app.kaeru.domain.model.ListStatus
import java.time.Instant
import java.time.ZoneId
import java.time.temporal.ChronoUnit

/**
 * Every user-facing string the component library builds out of numbers and dates, and the fixed
 * phrases more than one screen has to say the same way.
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

/**
 * `21 серию` — the same count as [pluralEpisodes] in the case a verb puts it in.
 *
 * «Показать ещё 21 серия» is what a naive join produces and it is simply ungrammatical: «показать»
 * takes the accusative, so the noun changes and the numeral does not. Separate from
 * [pluralEpisodes] rather than a parameter on it, because a caller picking a grammatical case with
 * a boolean is a caller that will pick the wrong one.
 */
fun pluralEpisodesAccusative(count: Int): String = "$count ${plural(count, "серию", "серии", "серий")}"

/**
 * `Лето 2026` — a broadcast season as a chip says it.
 *
 * The season's name lives here rather than on the domain type: it is a word the viewer reads, and
 * every one of those in this app is built in the UI layer.
 */
fun seasonTitle(season: Season): String {
    val name = when (season.kind) {
        SeasonKind.WINTER -> "Зима"
        SeasonKind.SPRING -> "Весна"
        SeasonKind.SUMMER -> "Лето"
        SeasonKind.FALL -> "Осень"
    }
    return "$name ${season.year}"
}

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
            val row = item.entry.progressAt(item.episode)
            val left = row?.let { remainingLine(it.positionMs, it.durationMs) }
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
 * Episodes that exist to play right now.
 *
 * A thin alias for `Anime.availableEpisodes`, kept so call sites here read in terms of what has
 * aired rather than a more general-sounding name. The rule itself — aired so far while ongoing,
 * nothing for an announcement, the announced total once finished — lives once on the domain
 * model; keeping it there is what keeps a button from offering, and a grid from opening, an
 * episode that does not exist yet.
 */
fun Anime.airedEpisodes(): Int = availableEpisodes

/**
 * Offered when the whole show is behind the viewer. No episode number on it: a rewatch starts at
 * the beginning, and «Пересмотреть 1 серию» would read as an offer of one episode rather than of
 * the show.
 */
private const val REWATCH = "Пересмотреть"

data class PrimaryAction(
    val label: String,
    val enabled: Boolean,
    /** The episode this would start, or null when there is nothing to start. */
    val episode: Int?,
)

/**
 * The watch button, decided in one place: what it says, whether it can be pressed, and which
 * episode it would start.
 *
 * A label alone is not enough, which is what this replaces. «Смотреть 11 серию» under a season
 * that has aired ten is a button that promises something the source cannot give, and pressing it
 * ends at «Серия ещё не появилась в Kodik». So the answer carries [enabled] with the label, and
 * [episode] is non-null exactly when there is something to start.
 *
 * The order of the cases is the order of what the viewer most wants to know:
 *
 * 1. a position inside an episode — that episode is on this device, so it is offered first, and
 *    which episode that is comes from `ContinueTarget`, so a tap that landed on the wrong tile
 *    never becomes the offer;
 * 2. a show with nothing left ahead of it — «Пересмотреть», from the first episode;
 * 3. an episode that has aired — «Смотреть 1 серию» or «Продолжить 7 серию»;
 * 4. an episode that has not, with a date still ahead — «9 серия выйдет завтра»;
 * 5. nothing aired at all — «Ещё не вышло», which is the whole truth about an announcement;
 * 6. anything else still to come — «Ждём 9 серию».
 *
 * Case 4 is deliberately ahead of case 5: an announcement with a broadcast date is better served
 * by that date than by a shrug, and both are equally unpressable. A date already in the past is
 * ignored — the catalogue has simply not caught up, and repeating it would be a promise about
 * yesterday.
 */
fun primaryAction(
    entry: LibraryEntry?,
    watchedThreshold: Float,
    now: Instant,
    zone: ZoneId = ZoneId.systemDefault(),
): PrimaryAction {
    if (entry == null) return PrimaryAction("Смотреть", enabled = true, episode = 1)
    val target = entry.continueTarget(watchedThreshold)
    val next = target.episode
    if (target.positionMs > 0) return PrimaryAction("Продолжить с ${formatTime(target.positionMs)}", true, next)

    val aired = entry.anime.airedEpisodes()
    if (next <= aired) {
        // A show with nothing left ahead of it is offered from the beginning, and the verb says so
        // rather than pretending this is a first viewing.
        if (target.rewatch) return PrimaryAction(REWATCH, enabled = true, episode = next)
        // «Продолжить» is a promise about an episode still ahead of the viewer. When the target is
        // one they have already finished — the whole show is behind them, and the alternative is a
        // button naming an episode that will never come out — the honest verb is «Смотреть»: this
        // starts the episode again.
        val seen = next <= entry.rate.episodes || entry.progressAt(next)?.unfinished(watchedThreshold) == false
        val label = if (next <= 1 || seen) "Смотреть $next серию" else "Продолжить $next серию"
        return PrimaryAction(label, enabled = true, episode = next)
    }
    return PrimaryAction(
        waitingLabel(next, entry.anime.nextEpisodeAt, aired, now, zone),
        enabled = false,
        episode = null,
    )
}

/**
 * What to say about an episode that cannot be started yet.
 *
 * The waiting half of [primaryAction], on its own because the player says the same thing when an
 * episode runs out with nothing aired after it: «13 серия выйдет завтра» rather than a countdown
 * to an episode Kodik does not have. One wording, so the button on the title screen and the card
 * over the video cannot drift apart.
 *
 * A date already in the past is ignored — the catalogue has simply not caught up, and repeating
 * it would be a promise about yesterday.
 */
fun waitingLabel(
    episode: Int,
    nextEpisodeAt: Instant?,
    aired: Int,
    now: Instant,
    zone: ZoneId = ZoneId.systemDefault(),
): String {
    val date = nextEpisodeAt?.takeIf { !it.isBefore(now) }
    return when {
        date != null -> "$episode серия выйдет ${relativeDay(date, now, zone)}"
        aired <= 0 -> "Ещё не вышло"
        else -> "Ждём $episode серию"
    }
}

/**
 * The mark on a track the viewer keeps coming back to.
 *
 * One string for three surfaces: the title screen's chooser and the phone player render it as a
 * chip through [OftenChosenChip], and the television — where a row is read from three metres and
 * a chip beside a studio name is a smudge — sets it verbatim on a second line under the name. A
 * second copy of the phrase would drift from this one the first time either is reworded.
 */
internal const val OFTEN_CHOSEN = "Часто выбираете"

/**
 * Where a title sits in the viewer's list, in the words Shikimori uses for it.
 *
 * Here rather than on any one screen: the title screen's menu, the library's tabs and anything
 * later that names a status all have to say the same six words, and a screen that owned them would
 * make every other screen import it.
 */
fun statusLabel(status: ListStatus): String = when (status) {
    ListStatus.WATCHING -> "Смотрю"
    ListStatus.PLANNED -> "В планах"
    ListStatus.COMPLETED -> "Завершено"
    ListStatus.ON_HOLD -> "Отложено"
    ListStatus.DROPPED -> "Брошено"
    ListStatus.REWATCHING -> "Пересматриваю"
}
