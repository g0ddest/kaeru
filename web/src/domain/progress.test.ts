// Vectors from android/src/test/java/app/kaeru/domain/playback/ContinueTargetTest.kt,
// android/src/test/java/app/kaeru/ui/common/details/EpisodeGridTest.kt (strips) and
// android/src/test/java/app/kaeru/domain/feed/HomeFeedBuilderTest.kt (lastWatchedAt).
import { describe, expect, it } from "vitest";
import type { Anime, EpisodeProgress, LibraryEntry, ListStatus } from "./models";
import {
  DEFAULT_THRESHOLD,
  continueTarget,
  episodeFraction,
  isFinished,
  isStarted,
  lastWatchedAt,
  progressAt,
  progressFraction,
} from "./progress";

// Twenty-four minutes, the length of an ordinary episode.
const EPISODE_MS = 1_440_000;
const threshold = 0.9;

function p(episode: number, positionMs: number, durationMs = EPISODE_MS, updatedAt = 0): EpisodeProgress {
  return { animeId: 100, episode, positionMs, durationMs, updatedAt };
}

// Android passes aired, announced and finishedAiring directly; here they come from the anime.
// "released" is used only with announced 0, where availableEpisodes falls back to episodesAired,
// so aired stays exactly what the Android vector says.
function entry(
  watched: number,
  aired: number,
  announced: number,
  opts: { status?: ListStatus; finishedAiring?: boolean } = {},
): LibraryEntry {
  const anime: Anime = {
    id: 100,
    title: "Тест",
    originalTitle: "Test",
    posterUrl: null,
    backdropUrl: null,
    status: opts.finishedAiring === true ? "released" : "ongoing",
    episodes: announced,
    episodesAired: aired,
    year: null,
    score: null,
    kind: null,
    studios: [],
    description: null,
    nextEpisodeAt: null,
  };
  return { anime, rate: { id: 1, animeId: 100, status: opts.status ?? "watching", episodes: watched, updatedAt: 0 } };
}

const resumeAt = (episode: number, positionMs: number) => ({ episode, positionMs, rewatch: false });
const fromTop = (episode: number) => ({ episode, positionMs: 0, rewatch: false });
const REWATCH = { episode: 1, positionMs: 0, rewatch: true };
const allFinished = (count: number) => Array.from({ length: count }, (_, i) => p(i + 1, 1_400_000));

describe("episode progress", () => {
  it("defaults the watched threshold to 90 %", () => {
    expect(DEFAULT_THRESHOLD).toBe(0.9);
  });

  it("measures the fraction clamped to 0..1, and 0 without a duration", () => {
    expect(progressFraction(p(1, 700_000, 1_400_000))).toBe(0.5);
    expect(progressFraction(p(1, 2_000_000))).toBe(1);
    expect(progressFraction(p(1, -5_000))).toBe(0);
    expect(progressFraction(p(1, 120_000, 0))).toBe(0);
  });

  it("counts a minute in as started however long the episode runs", () => {
    expect(isStarted(p(5, 60_000, 7_200_000))).toBe(true);
    expect(isStarted(p(5, 59_999, 7_200_000))).toBe(false);
    expect(isStarted(p(5, 120_000, 0))).toBe(true);
  });

  it("lets a short episode qualify on the share rather than the minute", () => {
    expect(isStarted(p(5, 3_600, 180_000))).toBe(true);
    expect(isStarted(p(5, 3_000, 180_000))).toBe(false);
    expect(isStarted(p(5, 10_000))).toBe(false);
  });

  it("treats reaching the threshold as finishing", () => {
    // 1_296_000 is exactly 90 % of EPISODE_MS.
    expect(isFinished(p(5, 1_296_000), threshold)).toBe(true);
    expect(isFinished(p(5, 1_295_000), threshold)).toBe(false);
    expect(isFinished(p(5, 1_000_000, 0), threshold)).toBe(false);
  });
});

