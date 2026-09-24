import type { FeedKind } from "./actions";
import { episodeBadge, pluralEpisodes, relativeDay, remainingLine } from "./format";
import { availableEpisodes } from "./models";
import type { Anime, EpisodeProgress, LibraryEntry } from "./models";
import { continueTarget, episodeFraction, lastWatchedAt, progressAt } from "./progress";
import type { ContinueTarget } from "./progress";

/** «Скоро» looks one week ahead, open at both ends (HomeFeedBuilder.upcomingWindow). */
export const UPCOMING_WINDOW_MS = 7 * 24 * 60 * 60 * 1000;

const NEW_EPISODES = "Новые серии";
const CONTINUE = "Продолжить";
const NEXT_UP = "Дальше по списку";
const UPCOMING = "Скоро";
const PLANNED = "В планах";
/** What an upcoming card says when the catalogue has no date for the next episode. */
const SOON = "скоро";

export interface FeedItem {
  entry: LibraryEntry;
  episode: number;
  kind: FeedKind;
}

export interface HomeFeed {
  top: FeedItem | null;
  newEpisodes: FeedItem[];
  continueWatching: FeedItem[];
  nextUp: FeedItem[];
  upcoming: FeedItem[];
  planned: FeedItem[];
}

/** One card with every word already decided, so what it says can be read in a test. */
export interface Card {
  key: string;
  animeId: number;
  title: string;
  posterUrl: string | null;
  badge: string | null;
  subtitle: string | null;
  progress: number | null;
}

export interface Row {
  title: string;
  cards: Card[];
}

/** Nothing worth drawing a home screen around («Скачано» does not exist on the web). */
export function isFeedEmpty(feed: HomeFeed): boolean {
  return feed.top === null && feed.planned.length === 0 && feed.upcoming.length === 0;
}

interface Targeted {
  entry: LibraryEntry;
  progress: readonly EpisodeProgress[];
  target: ContinueTarget;
}

function isActive(entry: LibraryEntry): boolean {
  return entry.rate.status === "watching" || entry.rate.status === "rewatching";
}

// Array.prototype.sort is stable, so equal keys keep input order like Kotlin's sortedByDescending.
function sortedDescending<T>(items: readonly T[], key: (item: T) => number): T[] {
  return [...items].sort((a, b) => key(b) - key(a));
}

function feedItem(entry: LibraryEntry, episode: number, kind: FeedKind): FeedItem {
  return { entry, episode, kind };
}

export function buildFeed(
  entries: readonly LibraryEntry[],
  progressOf: (animeId: number) => readonly EpisodeProgress[],
  now: number,
  threshold: number,
): HomeFeed {
  // One target per title, shared by every row, so no two rows can disagree about the episode.
  const targeted: Targeted[] = entries.map((entry) => {
    const progress = progressOf(entry.anime.id);
    return { entry, progress, target: continueTarget(entry, progress, threshold) };
  });
  const active = targeted.filter((t) => isActive(t.entry));

  // A local position puts a title here whatever its list status, except «Завершено»: the viewer
  // said they are done, and the title screen writes that status without touching the count.
  const continueWatching = sortedDescending(
    targeted.filter((t) => t.target.positionMs > 0 && t.entry.rate.status !== "completed"),
    // When the title was last really watched; lastWatchedAt ignores mis-taps.
    (t) => lastWatchedAt(t.progress) ?? 0,
  ).map((t) => feedItem(t.entry, t.target.episode, "continue"));
  const inProgress = new Set(continueWatching.map((item) => item.entry.anime.id));

  const newEpisodes = sortedDescending(
    active.filter(
      (t) =>
        t.entry.anime.status === "ongoing" &&
        !t.target.rewatch &&
        t.target.episode <= t.entry.anime.episodesAired &&
        !inProgress.has(t.entry.anime.id),
    ),
    (t) => t.entry.anime.nextEpisodeAt ?? t.entry.rate.updatedAt,
  ).map((t) => feedItem(t.entry, t.target.episode, "new"));

  // A show with every episode behind the viewer has no «next»; the title screen offers a rewatch.
  const nextUp = sortedDescending(
    active.filter(
      (t) =>
        t.entry.anime.status !== "ongoing" &&
        !t.target.rewatch &&
        t.target.episode <= availableEpisodes(t.entry.anime) &&
        !inProgress.has(t.entry.anime.id),
    ),
    (t) => t.entry.rate.updatedAt,
  ).map((t) => feedItem(t.entry, t.target.episode, "next"));

  // In-progress titles stay: a title can be both continued and waiting for its next episode.
  const horizon = now + UPCOMING_WINDOW_MS;
  const upcoming = active
    .filter((t) => {
      const next = t.entry.anime.nextEpisodeAt;
      return t.entry.anime.status === "ongoing" && next !== null && next > now && next < horizon;
    })
    .sort((a, b) => (a.entry.anime.nextEpisodeAt ?? 0) - (b.entry.anime.nextEpisodeAt ?? 0))
    .map((t) => feedItem(t.entry, t.entry.anime.episodesAired + 1, "upcoming"));

  // One title, one card: a planned title already being continued is not offered again.
  const planned = sortedDescending(
    entries.filter((entry) => entry.rate.status === "planned" && !inProgress.has(entry.anime.id)),
    (entry) => entry.rate.updatedAt,
  ).map((entry) => feedItem(entry, 1, "planned"));

  const top = continueWatching[0] ?? newEpisodes[0] ?? nextUp[0] ?? null;
  return { top, newEpisodes, continueWatching, nextUp, upcoming, planned };
}

