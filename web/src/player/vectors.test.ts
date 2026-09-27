// The shared player-rule vectors (shared/src/commonTest/resources/playback-vectors.json), the same
// file PlaybackVectorsTest runs on Android's rules, run through the web's own implementation. Every
// case is named by its group and name when it fails. `oftenChosen` is not run: the web draws no
// «часто выбираете» mark in its dub menu, so it has no such rule.
import { describe, expect, it } from "vitest";
import vectors from "../../../shared/src/commonTest/resources/playback-vectors.json";
import { availableEpisodes, type AiringStatus, type Anime, type EpisodeProgress } from "../domain/models";
import {
  DEFAULT_THRESHOLD,
  STARTED_FRACTION,
  STARTED_MS,
  isFinished,
  isStarted,
  offerCompletion,
} from "../domain/progress";
import type { Translation } from "./kodik";
import { usageOf } from "./memory";
import {
  COUNTDOWN_S,
  DEFAULT_STUDIOS,
  ENDING_WITHIN_MS,
  ENDING_ZONE_MS,
  INTERVAL_MAX_MS,
  INTERVAL_MIN_MS,
  OPENING_WITHIN_MS,
  SEEK_SETTLE_MS,
  SKIP_OFFER_MS,
  acceptMarks,
  carriesEpisode,
  countdown,
  endingDue,
  endingSkipDue,
  hasNextEpisode,
  insideEnding,
  lacksEpisode,
  rankTranslations,
  resumeFrom,
  skipOffer,
  substitutionNotice,
  substitutionOrder,
  type Interval,
  type SkipMarks,
} from "./rules";

interface VectorTrack {
  id: number;
  title: string;
  kind: string;
  episodes: number | null;
}

interface Named {
  name: string;
}

function track(t: VectorTrack): Translation {
  return { id: t.id, title: t.title, type: t.kind === "subtitles" ? "subtitles" : "voice", episodesCount: t.episodes };
}

function usage(map: Record<string, number | undefined>): Map<number, number> {
  return new Map(Object.entries(map).map(([id, count]) => [Number(id), count ?? 0]));
}

/** Answers as positions in the given list, the way the vectors state them. */
function positions(listed: readonly Translation[], ranked: readonly Translation[]): number[] {
  return ranked.map((t) => listed.indexOf(t));
}

function marks(c: { opening: Interval | null; ending: Interval | null }): SkipMarks {
  return { opening: c.opening, ending: c.ending };
}

function progress(positionMs: number, durationMs: number): EpisodeProgress {
  return { animeId: 1, episode: 1, positionMs, durationMs, updatedAt: 0 };
}

function anime(status: AiringStatus, episodes: number, episodesAired: number): Anime {
  return {
    id: 1, title: "", originalTitle: "", posterUrl: null, backdropUrl: null, status, episodes, episodesAired,
    year: null, score: null, kind: null, studios: [], description: null, nextEpisodeAt: null,
  };
}

/** One `it` per case, so a failure names the case. */
function each<T extends Named>(group: string, cases: readonly T[], run: (c: T) => void): void {
  describe(group, () => {
    it("has cases", () => expect(cases.length).toBeGreaterThan(0));
    for (const c of cases) it(c.name, () => run(c));
  });
}

describe("constants", () => {
  it("match the shared ones", () => {
    const c = vectors.constants;
    expect(DEFAULT_STUDIOS).toEqual(c.defaultStudios);
    expect(STARTED_MS).toBe(c.startedMs);
    expect(STARTED_FRACTION).toBe(c.startedFraction);
    expect(DEFAULT_THRESHOLD).toBe(c.defaultWatchedThreshold);
    expect(ENDING_ZONE_MS).toBe(c.nextEpisodeLeadMs);
    expect(COUNTDOWN_S).toBe(c.autoplayCountdownSec);
    expect(SKIP_OFFER_MS).toBe(c.skipButtonWindowMs);
    expect(OPENING_WITHIN_MS).toBe(c.openingStartsWithinMs);
    expect(ENDING_WITHIN_MS).toBe(c.endingEndsWithinMs);
    expect(INTERVAL_MIN_MS).toBe(c.skipMinLengthMs);
    expect(INTERVAL_MAX_MS).toBe(c.skipMaxLengthMs);
    expect(SEEK_SETTLE_MS).toBe(c.seekSettleMs);
  });
});

