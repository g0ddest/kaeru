// Vectors: android/src/test/java/app/kaeru/ui/common/home/HomeContentTest.kt (screen state),
// android/src/test/java/app/kaeru/ui/common/home/HomeRowsTest.kt «discovery» (row states) and
// android/src/test/java/app/kaeru/data/library/ShikimoriDiscoverRepositoryTest.kt (cache).
import { describe, expect, it, vi } from "vitest";
import type { Anime, LibraryEntry } from "../domain/models";
import {
  CATALOGUE_TTL_MS,
  CatalogueCache,
  discoverContent,
  homeContent,
  libraryEntries,
  popularNowContent,
  seasonalContent,
} from "./home";
import type { DiscoverContent } from "./home";

const OFFLINE = "Нет соединения. Проверьте интернет";
const MINUTE = 60_000;
const HOUR = 60 * MINUTE;
const START = Date.parse("2026-09-13T20:00:00Z");

function anime(id: number): Anime {
  return {
    id,
    title: `Аниме ${id}`,
    originalTitle: `Anime ${id}`,
    posterUrl: null,
    backdropUrl: null,
    status: "ongoing",
    episodes: 12,
    episodesAired: 8,
    year: 2026,
    score: 8,
    kind: "tv",
    studios: [],
    description: null,
    nextEpisodeAt: null,
  };
}

function entry(id: number): LibraryEntry {
  return { anime: anime(id), rate: { id, animeId: id, status: "watching", episodes: 6, updatedAt: START } };
}

function cardIds(content: DiscoverContent | null): number[] {
  if (content?.kind !== "titles") throw new Error(`expected titles, got ${content?.kind ?? "nothing"}`);
  return content.cards.map((card) => card.animeId);
}

function clockAt(start: number) {
  let now = start;
  return {
    now: () => now,
    advance: (ms: number) => {
      now += ms;
    },
  };
}

describe("homeContent", () => {
  it("is loading before anything was read", () => {
    expect(homeContent({ kind: "idle" }, true)).toEqual({ kind: "loading" });
  });

  it("is the feed when the feed has titles", () => {
    expect(homeContent({ kind: "ready", entries: [entry(1)] }, false)).toEqual({ kind: "feed" });
  });

  it("stays the feed when a refresh over it fails", () => {
    expect(homeContent({ kind: "error", message: OFFLINE, entries: [entry(1)] }, false)).toEqual({ kind: "feed" });
  });

  it("explains a failure when there is no feed", () => {
    expect(homeContent({ kind: "error", message: OFFLINE, entries: null }, true)).toEqual({
      kind: "error",
      message: OFFLINE,
    });
  });

  it("is the invitation when a finished sync found nothing", () => {
    expect(homeContent({ kind: "ready", entries: [] }, true)).toEqual({ kind: "empty" });
  });

  it("is the first sync, not an empty list, while the list is still coming", () => {
    expect(homeContent({ kind: "loading", entries: null }, true)).toEqual({ kind: "first_sync" });
  });

  it("keeps the feed on screen while it syncs again", () => {
    expect(homeContent({ kind: "loading", entries: [entry(1)] }, false)).toEqual({ kind: "feed" });
  });

  it("is the invitation when the list holds nothing the feed can show", () => {
    // For example a list of completed titles only: loaded, nothing wrong, nothing to watch.
    expect(homeContent({ kind: "ready", entries: [entry(1)] }, true)).toEqual({ kind: "empty" });
  });

  it("still explains a failure when a refresh over an empty feed fails", () => {
    expect(homeContent({ kind: "error", message: OFFLINE, entries: [] }, true)).toEqual({
      kind: "error",
      message: OFFLINE,
    });
  });
});

describe("libraryEntries", () => {
  it("reads the entries out of every state that has them", () => {
    const entries = [entry(1)];
    expect(libraryEntries({ kind: "idle" })).toEqual([]);
    expect(libraryEntries({ kind: "loading", entries: null })).toEqual([]);
    expect(libraryEntries({ kind: "loading", entries })).toBe(entries);
    expect(libraryEntries({ kind: "ready", entries })).toBe(entries);
    expect(libraryEntries({ kind: "error", message: OFFLINE, entries })).toBe(entries);
  });
});

describe("«Популярно сейчас»", () => {
  it("shows the titles it was given", () => {
    expect(cardIds(popularNowContent([anime(1)], false))).toEqual([1]);
  });

  it("is absent when the read failed", () => {
    expect(popularNowContent(null, false)).toBeNull();
  });

  it("is absent when the catalogue had nothing", () => {
    expect(popularNowContent([], false)).toBeNull();
  });

  it("keeps its place with a skeleton while loading", () => {
    expect(popularNowContent(null, true)).toEqual({ kind: "loading" });
  });

  it("does not replace titles on screen with a skeleton while they reload", () => {
    expect(cardIds(popularNowContent([anime(1)], true))).toEqual([1]);
  });
});

