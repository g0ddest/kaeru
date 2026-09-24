// Vectors from android/src/test/java/app/kaeru/ui/common/library/LibraryTabsTest.kt and
// LibraryViewModelTest.kt. Not ported: «latin names come before cyrillic ones» — the JDK collator
// does that, ICU's «ru» (Intl.Collator, per the contract) puts Cyrillic first.
import { describe, expect, it } from "vitest";
import type { Anime, EpisodeProgress, LibraryEntry, ListStatus } from "../domain/models";
import {
  emptyTabCopy,
  libraryCard,
  libraryCardProgress,
  libraryCardSubtitle,
  libraryTabs,
  parseSort,
  parseTab,
  selectLibrary,
} from "./listing";

const SEP_1 = "2026-09-01T00:00:00Z";

function anime(id: number, title: string, over: Partial<Anime> = {}): Anime {
  return {
    id,
    title,
    originalTitle: title,
    posterUrl: null,
    backdropUrl: null,
    status: "released",
    episodes: 12,
    episodesAired: 12,
    year: 2026,
    score: null,
    kind: "tv",
    studios: [],
    description: null,
    nextEpisodeAt: null,
    ...over,
  };
}

function entry(
  id: number,
  title: string,
  status: ListStatus,
  watched: number,
  updated = SEP_1,
  over: Partial<Anime> = {},
): LibraryEntry {
  return {
    anime: anime(id, title, over),
    rate: { id, animeId: id, status, episodes: watched, updatedAt: Date.parse(updated) },
  };
}

function row(episode: number, positionMs: number, durationMs = 1_440_000): EpisodeProgress {
  return { animeId: 1, episode, positionMs, durationMs, updatedAt: 1 };
}

const ids = (entries: readonly LibraryEntry[]) => entries.map((item) => item.anime.id);

describe("libraryTabs", () => {
  it("gives every status a tab counted over the whole list, in Android's order", () => {
    const tabs = libraryTabs([
      entry(1, "А", "watching", 0),
      entry(2, "Б", "watching", 0),
      entry(3, "В", "planned", 0),
      entry(4, "Г", "dropped", 0),
    ]);
    expect(tabs.map((tab) => tab.status)).toEqual([
      "watching",
      "planned",
      "completed",
      "rewatching",
      "on_hold",
      "dropped",
    ]);
    expect(tabs.map((tab) => tab.count)).toEqual([2, 1, 0, 0, 0, 1]);
  });

  it("reads as a label and a number with nothing between them", () => {
    const many = (from: number, count: number, status: ListStatus) =>
      Array.from({ length: count }, (_, index) => entry(from + index, "Т", status, 0));
    const tabs = libraryTabs([...many(0, 12, "watching"), ...many(100, 40, "planned"), ...many(200, 128, "completed")]);
    expect(tabs.slice(0, 3).map((tab) => tab.text)).toEqual(["Смотрю 12", "В планах 40", "Завершено 128"]);
    for (const tab of tabs) {
      expect(tab.text).not.toMatch(/[·()]/);
      expect(tab.text).not.toBe(tab.text.toUpperCase());
    }
  });
});

describe("selectLibrary", () => {
  it("keeps the open status and puts the latest update first", () => {
    const items = [
      entry(1, "А", "watching", 1, "2026-09-01T00:00:00Z"),
      entry(2, "Б", "watching", 2, "2026-09-10T00:00:00Z"),
      entry(3, "В", "planned", 3, "2026-09-11T00:00:00Z"),
    ];
    expect(ids(selectLibrary(items, "watching", "updated"))).toEqual([2, 1]);
    expect(ids(selectLibrary(items, "watching", "title"))).toEqual([1, 2]);
  });

  it("breaks a tie in update time by name, whatever order the list came in", () => {
    const same = "2026-09-10T00:00:00Z";
    const items = [entry(1, "Ящер", "watching", 0, same), entry(2, "Аист", "watching", 0, same)];
    expect(ids(selectLibrary(items, "watching", "updated"))).toEqual([2, 1]);
    expect(ids(selectLibrary([...items].reverse(), "watching", "updated"))).toEqual([2, 1]);
  });

  it("sorts ё right after е, not after я", () => {
    const items = [entry(1, "Яблоко", "watching", 0), entry(2, "Ёлка", "watching", 0), entry(3, "Ели", "watching", 0)];
    expect(ids(selectLibrary(items, "watching", "title"))).toEqual([3, 2, 1]);
  });

  it("does not let case decide the order", () => {
    const items = [entry(1, "фрирен", "watching", 0), entry(2, "Дандадан", "watching", 0)];
    expect(ids(selectLibrary(items, "watching", "title"))).toEqual([2, 1]);
  });

  it("leaves the list it was given untouched", () => {
    const items = [entry(1, "Ящер", "watching", 0), entry(2, "Аист", "watching", 0)];
    selectLibrary(items, "watching", "title");
    expect(ids(items)).toEqual([1, 2]);
  });
});

