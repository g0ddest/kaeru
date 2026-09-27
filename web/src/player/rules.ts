import { availableEpisodes } from "../domain/models";
import type { Anime, EpisodeProgress } from "../domain/models";
import { isFinished, isStarted } from "../domain/progress";
import type { Translation } from "./kodik";

// The arithmetic of an episode (EpisodeQueue.kt, SkipMarks.kt). All of it runs on media position, so
// a paused player holds every countdown and offer where it is.

/** One step of ←/→ and of the seek buttons. */
export const SEEK_STEP_MS = 10_000;
/** «+85 с»: one opening, give or take. */
export const JUMP_MS = 85_000;
/** How much the position has to move before it is written down again. */
export const SAVE_EVERY_MS = 5_000;
/** The last stretch of an episode, where the next one is offered. */
export const ENDING_ZONE_MS = 30_000;
/** How long the viewer has to say no before the next episode starts by itself. */
export const COUNTDOWN_S = 10;
/** How long a skip button stands after playback walks into what it skips. */
export const SKIP_OFFER_MS = 10_000;
/** An opening starts in the first five minutes; anything later is somebody's mistake. */
export const OPENING_WITHIN_MS = 300_000;
/** An ending reaches into the last three minutes. */
export const ENDING_WITHIN_MS = 180_000;
/** Shorter than this is a jingle, longer is a chunk of the episode. */
export const INTERVAL_MIN_MS = 60_000;
export const INTERVAL_MAX_MS = 150_000;
/** How much playing it takes after a seek before a position is the episode's own again. */
export const SEEK_SETTLE_MS = 1_000;
/** Wall-clock, not media: how long the controls stay up without a pointer or a key. */
export const CONTROLS_HIDE_MS = 3_000;

/**
 * Studios that dub most of what Kodik carries, best first: the opening guess for a viewer with no
 * history (TranslationRanker.DEFAULT_STUDIOS, the same list on iOS).
 */
export const DEFAULT_STUDIOS: readonly string[] = [
  "AniLibria", "AniDUB", "Crunchyroll", "Amazing Dubbing", "AniBaza", "AniMaunt", "JAM", "Dream Cast", "SHIZA Project",
];

// A title no studio matches sits behind every one that does; a blank studio matches nothing.
function studioRank(title: string, studios: readonly string[]): number {
  const lower = title.toLowerCase();
  const index = studios.findIndex((studio) => studio.trim() !== "" && lower.includes(studio.toLowerCase()));
  return index >= 0 ? index : studios.length;
}

/**
 * Which dub a viewer most likely wants, best first: the head is what plays, the whole list is the
 * dub menu (TranslationRanker.kt). The web has no hand-typed studio list of its own, so `preferred`
 * is empty in the app; the shared vectors fill it. Stable, so tracks the rules cannot separate keep
 * Kodik's own order.
 */
export function rankTranslations(
  tracks: readonly Translation[],
  ctx: { remembered: number | null; usage: ReadonlyMap<number, number>; preferred?: readonly string[] },
): Translation[] {
  const preferred = ctx.preferred ?? [];
  const key = (track: Translation): number[] => [
    track.id === ctx.remembered ? 0 : 1,
    // A list somebody typed by hand beats every guess below it.
    studioRank(track.title, preferred),
    // A track nobody has watched counts as zero, behind every track that has been watched at all.
    -(ctx.usage.get(track.id) ?? 0),
    studioRank(track.title, DEFAULT_STUDIOS),
    track.type === "voice" ? 0 : 1,
    // A half-finished dub strands the viewer mid-season; subtitles are not told apart this way.
    track.type === "voice" ? -(track.episodesCount ?? 0) : 0,
  ];
  return tracks
    .map((track) => ({ track, keys: key(track) }))
    .sort((a, b) => {
      for (let i = 0; i < a.keys.length; i += 1) {
        const diff = (a.keys[i] ?? 0) - (b.keys[i] ?? 0);
        if (diff !== 0) return diff;
      }
      return 0;
    })
    .map(({ track }) => track);
}

