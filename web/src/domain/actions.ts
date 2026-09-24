import { episodeBadge, formatTime, pluralEpisodes, relativeDay, remainingLine } from "./format";
import { availableEpisodes } from "./models";
import type { Anime, EpisodeProgress, LibraryEntry, UserRate } from "./models";
import { continueTarget, isFinished, progressAt } from "./progress";

export interface PrimaryAction {
  label: string;
  episode: number;
  positionMs: number;
  enabled: boolean;
}

export type FeedKind = "continue" | "new" | "next" | "upcoming" | "planned";

// No episode number: a rewatch offers the show, not «1 серию».
const REWATCH = "Пересмотреть";

// The watch button (android ui/common/design/Format.kt primaryAction). `anime` wins over
// entry.anime because the title screen holds fresher details; only the rate comes from the entry.
export function primaryAction(
  entry: LibraryEntry | null,
  anime: Anime,
  progress: readonly EpisodeProgress[],
  threshold: number,
  now: number,
): PrimaryAction {
  // A title in no list behaves as a rate with nothing counted.
  const rate: UserRate = entry?.rate ?? { id: 0, animeId: anime.id, status: "watching", episodes: 0, updatedAt: 0 };
  const target = continueTarget({ anime, rate }, progress, threshold);
  const next = target.episode;
  if (target.positionMs > 0) {
    return { label: `Продолжить с ${formatTime(target.positionMs)}`, episode: next, positionMs: target.positionMs, enabled: true };
  }

  const aired = availableEpisodes(anime);
  if (next <= aired) {
    if (target.rewatch) return { label: REWATCH, episode: next, positionMs: 0, enabled: true };
    // «Продолжить» promises an episode still ahead; one already finished here is watched again.
    const row = progressAt(progress, next);
    const seen = next <= rate.episodes || (row !== undefined && isFinished(row, threshold));
    const label = next <= 1 || seen ? `Смотреть ${next} серию` : `Продолжить ${next} серию`;
    return { label, episode: next, positionMs: 0, enabled: true };
  }
  // Disabled: the episode is named in the label and kept for callers, but nothing plays.
  return { label: waitingLabel(anime, next, now), episode: next, positionMs: 0, enabled: false };
}

// What to say about an episode that cannot start yet. A date already past is the catalogue
// lagging, so it is not repeated as a promise.
export function waitingLabel(anime: Anime, episode: number, now: number): string {
  const date = anime.nextEpisodeAt;
  if (date !== null && date >= now) return `${episode} серия выйдет ${relativeDay(date, now)}`;
  if (availableEpisodes(anime) <= 0) return "Ещё не вышло";
  return `Ждём ${episode} серию`;
}

// Status line of a feed item (Format.kt episodeLine). Facts join with a comma, never a middle dot.
export function episodeLine(
  kind: FeedKind,
  entry: LibraryEntry,
  episode: number,
  progress: readonly EpisodeProgress[],
  now: number,
): string {
  const anime = entry.anime;
  switch (kind) {
    case "continue": {
      const row = progressAt(progress, episode);
      const left = row === undefined ? null : remainingLine(row.positionMs, row.durationMs);
      return left === null ? episodeBadge(episode) : `${episodeBadge(episode)}, ${left}`;
    }
    case "new":
      return `Вышла ${episode} серия`;
    case "next":
      return episodeBadge(episode);
    case "upcoming": {
      const day = anime.nextEpisodeAt === null ? "скоро" : relativeDay(anime.nextEpisodeAt, now);
      return `${episode} серия ${day}`;
    }
    case "planned": {
      const season = anime.episodes > 0 ? anime.episodes : availableEpisodes(anime);
      return season > 0 ? `В планах, ${pluralEpisodes(season)}` : "В планах";
    }
  }
}