each("translationOrder", vectors.translationOrder, (c) => {
  const listed = c.tracks.map(track);
  const ranked = rankTranslations(listed, { remembered: c.rememberedId, usage: usage(c.usage), preferred: c.preferred });
  expect(positions(listed, ranked)).toEqual(c.expected);
});

each("substitutionOrder", vectors.substitutionOrder, (c) => {
  const listed = c.tracks.map(track);
  const ranked = rankTranslations(listed, { remembered: c.rememberedId, usage: usage(c.usage), preferred: c.preferred });
  expect(positions(listed, substitutionOrder(ranked, c.chosenId))).toEqual(c.expected);
});

each("carriesEpisode", vectors.carriesEpisode, (c) => {
  const listed = c.listedEpisodes === null ? null : new Set(c.listedEpisodes);
  expect(carriesEpisode(c.episode, listed, c.season, c.episodesCount)).toBe(c.expected);
  // The web's own question: first season, no list read, so only the count cases reach it.
  if (listed === null && c.season === 1) {
    const candidate: Translation = { id: 1, title: "", type: "voice", episodesCount: c.episodesCount };
    expect(lacksEpisode(candidate, c.episode)).toBe(c.expected === false);
  }
});

each("translationUsage", vectors.translationUsage, (c) => {
  expect(Object.fromEntries(usageOf(c.remembered))).toEqual(
    Object.fromEntries(Object.entries(c.expected).map(([id, n]) => [Number(id), n])),
  );
});

each("substitutionNotice", vectors.substitutionNotice, (c) => {
  expect(substitutionNotice(c.askedFor, c.episode, c.playing)).toBe(c.expected);
});

each("started", vectors.started, (c) => {
  expect(isStarted(progress(c.positionMs, c.durationMs))).toBe(c.expected);
});

each("watched", vectors.watched, (c) => {
  expect(isFinished(progress(c.positionMs, c.durationMs), c.threshold)).toBe(c.expected);
});

each("unfinished", vectors.unfinished, (c) => {
  expect(!isFinished(progress(c.positionMs, c.durationMs), c.threshold)).toBe(c.expected);
});

each("resumePosition", vectors.resumePosition, (c) => {
  expect(resumeFrom(progress(c.positionMs, c.durationMs), c.threshold)).toBe(c.expected);
});

each("availableEpisodes", vectors.availableEpisodes, (c) => {
  expect(availableEpisodes(anime(c.status as AiringStatus, c.episodes, c.episodesAired))).toBe(c.expected);
});

each("hasNextEpisode", vectors.hasNextEpisode, (c) => {
  // An ongoing show makes exactly what aired available.
  expect(hasNextEpisode(anime("ongoing", 0, c.availableEpisodes), c.episode)).toBe(c.expected);
});

each("nextEpisodeDue", vectors.nextEpisodeDue, (c) => {
  expect(endingDue(c.positionMs, c.durationMs, c.ended)).toBe(c.expected);
});

each("countdownSeconds", vectors.countdownSeconds, (c) => {
  // The shared rule leaves autoplay, a cancel and a next episode to the caller; here all say go.
  const left = countdown({
    positionMs: c.positionMs,
    durationMs: c.durationMs,
    ended: c.ended,
    autoplay: true,
    cancelled: false,
    hasNext: true,
  });
  expect(left).toBe(c.expected);
});

each("skipAccept", vectors.skipAccept, (c) => {
  expect(acceptMarks(marks(c), c.durationMs)).toEqual(marks(c.expected));
});

each("skipOffer", vectors.skipOffer, (c) => {
  const kind = skipOffer(marks(c), c.positionMs, c.durationMs);
  const offered = kind === null ? null : { kind, ...(kind === "opening" ? c.opening : c.ending) };
  expect(offered).toEqual(c.expected);
});

each("endingSkipDue", vectors.endingSkipDue, (c) => {
  expect(endingSkipDue(marks(c), c.positionMs, c.durationMs)).toBe(c.expected);
});

each("insideEnding", vectors.insideEnding, (c) => {
  expect(insideEnding(marks(c), c.positionMs, c.durationMs)).toBe(c.expected);
});

each("offerCompletion", vectors.offerCompletion, (c) => {
  expect(offerCompletion(c.episode, c.announcedEpisodes, c.nextEpisodeAtMs, c.nowMs)).toBe(c.expected);
});