/**
 * Where to look for a stand-in when `chosenId` lacks an episode (TranslationRanker.substitutionOrder):
 * the ranking with the chosen track taken out. The caller still skips any that lacks the episode.
 */
export function substitutionOrder(ranked: readonly Translation[], chosenId: number): Translation[] {
  return ranked.filter((track) => track.id !== chosenId);
}

/**
 * Whether a track carries `episode`, as far as anything already read can say; null when nothing can
 * (TranslationRanker.carriesEpisode). Its own list of episodes answers; failing that, the count, but
 * only against the first season's numbering.
 */
export function carriesEpisode(
  episode: number,
  listedEpisodes: ReadonlySet<number> | null,
  season: number,
  episodesCount: number | null,
): boolean | null {
  if (listedEpisodes !== null) return listedEpisodes.has(episode);
  if (season !== 1) return null;
  return episodesCount === null ? null : episode <= episodesCount;
}

/**
 * Known not to carry the episode. The web never reads a track's own episode list and always plays
 * the first season's numbering, so only Kodik's count can say so; an unknown is not a lack. A count
 * of 0 is taken as unknown: the worker turns an empty `data-episode-count` into 0 where Android's
 * parser reads null.
 */
export function lacksEpisode(track: Translation, episode: number): boolean {
  const count = track.episodesCount !== null && track.episodesCount > 0 ? track.episodesCount : null;
  return carriesEpisode(episode, null, 1, count) === false;
}

/** What the player says when another track stood in for the one asked for. */
export function substitutionNotice(askedFor: string, episode: number, playing: string): string {
  return `В озвучке ${askedFor} серии ${episode} нет — включена ${playing}`;
}

/**
 * Where to pick this episode up (PlayerViewModel.resumeFrom). A mis-tap is no place to drop the
 * viewer, and an episode watched to the threshold starts over rather than on its last frame.
 */
export function resumeFrom(row: EpisodeProgress | undefined, threshold: number): number {
  if (row === undefined || !isStarted(row) || isFinished(row, threshold)) return 0;
  return row.positionMs;
}

/**
 * The quality to open with: the viewer's own when this stream carries it (Kodik lists different
 * heights per episode), otherwise the best on offer.
 */
export function startQuality(offered: readonly number[], preferred: number | null): number {
  if (preferred !== null && offered.includes(preferred)) return preferred;
  return offered.reduce((best, quality) => Math.max(best, quality), 0);
}

/** An unknown or announced title has nothing after any episode: no countdown, no «Следующая серия». */
export function hasNextEpisode(anime: Anime, episode: number): boolean {
  const aired = availableEpisodes(anime);
  return aired > 0 && episode < aired;
}

/** Close enough to the end to offer the next episode, or wait for one. */
export function endingDue(positionMs: number, durationMs: number, ended: boolean): boolean {
  return ended || (durationMs > 0 && durationMs - positionMs <= ENDING_ZONE_MS);
}

/**
 * Seconds until the next episode starts by itself, or null when nothing is counting down. Rounded
 * up, so the card opens on 10 and the next episode starts on 0.
 */
export function countdown(s: {
  positionMs: number;
  durationMs: number;
  ended: boolean;
  autoplay: boolean;
  cancelled: boolean;
  hasNext: boolean;
}): number | null {
  if (!s.autoplay || s.cancelled || !s.hasNext) return null;
  if (s.ended) return 0;
  if (s.durationMs <= 0) return null;
  const remaining = Math.max(0, s.durationMs - s.positionMs);
  if (remaining > COUNTDOWN_S * 1_000) return null;
  return Math.min(COUNTDOWN_S, Math.max(0, Math.ceil(remaining / 1_000)));
}

/** One stretch of an episode worth stepping over, in ms from its start. */
export interface Interval {
  startMs: number;
  endMs: number;
}

/** What is known about one file's opening and ending. Either can be missing, and usually is. */
export interface SkipMarks {
  opening: Interval | null;
  ending: Interval | null;
}

/** Nothing is known: no button is drawn and nothing skips itself. */
export const NO_MARKS: SkipMarks = Object.freeze({ opening: null, ending: null });

