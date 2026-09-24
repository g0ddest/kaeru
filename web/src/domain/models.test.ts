// Vectors from android/src/test/java/app/kaeru/domain/model/AnimeTest.kt (availableEpisodes)
// and android domain/model/UserRate.kt + data/shikimori/ShikimoriMappers.kt (parsing).
import { describe, expect, it } from "vitest";
import type { AiringStatus, Anime } from "./models";
import { LIST_TABS, STATUS_MENU, availableEpisodes, parseAiringStatus, parseListStatus } from "./models";

function anime(status: AiringStatus, episodes: number, episodesAired: number): Anime {
  return {
    id: 1,
    title: "Тест",
    originalTitle: "Test",
    posterUrl: null,
    backdropUrl: null,
    status,
    episodes,
    episodesAired,
    year: null,
    score: null,
    kind: null,
    studios: [],
    description: null,
    nextEpisodeAt: null,
  };
}

describe("availableEpisodes", () => {
  it("an ongoing show has only what has aired, whatever the season promises", () => {
    expect(availableEpisodes(anime("ongoing", 24, 7))).toBe(7);
    expect(availableEpisodes(anime("ongoing", 24, 0))).toBe(0);
  });

  it("an announcement has nothing available, even when the catalogue says something aired", () => {
    expect(availableEpisodes(anime("anons", 12, 0))).toBe(0);
    expect(availableEpisodes(anime("anons", 0, 0))).toBe(0);
    expect(availableEpisodes(anime("anons", 12, 5))).toBe(0);
  });

  it("a released show counts its whole announced run", () => {
    // Live Shikimori: Death Note is released with episodes 37 and episodes_aired 0.
    expect(availableEpisodes(anime("released", 12, 0))).toBe(12);
    expect(availableEpisodes(anime("released", 37, 0))).toBe(37);
  });

  it("a released show with an unknown total falls back to what aired", () => {
    expect(availableEpisodes(anime("released", 0, 24))).toBe(24);
  });
});

describe("parseListStatus", () => {
  it("keeps every Shikimori status as it is", () => {
    for (const status of LIST_TABS) expect(parseListStatus(status)).toBe(status);
  });

  it("reads anything unknown as planned", () => {
    expect(parseListStatus("")).toBe("planned");
    expect(parseListStatus("watched")).toBe("planned");
    expect(parseListStatus("Watching")).toBe("planned");
  });
});

describe("parseAiringStatus", () => {
  it("knows ongoing and anons and treats everything else as released", () => {
    expect(parseAiringStatus("ongoing")).toBe("ongoing");
    expect(parseAiringStatus("anons")).toBe("anons");
    expect(parseAiringStatus("released")).toBe("released");
    expect(parseAiringStatus("latest")).toBe("released");
    expect(parseAiringStatus("")).toBe("released");
  });
});

describe("status orders", () => {
  it("lists the library tabs in Android order", () => {
    expect(LIST_TABS).toEqual(["watching", "planned", "completed", "rewatching", "on_hold", "dropped"]);
  });

  it("lists the title-screen status menu in the order both apps use", () => {
    expect(STATUS_MENU).toEqual(["watching", "planned", "completed", "on_hold", "dropped", "rewatching"]);
  });
});