describe("continueTarget", () => {
  it("a mis-tap on an earlier episode does not move the pointer off the one being watched", () => {
    const target = continueTarget(entry(6, 10, 24), [p(7, 2_400_000, 2_880_000), p(6, 10_000)], threshold);
    expect(target).toEqual(resumeAt(7, 2_400_000));
  });

  it("an episode watched to the end hands over to the next one, from the beginning", () => {
    expect(continueTarget(entry(6, 10, 24), [p(7, 1_400_000)], threshold)).toEqual(fromTop(8));
  });

  it("with nothing started the next episode after Shikimori's count is offered", () => {
    expect(continueTarget(entry(6, 10, 24), [], threshold)).toEqual(fromTop(7));
  });

  it("an announcement with nothing aired has nothing to resume", () => {
    expect(continueTarget(entry(0, 0, 12), [p(1, 600_000)], threshold)).toEqual(fromTop(1));
  });

  it("an episode that has not aired is never resumed from", () => {
    expect(continueTarget(entry(8, 8, 12), [p(9, 600_000)], threshold)).toEqual(fromTop(9));
  });

  it("the latest unfinished episode wins over an earlier one", () => {
    const rows = [p(4, 600_000), p(9, 300_000), p(6, 900_000)];
    expect(continueTarget(entry(3, 12, 12), rows, threshold)).toEqual(resumeAt(9, 300_000));
  });

  it("a minute in counts as started however long the episode runs", () => {
    const film = 7_200_000;
    expect(continueTarget(entry(4, 12, 12), [p(5, 60_000, film)], threshold)).toEqual(resumeAt(5, 60_000));
    expect(continueTarget(entry(4, 12, 12), [p(5, 59_999, film)], threshold)).toEqual(fromTop(5));
  });

  it("a short episode qualifies on the share rather than the minute", () => {
    const short = 180_000;
    expect(continueTarget(entry(4, 12, 12), [p(5, 3_600, short)], threshold)).toEqual(resumeAt(5, 3_600));
    expect(continueTarget(entry(4, 12, 12), [p(5, 3_000, short)], threshold)).toEqual(fromTop(5));
  });

  it("the watched threshold is the boundary, and reaching it is finishing", () => {
    expect(continueTarget(entry(4, 12, 12), [p(5, 1_296_000)], threshold)).toEqual(fromTop(6));
    expect(continueTarget(entry(4, 12, 12), [p(5, 1_295_000)], threshold)).toEqual(resumeAt(5, 1_295_000));
  });

  it("a mis-tap on a later episode does not cost the earlier one its position", () => {
    const target = continueTarget(entry(6, 10, 24), [p(7, 2_400_000, 2_880_000), p(9, 10_000)], threshold);
    expect(target).toEqual(resumeAt(7, 2_400_000));
  });

  it("the finale watched early does not carry the pointer past the episodes in between", () => {
    expect(continueTarget(entry(3, 12, 12), [p(12, 1_400_000)], threshold)).toEqual(fromTop(4));
  });

  it("a show finished to the last episode is offered from the top rather than from the end", () => {
    expect(continueTarget(entry(12, 12, 12), allFinished(12), threshold)).toEqual(REWATCH);
  });

  it("a season nobody has measured is never a season that has run out", () => {
    expect(continueTarget(entry(8, 8, 0), [p(8, 1_400_000)], threshold)).toEqual(fromTop(9));
  });

  it("a rewatcher starts where their own counter says, not where the last time round ended", () => {
    const target = continueTarget(entry(0, 12, 12, { status: "rewatching" }), allFinished(12), threshold);
    expect(target).toEqual(fromTop(1));
  });

  it("a rewatcher part-way through an episode is still returned to it", () => {
    const rows = [p(1, 1_400_000), p(2, 1_400_000), p(3, 600_000)];
    expect(continueTarget(entry(2, 12, 12, { status: "rewatching" }), rows, threshold)).toEqual(resumeAt(3, 600_000));
  });

  it("an episode finished locally moves the pointer on before Shikimori has heard about it", () => {
    const rows = [p(4, 1_400_000), p(5, 1_400_000), p(6, 1_400_000)];
    expect(continueTarget(entry(3, 12, 12), rows, threshold)).toEqual(fromTop(7));
  });

  it("a position in an episode Shikimori already counted is spent", () => {
    expect(continueTarget(entry(8, 12, 12), [p(6, 700_000)], threshold)).toEqual(fromTop(9));
  });

  it("an episode with no known duration still resumes once a minute is behind it", () => {
    expect(continueTarget(entry(4, 12, 12), [p(5, 120_000, 0)], threshold)).toEqual(resumeAt(5, 120_000));
  });

  it("a released show with everything watched and nothing on this device offers it from the top", () => {
    expect(continueTarget(entry(12, 12, 12, { status: "completed" }), [], threshold)).toEqual(REWATCH);
  });

  it("a rewatcher who has reached the last episode is offered the show from the top again", () => {
    expect(continueTarget(entry(12, 12, 12, { status: "rewatching" }), [], threshold)).toEqual(REWATCH);
  });

  it("a season still airing has not run out, however far ahead the count has got", () => {
    expect(continueTarget(entry(8, 8, 12), [], threshold)).toEqual(fromTop(9));
    expect(continueTarget(entry(12, 8, 12), [], threshold)).toEqual(fromTop(13));
  });

  it("a season that ran past its announced length keeps offering the episodes it grew", () => {
    expect(continueTarget(entry(12, 13, 12), [], threshold)).toEqual(fromTop(13));
  });

  it("an announcement is not a show that has been finished", () => {
    expect(continueTarget(entry(0, 0, 12), [], threshold)).toEqual(fromTop(1));
  });

  it("a finished show whose length nobody recorded has still ended", () => {
    const target = continueTarget(entry(24, 24, 0, { status: "completed", finishedAiring: true }), [], threshold);
    expect(target).toEqual(REWATCH);
  });

  it("a show of unknown length that nobody has called finished is waited for, not restarted", () => {
    expect(continueTarget(entry(24, 24, 0), [], threshold)).toEqual(fromTop(25));
  });

  it("a finished show of unknown length still hands a viewer the episode they are on", () => {
    expect(continueTarget(entry(10, 24, 0, { finishedAiring: true }), [], threshold)).toEqual(fromTop(11));
  });
});

