import { availableEpisodes } from "./models";
import type { EpisodeProgress, LibraryEntry } from "./models";

// Share of an episode that counts as watched, the default on both apps.
export const DEFAULT_THRESHOLD = 0.9;

// A minute in is watching whatever the length; a fiftieth covers three-minute shorts.
const STARTED_MS = 60_000;
const STARTED_FRACTION = 0.02;
// A strip under 1 % says nothing a viewer can read.
const MIN_STRIP = 0.01;

export function progressFraction(p: EpisodeProgress): number {
  if (p.durationMs <= 0) return 0;
  return Math.min(1, Math.max(0, p.positionMs / p.durationMs));
}

// Below both bars is a mis-tap, and a mis-tap must never become the episode to continue.
export function isStarted(p: EpisodeProgress): boolean {
  return p.positionMs >= STARTED_MS || (p.durationMs > 0 && p.positionMs / p.durationMs >= STARTED_FRACTION);
}

export function isFinished(p: EpisodeProgress, threshold: number): boolean {
  return progressFraction(p) >= threshold;
}

export interface ContinueTarget {
  episode: number;
  // 0 when the episode starts from the top; only a position earns «Продолжить с m:ss».
  positionMs: number;
  // The whole show is behind the viewer and episode 1 is a rewatch.
  rewatch: boolean;
}

// Port of android domain/playback/ContinueTarget.of. `progress` holds this title's rows only.
export function continueTarget(
  entry: LibraryEntry,
  progress: readonly EpisodeProgress[],
  threshold: number,
): ContinueTarget {
  const { anime, rate } = entry;
  const counted = rate.episodes;
  const aired = availableEpisodes(anime);
  const announced = anime.episodes;
  const started = progress.filter(isStarted);

  // Resume the highest started, unfinished episode that has aired and Shikimori has not counted.
  let resume: EpisodeProgress | undefined;
  for (const row of started) {
    const inRange = row.episode > counted && row.episode <= aired;
    if (inRange && !isFinished(row, threshold) && (resume === undefined || row.episode > resume.episode)) {
      resume = row;
    }
  }
  if (resume !== undefined) return { episode: resume.episode, positionMs: resume.positionMs, rewatch: false };

  // Step over episodes finished here that Shikimori has not heard about, never across a gap.
  // A rewatcher's finished rows are from the last time round, so they get no walk.
  const finishedHere = new Set<number>();
  if (rate.status !== "rewatching") {
    for (const row of started) {
      if (isFinished(row, threshold)) finishedHere.add(row.episode);
    }
  }
  let next = counted + 1;
  while (finishedHere.has(next)) next += 1;

  // Not clamped to aired: an unaired target is how the button says «Ждём 9 серию».
  // A released show of unknown length has still ended (LibraryEntry.continueTarget passes it).
  const finishedAiring = anime.status === "released";
  const runEnded = (announced > 0 && aired >= announced) || (finishedAiring && aired > 0);
  if (runEnded && next > Math.max(announced, aired)) return { episode: 1, positionMs: 0, rewatch: true };
  return { episode: next, positionMs: 0, rewatch: false };
}

export function progressAt(progress: readonly EpisodeProgress[], episode: number): EpisodeProgress | undefined {
  return progress.find((row) => row.episode === episode);
}

// Strip width for one episode, or null when it is counted, unopened, mis-tapped, finished or under 1 %.
export function episodeFraction(
  entry: LibraryEntry,
  progress: readonly EpisodeProgress[],
  episode: number,
  threshold: number,
): number | null {
  if (episode <= entry.rate.episodes) return null;
  const row = progressAt(progress, episode);
  if (row === undefined || !isStarted(row) || isFinished(row, threshold)) return null;
  const fraction = progressFraction(row);
  return fraction >= MIN_STRIP ? fraction : null;
}

// When the title was last really watched. Mis-taps are ignored so they cannot reorder «Продолжить».
export function lastWatchedAt(progress: readonly EpisodeProgress[]): number | null {
  let latest: number | null = null;
  for (const row of progress) {
    if (isStarted(row) && (latest === null || row.updatedAt > latest)) latest = row.updatedAt;
  }
  return latest;
}