export function feedRows(
  feed: HomeFeed,
  progressOf: (animeId: number) => readonly EpisodeProgress[],
  now: number,
  threshold: number,
): Row[] {
  const rows: Array<[string, FeedItem[]]> = [
    [NEW_EPISODES, feed.newEpisodes],
    [CONTINUE, feed.continueWatching],
    [NEXT_UP, feed.nextUp],
    [UPCOMING, feed.upcoming],
    [PLANNED, feed.planned],
  ];
  // A heading with nothing under it says nothing, so empty rows are never built.
  return rows
    .filter(([, items]) => items.length > 0)
    .map(([title, items]) => ({
      title,
      cards: items.map((item) => feedCard(item, progressOf(item.entry.anime.id), now, threshold)),
    }));
}

/** A catalogue card: artwork, name and one fact about the size of the show. */
export function catalogueCard(anime: Anime): Card {
  const available = availableEpisodes(anime);
  return card(anime, null, available > 0 ? pluralEpisodes(available) : null, null);
}

// A card says only what its row cannot; the strip and the badge describe the same episode.
function feedCard(
  item: FeedItem,
  progress: readonly EpisodeProgress[],
  now: number,
  threshold: number,
): Card {
  const { entry, episode } = item;
  switch (item.kind) {
    case "new":
    case "next":
      return card(entry.anime, episodeBadge(episode), null, null);
    case "continue": {
      const row = progressAt(progress, episode);
      return card(
        entry.anime,
        episodeBadge(episode),
        row ? remainingLine(row.positionMs, row.durationMs) : null,
        episodeFraction(entry, progress, episode, threshold),
      );
    }
    case "upcoming": {
      const at = entry.anime.nextEpisodeAt;
      return card(entry.anime, episodeBadge(episode), at === null ? SOON : relativeDay(at, now), null);
    }
    case "planned":
      return card(entry.anime, null, seasonLength(entry.anime), null);
  }
}

function card(anime: Anime, badge: string | null, subtitle: string | null, progress: number | null): Card {
  return {
    key: badge === null ? `${anime.id}` : `${anime.id}:${badge}`,
    animeId: anime.id,
    title: anime.title,
    posterUrl: anime.posterUrl,
    badge,
    subtitle,
    progress,
  };
}

/** «24 серии» for a planned title, or null while the catalogue does not know the length. */
function seasonLength(anime: Anime): string | null {
  const n = anime.episodes > 0 ? anime.episodes : availableEpisodes(anime);
  return n > 0 ? pluralEpisodes(n) : null;
}