describe("«Популярное в сезоне»", () => {
  it("takes the whole block away when the very first load fails", () => {
    expect(seasonalContent(null, false, false)).toBeNull();
  });

  it("keeps the block up while the first load runs", () => {
    expect(seasonalContent(null, true, false)).toEqual({ kind: "loading" });
  });

  it("says an empty season is empty and keeps the switcher", () => {
    expect(seasonalContent([], false, true)).toEqual({ kind: "empty" });
  });

  it("offers a retry when a season fails after another one worked", () => {
    expect(seasonalContent(null, false, true)).toEqual({ kind: "failed" });
  });

  it("treats an empty answer as empty however the session got there", () => {
    expect(seasonalContent([], false, false)).toEqual({ kind: "empty" });
  });

  it("shows a skeleton, not the season before, while a new season loads", () => {
    expect(seasonalContent(null, true, true)).toEqual({ kind: "loading" });
  });
});

describe("discoverContent", () => {
  it("draws a title the catalogue repeats only once", () => {
    expect(cardIds(discoverContent([anime(1), anime(2), anime(1)], false))).toEqual([1, 2]);
  });

  it("builds catalogue cards with no badge and no progress", () => {
    expect(discoverContent([anime(1)], false)).toEqual({
      kind: "titles",
      cards: [
        { key: "1", animeId: 1, title: "Аниме 1", posterUrl: null, badge: null, subtitle: "8 серий", progress: null },
      ],
    });
  });
});

describe("CatalogueCache", () => {
  it("keeps six hours", () => {
    expect(CATALOGUE_TTL_MS).toBe(6 * HOUR);
  });

  it("answers a second read inside six hours without fetching", async () => {
    const clock = clockAt(START);
    const cache = new CatalogueCache({ now: clock.now });
    const load = vi.fn(async () => [anime(1)]);

    const first = await cache.read("now", load);
    clock.advance(5 * HOUR + 59 * MINUTE);
    const second = await cache.read("now", load);

    expect(second).toBe(first);
    expect(load).toHaveBeenCalledTimes(1);
  });

  it("reads the row again six hours on", async () => {
    const clock = clockAt(START);
    const cache = new CatalogueCache({ now: clock.now });
    const load = vi.fn(async () => [anime(1)]);

    await cache.read("now", load);
    clock.advance(6 * HOUR);
    await cache.read("now", load);

    expect(load).toHaveBeenCalledTimes(2);
  });

  it("ignores a fresh cache when forced", async () => {
    const cache = new CatalogueCache({ now: clockAt(START).now });
    const load = vi.fn(async () => [anime(1)]);

    await cache.read("now", load);
    await cache.read("now", load, true);

    expect(load).toHaveBeenCalledTimes(2);
  });

  it("remembers each season under its own key", async () => {
    const cache = new CatalogueCache({ now: clockAt(START).now });
    const summer = vi.fn(async () => [anime(1)]);
    const fall = vi.fn(async () => [anime(2)]);

    await cache.read("summer_2026", summer);
    await cache.read("fall_2026", fall);
    await cache.read("summer_2026", summer);

    expect(summer).toHaveBeenCalledTimes(1);
    expect(fall).toHaveBeenCalledTimes(1);
  });

  it("does not share a key between the ongoing row and a season", async () => {
    const cache = new CatalogueCache({ now: clockAt(START).now });
    const ongoing = vi.fn(async () => [anime(1)]);
    const summer = vi.fn(async () => [anime(2)]);

    expect(await cache.read("now", ongoing)).toEqual([anime(1)]);
    expect(await cache.read("summer_2026", summer)).toEqual([anime(2)]);
    expect(summer).toHaveBeenCalledTimes(1);
  });

  it("does not cache a failure, so the next read tries again", async () => {
    const cache = new CatalogueCache({ now: clockAt(START).now });
    const refused = new Error("503");
    const load = vi
      .fn<() => Promise<Anime[]>>()
      .mockRejectedValueOnce(refused)
      .mockResolvedValueOnce([anime(1)]);

    await expect(cache.read("now", load)).rejects.toBe(refused);
    expect(await cache.read("now", load)).toEqual([anime(1)]);
    expect(load).toHaveBeenCalledTimes(2);
  });
});