describe("episodeFraction", () => {
  // EpisodeGridTest: 28 announced, 24 aired, 20 counted, 1_400_000 ms episodes.
  const grid = entry(20, 24, 28);
  const row = (episode: number, positionMs: number, durationMs = 1_400_000) => p(episode, positionMs, durationMs);

  it("shows on the episode in progress and nowhere else", () => {
    const rows = [row(21, 700_000)];
    expect(episodeFraction(grid, rows, 21, threshold)).toBeCloseTo(0.5, 3);
    expect(episodeFraction(grid, rows, 20, threshold)).toBeNull();
    expect(episodeFraction(grid, rows, 22, threshold)).toBeNull();
  });

  it("gives every episode with a position its own strip", () => {
    const rows = [row(21, 700_000), row(23, 350_000)];
    expect(episodeFraction(grid, rows, 21, threshold)).toBeCloseTo(0.5, 3);
    expect(episodeFraction(grid, rows, 23, threshold)).toBeCloseTo(0.25, 3);
    expect(episodeFraction(grid, rows, 22, threshold)).toBeNull();
  });

  it("draws nothing for a mis-tap or an episode finished here", () => {
    expect(episodeFraction(grid, [row(22, 10_000)], 22, threshold)).toBeNull();
    expect(episodeFraction(grid, [row(21, 1_350_000)], 21, threshold)).toBeNull();
  });

  it("draws nothing inside an episode Shikimori already counted", () => {
    expect(episodeFraction(entry(22, 24, 28), [row(21, 700_000)], 21, threshold)).toBeNull();
  });

  it("draws nothing under 1 % or without a duration", () => {
    // android domain/model/LibraryEntry.kt episodeFraction: started, but 60 s of two hours is 0.8 %.
    expect(episodeFraction(grid, [row(21, 60_000, 7_200_000)], 21, threshold)).toBeNull();
    expect(episodeFraction(grid, [row(21, 120_000, 0)], 21, threshold)).toBeNull();
  });
});

describe("lastWatchedAt and progressAt", () => {
  const day = 86_400_000;
  const now = 1_800_000_000_000;

  it("ignores a mis-tap even when it is the newest row", () => {
    // HomeFeedBuilderTest: 40 % of ep 7 three days ago, 0.5 % of ep 4 a minute ago.
    const rows = [p(7, 400_000, 1_000_000, now - 3 * day), p(4, 5_000, 1_000_000, now - 60_000)];
    expect(lastWatchedAt(rows)).toBe(now - 3 * day);
  });

  it("takes the newest started row across every episode", () => {
    const rows = [p(2, 400_000, 1_000_000, now - day), p(5, 400_000, 1_000_000, now - 300_000)];
    expect(lastWatchedAt(rows)).toBe(now - 300_000);
  });

  it("is null when nothing was really watched", () => {
    expect(lastWatchedAt([])).toBeNull();
    expect(lastWatchedAt([p(1, 10_000)])).toBeNull();
  });

  it("finds the row of one episode", () => {
    const rows = [p(3, 100_000), p(4, 200_000)];
    expect(progressAt(rows, 4)).toEqual(p(4, 200_000));
    expect(progressAt(rows, 5)).toBeUndefined();
  });
});
