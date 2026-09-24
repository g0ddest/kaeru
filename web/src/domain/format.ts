import type { AiringStatus, Anime, ListStatus } from "./models";

const MINUTE_MS = 60_000;
const HOUR_MS = 3_600_000;
const DAY_MS = 86_400_000;

// Russian counts three ways; 11–14 are checked before the last digit (Format.kt plural).
export function plural(n: number, one: string, few: string, many: string): string {
  const abs = Math.abs(n);
  const lastTwo = abs % 100;
  if (lastTwo >= 11 && lastTwo <= 14) return many;
  const last = abs % 10;
  if (last === 1) return one;
  if (last >= 2 && last <= 4) return few;
  return many;
}

export function pluralEpisodes(n: number): string {
  return `${n} ${plural(n, "серия", "серии", "серий")}`;
}

// «Показать ещё 21 серию»: the verb puts the noun in the accusative.
export function pluralEpisodesAccusative(n: number): string {
  return `${n} ${plural(n, "серию", "серии", "серий")}`;
}

// "1:02:34" from an hour up, "14:20" below it; zero, negative and NaN read "0:00".
export function formatTime(ms: number): string {
  if (!(ms > 0)) return "0:00";
  const total = Math.floor(ms / 1000);
  const seconds = String(total % 60).padStart(2, "0");
  const minutes = Math.floor(total / 60) % 60;
  const hours = Math.floor(total / 3600);
  if (hours > 0) return `${hours}:${String(minutes).padStart(2, "0")}:${seconds}`;
  return `${minutes}:${seconds}`;
}

// Minutes are floored so «14 мин» always means at least fourteen (Format.kt remainingLine).
export function remainingLine(positionMs: number, durationMs: number): string | null {
  if (durationMs <= 0) return null;
  const left = durationMs - positionMs;
  if (left <= 0) return null;
  if (left < MINUTE_MS) return "осталось меньше минуты";
  if (left < HOUR_MS) return `осталось ${Math.floor(left / MINUTE_MS)} мин`;
  const hours = Math.floor(left / HOUR_MS);
  const minutes = Math.floor((left % HOUR_MS) / MINUTE_MS);
  return minutes === 0 ? `осталось ${hours} ч` : `осталось ${hours} ч ${minutes} мин`;
}

// Index of the local calendar day. Date.UTC over the local fields keeps 23 h and 25 h days whole.
function localDay(ms: number): number {
  const date = new Date(ms);
  return Date.UTC(date.getFullYear(), date.getMonth(), date.getDate()) / DAY_MS;
}

// Whole calendar days as the viewer counts them: 01:15 tomorrow is «завтра» though 4 h away.
export function relativeDay(target: number, now: number): string {
  const days = localDay(target) - localDay(now);
  if (days === 0) return "сегодня";
  if (days === 1) return "завтра";
  if (days === -1) return "вчера";
  if (days > 1) return `через ${days} ${plural(days, "день", "дня", "дней")}`;
  return `${-days} ${plural(days, "день", "дня", "дней")} назад`;
}

export function statusLabel(s: ListStatus): string {
  switch (s) {
    case "watching":
      return "Смотрю";
    case "planned":
      return "В планах";
    case "completed":
      return "Завершено";
    case "rewatching":
      return "Пересматриваю";
    case "on_hold":
      return "Отложено";
    case "dropped":
      return "Брошено";
  }
}

export function airingLabel(s: AiringStatus): string {
  switch (s) {
    case "ongoing":
      return "Онгоинг";
    case "released":
      return "Вышло";
    case "anons":
      return "Анонс";
  }
}

// iOS DetailView.kindTitle; unknown kinds such as "cm" or "pv" are shown upper-cased.
export function kindLabel(kind: string): string {
  switch (kind) {
    case "tv":
      return "Сериал";
    case "movie":
      return "Фильм";
    case "ova":
      return "OVA";
    case "ona":
      return "ONA";
    case "special":
    case "tv_special":
      return "Спецвыпуск";
    case "music":
      return "Музыкальное видео";
    default:
      return kind.toUpperCase();
  }
}

// Shikimori writes scores with a point ("8.62", "9.0"); a whole number keeps its ".0".
function scoreText(score: number): string {
  return Number.isInteger(score) ? score.toFixed(1) : String(score);
}

// One line in the iOS order: status · year · length · score · aired so far · kind · studios.
export function factsLine(anime: Anime): string {
  const parts: string[] = [airingLabel(anime.status)];
  if (anime.year !== null) parts.push(String(anime.year));
  if (anime.episodes > 0) parts.push(`${anime.episodes} эп.`);
  if (anime.score !== null && anime.score > 0) parts.push(`★ ${scoreText(anime.score)}`);
  if (anime.status === "ongoing") parts.push(`вышло ${anime.episodesAired}`);
  if (anime.kind !== null && anime.kind.trim() !== "") parts.push(kindLabel(anime.kind));
  const studios = anime.studios.filter((name) => name.trim() !== "");
  if (studios.length > 0) parts.push(studios.join(", "));
  return parts.join(" · ");
}

// [[Синигами]] is a wiki link: keep the word (iOS dropped it, Android kept the brackets).
const WIKI_LINK = /\[\[([^\]]+)\]\]/g;
// BBCode open and close tags with an optional =value (ShikimoriMappers.kt); inner text stays.
const BB_TAG = /\[\/?[a-z_]+(?:=[^\]]*)?\]/g;
const HTML_TAG = /<[^>]+>/g;

export function cleanDescription(raw: string | null): string | null {
  if (raw === null) return null;
  const text = raw
    .replace(WIKI_LINK, "$1")
    .replace(BB_TAG, "")
    .replace(HTML_TAG, "")
    // Entities after the tags, so "&lt;b&gt;" survives as text; &amp; last, so "&amp;lt;" stays "&lt;".
    .replace(/&quot;/g, '"')
    .replace(/&#39;/g, "'")
    .replace(/&lt;/g, "<")
    .replace(/&gt;/g, ">")
    .replace(/&amp;/g, "&")
    .trim();
  return text === "" ? null : text;
}

// Card badge. It names one episode, so the noun never changes: «21 серия», «22 серия».
export function episodeBadge(n: number): string {
  return `${n} серия`;
}
