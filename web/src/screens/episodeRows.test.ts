import { describe, expect, it } from "vitest";
import type { AiringStatus, Anime, EpisodeProgress, LibraryEntry, ListStatus } from "../domain/models";
import { EPISODE_PAGE, episodeRows, type EpisodeRow } from "./episodeRows";

// Vectors from android/src/test/java/app/kaeru/ui/common/details/EpisodeGridTest.kt. Android's
// WatchState pointer is one more progress row here: the web keeps only per-episode rows.
const THRESHOLD = 0.9;

function anime(episodes: number, aired: number, status: AiringStatus = "ongoing"): Anime {
  return {
    id: 7,
    title: "Фрирен",
    originalTitle: "Frieren",
    posterUrl: null,
    backdropUrl: null,
    status,
    episodes,
    episodesAired: aired,
    year: 2023,
    score: 9.1,
    kind: "tv",
    studios: ["Madhouse"],
    description: null,
    nextEpisodeAt: null,
  };
}

function entry(show: Anime, episodes: number, status: ListStatus = "watching"): LibraryEntry {
  return { anime: show, rate: { id: 1, animeId: 7, status, episodes, updatedAt: 0 } };
}

function stopped(episode: number, positionMs: number, durationMs = 1_400_000): EpisodeProgress {
  return { animeId: 7, episode, positionMs, durationMs, updatedAt: 0 };
}

function at(rows: EpisodeRow[], episode: number): EpisodeRow {
  const found = rows.find((row) => row.number === episode);
  if (!found) throw new Error(`no row for episode ${episode}`);
  return found;
}

describe("episodeRows", () => {
  it("pages sixty rows at a time", () => {
    expect(EPISODE_PAGE).toBe(60);
  });

  it("runs to the announced length and stops being playable at what aired", () => {
    const show = anime(28, 24);
    const rows = episodeRows(show, entry(show, 20), [], THRESHOLD);

    expect(rows.map((row) => row.number)).toEqual(Array.from({ length: 28 }, (_, index) => index + 1));
    expect(at(rows, 24).aired).toBe(true);
    expect(at(rows, 25).aired).toBe(false);
  });

  it("marks what Shikimori counted as watched and the rest as not", () => {
    const show = anime(28, 24);
    const rows = episodeRows(show, entry(show, 20), [], THRESHOLD);

    expect(at(rows, 20).watched).toBe(true);
    expect(at(rows, 21).watched).toBe(false);
  });

  it("counts an episode the viewer already watched as aired whatever the catalogue says", () => {
    const show = anime(28, 24);
    const rows = episodeRows(show, entry(show, 26), [], THRESHOLD);

    expect(at(rows, 26).aired).toBe(true);
    expect(at(rows, 27).aired).toBe(false);
  });

  it("extends the list past the announced season to an episode opened here", () => {
    const show = anime(12, 12, "released");
    const rows = episodeRows(show, entry(show, 12), [stopped(13, 60_000)], THRESHOLD);

    expect(rows).toHaveLength(13);
    expect(at(rows, 13).aired).toBe(true);
  });

  it("shows progress on the episode in progress and nowhere else", () => {
    const show = anime(28, 24);
    const rows = episodeRows(show, entry(show, 20), [stopped(21, 700_000)], THRESHOLD);

    expect(at(rows, 21).fraction).toBeCloseTo(0.5, 3);
    expect(at(rows, 21).positionMs).toBe(700_000);
    expect(at(rows, 20).fraction).toBeNull();
    expect(at(rows, 22).fraction).toBeNull();
  });

  it("gives every episode with a position of its own its own bar", () => {
    const show = anime(28, 24);
    const rows = episodeRows(show, entry(show, 20), [stopped(21, 700_000), stopped(23, 350_000)], THRESHOLD);

    expect(at(rows, 21).fraction).toBeCloseTo(0.5, 3);
    expect(at(rows, 23).fraction).toBeCloseTo(0.25, 3);
    expect(at(rows, 22).fraction).toBeNull();
  });

  it("does not call ten seconds from a mis-tap started", () => {
    const show = anime(28, 24);
    const rows = episodeRows(show, entry(show, 20), [stopped(22, 10_000)], THRESHOLD);

    expect(at(rows, 22).fraction).toBeNull();
    expect(at(rows, 22).positionMs).toBe(0);
  });

  it("draws nothing on an episode finished here before Shikimori counts it", () => {
    const show = anime(28, 24);
    const rows = episodeRows(show, entry(show, 20), [stopped(21, 1_350_000)], THRESHOLD);

    expect(at(rows, 21).fraction).toBeNull();
  });

  it("stretches the list to a row past the announced season", () => {
    const show = anime(12, 12, "released");
    const rows = episodeRows(show, entry(show, 12), [stopped(13, 700_000)], THRESHOLD);

    expect(rows).toHaveLength(13);
    expect(at(rows, 13).aired).toBe(true);
  });

  it("does not conjure an episode from a row nobody really started", () => {
    const show = anime(12, 12, "released");

    expect(episodeRows(show, entry(show, 12), [stopped(13, 10_000)], THRESHOLD)).toHaveLength(12);
  });

  it("treats a position in an episode Shikimori already counted as spent", () => {
    const show = anime(28, 24);
    const rows = episodeRows(show, entry(show, 22), [stopped(21, 700_000)], THRESHOLD);

    expect(at(rows, 21).fraction).toBeNull();
  });

  it("lists a title in no list in full with nothing watched", () => {
    const rows = episodeRows(anime(12, 12, "released"), null, [], THRESHOLD);

    expect(rows).toHaveLength(12);
    expect(rows.every((row) => row.aired)).toBe(true);
    expect(rows.some((row) => row.watched)).toBe(false);
    expect(rows.every((row) => row.fraction === null)).toBe(true);
  });

  it("has no rows for an announcement with no episodes", () => {
    expect(episodeRows(anime(0, 0, "anons"), null, [], THRESHOLD)).toEqual([]);
  });

  it("runs an ongoing season of unknown length to what is out", () => {
    const show = anime(0, 24);
    const rows = episodeRows(show, entry(show, 20), [], THRESHOLD);

    expect(rows).toHaveLength(24);
    expect(rows.every((row) => row.aired)).toBe(true);
    expect(rows.filter((row) => row.watched)).toHaveLength(20);
  });

  it("lists the episodes an announcement promised as still to come", () => {
    const rows = episodeRows(anime(12, 0, "anons"), null, [], THRESHOLD);

    expect(rows).toHaveLength(12);
    expect(rows.some((row) => row.aired)).toBe(false);
  });

  it("lists a released season with no aired count as it was announced", () => {
    const rows = episodeRows(anime(12, 0, "released"), null, [], THRESHOLD);

    expect(rows).toHaveLength(12);
    expect(rows.every((row) => row.aired)).toBe(true);
  });
});