describe("libraryCardSubtitle", () => {
  it("counts a started title up to the season length", () => {
    expect(libraryCardSubtitle(entry(1, "Т", "watching", 7, SEP_1, { episodes: 28, episodesAired: 24 }))).toBe("7 из 28");
  });

  it("says how long an untouched season is instead of counting from zero", () => {
    expect(libraryCardSubtitle(entry(1, "Т", "planned", 0, SEP_1, { episodes: 28, episodesAired: 0 }))).toBe("28 серий");
  });

  it("counts against what has aired when the total is unknown", () => {
    expect(
      libraryCardSubtitle(entry(1, "Т", "watching", 3, SEP_1, { episodes: 0, episodesAired: 5, status: "ongoing" })),
    ).toBe("3 из 5");
  });

  it("writes «?» for a total nobody knows yet", () => {
    expect(
      libraryCardSubtitle(entry(1, "Т", "watching", 3, SEP_1, { episodes: 0, episodesAired: 0, status: "ongoing" })),
    ).toBe("3 из ?");
  });

  it("says nothing for a title with nothing aired and nothing watched", () => {
    expect(
      libraryCardSubtitle(entry(1, "Т", "planned", 0, SEP_1, { episodes: 0, episodesAired: 0, status: "anons" })),
    ).toBeNull();
  });
});

describe("libraryCardProgress", () => {
  const watching = entry(1, "Т", "watching", 2);

  it("shows how far into the episode being continued", () => {
    expect(libraryCardProgress(watching, [row(3, 600_000)], 0.9)).toBeCloseTo(600 / 1440, 5);
  });

  it("draws nothing for a mis-tap under a minute and under 2 %", () => {
    expect(libraryCardProgress(watching, [row(3, 20_000)], 0.9)).toBeNull();
  });

  it("draws nothing for an episode finished here", () => {
    expect(libraryCardProgress(watching, [row(3, 1_400_000)], 0.9)).toBeNull();
  });

  it("draws nothing without positions", () => {
    expect(libraryCardProgress(watching, [], 0.9)).toBeNull();
  });
});

describe("libraryCard", () => {
  it("is the poster card of a list title: no badge, a caption, a strip only mid-episode", () => {
    expect(libraryCard(entry(2, "Ели", "watching", 5), [], 0.9)).toEqual({
      key: "2",
      animeId: 2,
      title: "Ели",
      posterUrl: null,
      badge: null,
      subtitle: "5 из 12",
      progress: null,
    });
  });
});

describe("emptyTabCopy", () => {
  it("offers search only on the two tabs a viewer fills on purpose", () => {
    const offering = (["watching", "planned", "completed", "rewatching", "on_hold", "dropped"] as const).filter(
      (status) => emptyTabCopy(status).offersSearch,
    );
    expect(offering).toEqual(["watching", "planned"]);
  });

  it("uses Android's words", () => {
    expect(emptyTabCopy("watching")).toEqual({
      title: "Вы ничего не смотрите",
      text: "Начните любой тайтл — он окажется здесь вместе с серией, на которой вы остановились.",
      offersSearch: true,
    });
    expect(emptyTabCopy("completed")).toEqual({
      title: "Завершённых тайтлов пока нет",
      text: "Здесь соберётся всё, что вы досмотрели до конца.",
      offersSearch: false,
    });
    expect(emptyTabCopy("dropped").title).toBe("Ничего не брошено");
  });
});

describe("address values", () => {
  it("reads a known tab and falls back to «Смотрю»", () => {
    expect(parseTab("on_hold")).toBe("on_hold");
    expect(parseTab("bogus")).toBe("watching");
    expect(parseTab(null)).toBe("watching");
  });

  it("reads the sort and falls back to «Обновление»", () => {
    expect(parseSort("title")).toBe("title");
    expect(parseSort("anything")).toBe("updated");
    expect(parseSort(null)).toBe("updated");
  });
});