// An interval that runs past the end of this file was marked for a file of another length.
function plausible(interval: Interval, durationMs: number): boolean {
  const length = interval.endMs - interval.startMs;
  return (
    durationMs > 0 &&
    interval.startMs >= 0 &&
    interval.endMs <= durationMs &&
    length >= INTERVAL_MIN_MS &&
    length <= INTERVAL_MAX_MS
  );
}

function acceptedOpening(interval: Interval | null, durationMs: number): Interval | null {
  return interval !== null && plausible(interval, durationMs) && interval.startMs <= OPENING_WITHIN_MS ? interval : null;
}

function acceptedEnding(interval: Interval | null, durationMs: number): Interval | null {
  return interval !== null && plausible(interval, durationMs) && interval.endMs >= durationMs - ENDING_WITHIN_MS
    ? interval
    : null;
}

/** Both halves through the same sieve (SkipRules.accept): what does not survive it is simply not there. */
export function acceptMarks(marks: SkipMarks, durationMs: number): SkipMarks {
  return { opening: acceptedOpening(marks.opening, durationMs), ending: acceptedEnding(marks.ending, durationMs) };
}

/**
 * Common sense over community marks (SkipRules.accept): the first plausible interval of each kind,
 * anything else dropped in silence, so a wrong mark looks exactly like no mark at all.
 */
export function plausibleMarks(
  raw: readonly { kind: "op" | "ed"; startMs: number; endMs: number }[],
  durationMs: number,
): SkipMarks {
  let opening: Interval | null = null;
  let ending: Interval | null = null;
  for (const { kind, startMs, endMs } of raw) {
    const interval = { startMs, endMs };
    if (kind === "op" && opening === null) opening = acceptedOpening(interval, durationMs);
    if (kind === "ed" && ending === null) ending = acceptedEnding(interval, durationMs);
  }
  return { opening, ending };
}

function offered(interval: Interval | null, positionMs: number): boolean {
  return interval !== null && positionMs >= interval.startMs && positionMs < interval.startMs + SKIP_OFFER_MS;
}

/**
 * The skip button for this position: ten seconds of played video from the start of what it skips
 * (SkipRules.offer). The marks are sieved here too, so a remembered answer is held to this length.
 */
export function skipOffer(marks: SkipMarks, positionMs: number, durationMs: number): "opening" | "ending" | null {
  const accepted = acceptMarks(marks, durationMs);
  if (offered(accepted.opening, positionMs)) return "opening";
  if (offered(accepted.ending, positionMs)) return "ending";
  return null;
}

/** Ten seconds into the ending and still inside it (SkipRules.endingSkipDue). */
export function endingSkipDue(marks: SkipMarks, positionMs: number, durationMs: number): boolean {
  const ending = acceptedEnding(marks.ending, durationMs);
  return ending !== null && positionMs >= ending.startMs + SKIP_OFFER_MS && positionMs < ending.endMs;
}

/** Anywhere inside the ending (SkipRules.insideEnding). */
export function insideEnding(marks: SkipMarks, positionMs: number, durationMs: number): boolean {
  const ending = acceptedEnding(marks.ending, durationMs);
  return ending !== null && positionMs >= ending.startMs && positionMs < ending.endMs;
}

/**
 * «Пропускать эндинг»: ten seconds into the ending and still inside it. The tick before has to be
 * inside it too and a second has to have played since a seek, so dragging the bar into the ending
 * is never taken for playing into it (PlaybackController.skipEndingIfDue).
 */
export function shouldAutoSkip(s: {
  enabled: boolean;
  done: boolean;
  marks: SkipMarks;
  durationMs: number;
  positionMs: number;
  previousMs: number;
  playedSinceSeekMs: number;
}): boolean {
  if (!s.enabled || s.done) return false;
  return (
    endingSkipDue(s.marks, s.positionMs, s.durationMs) &&
    insideEnding(s.marks, s.previousMs, s.durationMs) &&
    s.playedSinceSeekMs >= SEEK_SETTLE_MS
  );
}
