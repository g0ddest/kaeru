// Vectors from android/src/test/java/app/kaeru/ui/common/design/FormatTest.kt (primaryAction,
// waitingLabel, episodeLine). Android's WatchState pointer becomes a plain progress row here.
import { describe, expect, it } from "vitest";
import type { FeedKind } from "./actions";
import { episodeLine, primaryAction, waitingLabel } from "./actions";
import type { Anime, EpisodeProgress, LibraryEntry, ListStatus } from "./models";
import { availableEpisodes } from "./models";

// A local wall-clock instant: day names follow the viewer's calendar in any test zone.
function at(year: number, month: number, day: number, hour: number, minute: number): number {
  return new Date(year, month - 1, day, hour, minute).getTime();
}

// 12 April 2026, 21:30 — an ordinary evening on the sofa.
const now = at(2026, 4, 12, 21, 30);
const tomorrowEvening = at(2026, 4, 13, 18, 0);
const THRESHOLD = 0.9;

function anime(overrides: Partial<Anime> = {}): Anime {
  return {
    id: 21,
    title: "Магическая битва",
    originalTitle: "Jujutsu Kaisen",
    posterUrl: null,
    backdropUrl: null,
    status: "ongoing",
    episodes: 12,
    episodesAired: 8,
    year: 2026,
    score: 8.6,
    kind: "tv",
    studios: ["MAPPA"],
    description: null,
    nextEpisodeAt: null,
    ...overrides,
  };
}

function entry(a: Anime = anime(), watched = 6, status: ListStatus = "watching"): LibraryEntry {
  return { anime: a, rate: { id: 1, animeId: a.id, status, episodes: watched, updatedAt: 0 } };
}

function stopped(episode: number, positionMs: number, durationMs = 1_440_000): EpisodeProgress {
  return { animeId: 21, episode, positionMs, durationMs, updatedAt: 0 };
}

const finishedRows = (count: number) => Array.from({ length: count }, (_, i) => stopped(i + 1, 1_400_000));

function act(e: LibraryEntry, progress: readonly EpisodeProgress[] = []) {
  return primaryAction(e, e.anime, progress, THRESHOLD, now);
}

describe("primaryAction", () => {
  it("starts an untouched title at the first episode", () => {
    expect(act(entry(anime(), 0))).toEqual({ label: "Смотреть 1 серию", episode: 1, positionMs: 0, enabled: true });
  });

  it("continues a title in progress at the next episode", () => {
    expect(act(entry(anime(), 6))).toEqual({ label: "Продолжить 7 серию", episode: 7, positionMs: 0, enabled: true });
  });

  it("continues a half-watched episode from its timecode", () => {
    expect(act(entry(anime(), 6), [stopped(7, 860_000)])).toEqual({
      label: "Продолжить с 14:20",
      episode: 7,
      positionMs: 860_000,
      enabled: true,
    });
  });

  it("moves on once an episode is watched past the threshold", () => {
    expect(act(entry(anime(), 6), [stopped(7, 1_400_000)]).label).toBe("Продолжить 8 серию");
  });

  it("still offers the last episode that aired", () => {
    expect(act(entry(anime(), 7))).toEqual({ label: "Продолжить 8 серию", episode: 8, positionMs: 0, enabled: true });
  });

  it("names an unaired episode with its date and cannot be pressed", () => {
    const action = act(entry(anime({ nextEpisodeAt: tomorrowEvening }), 8));
    expect(action).toEqual({ label: "9 серия выйдет завтра", episode: 9, positionMs: 0, enabled: false });
  });

  it("counts the days to an episode further out", () => {
    const action = act(entry(anime({ nextEpisodeAt: at(2026, 4, 15, 18, 0) }), 8));
    expect(action.label).toBe("9 серия выйдет через 3 дня");
  });

  it("simply awaits an episode with no date, or with a date already gone by", () => {
    expect(act(entry(anime(), 8)).label).toBe("Ждём 9 серию");
    const overdue = act(entry(anime({ nextEpisodeAt: at(2026, 4, 11, 18, 0) }), 8));
    expect(overdue.label).toBe("Ждём 9 серию");
    expect(overdue.enabled).toBe(false);
  });

  it("offers a rewatch of a finished show with everything watched", () => {
    const done = entry(anime({ status: "released", episodes: 12, episodesAired: 12 }), 12, "completed");
    expect(act(done)).toEqual({ label: "Пересмотреть", episode: 1, positionMs: 0, enabled: true });
  });

  it("offers a rewatcher at the last episode the show from the top", () => {
    const again = entry(anime({ status: "released", episodes: 12, episodesAired: 12 }), 12, "rewatching");
    expect(act(again)).toEqual({ label: "Пересмотреть", episode: 1, positionMs: 0, enabled: true });
  });

  it("keeps an ongoing season waiting however far the count has got", () => {
    const caughtUp = act(entry(anime({ episodes: 12, episodesAired: 8 }), 8));
    expect(caughtUp.label).toBe("Ждём 9 серию");
    expect(caughtUp.enabled).toBe(false);
    expect(act(entry(anime({ episodes: 12, episodesAired: 8 }), 12)).label).toBe("Ждём 13 серию");
  });

  it("offers the rewatch for a finished show whose length nobody recorded", () => {
    const done = entry(anime({ status: "released", episodes: 0, episodesAired: 24 }), 24, "completed");
    expect(act(done)).toEqual({ label: "Пересмотреть", episode: 1, positionMs: 0, enabled: true });
    const airing = entry(anime({ status: "ongoing", episodes: 0, episodesAired: 24 }), 24);
    expect(act(airing).label).toBe("Ждём 25 серию");
  });

  it("offers nothing to press for an announcement with nothing aired", () => {
    const anons = act(entry(anime({ status: "anons", episodes: 0, episodesAired: 0 }), 0));
    expect(anons.label).toBe("Ещё не вышло");
    expect(anons.enabled).toBe(false);
  });

  it("names the day an announcement arrives", () => {
    const anons = act(entry(anime({ status: "anons", episodes: 12, episodesAired: 0, nextEpisodeAt: tomorrowEvening }), 0));
    expect(anons.label).toBe("1 серия выйдет завтра");
    expect(anons.enabled).toBe(false);
  });

  it("takes the timecode from the episode being continued, not the one opened last", () => {
    const action = act(entry(anime(), 6), [stopped(7, 860_000), stopped(6, 10_000)]);
    expect(action.label).toBe("Продолжить с 14:20");
    expect(action.episode).toBe(7);
  });

  it("never offers to continue ten seconds of an episode", () => {
    const action = act(entry(anime(), 6), [stopped(7, 10_000)]);
    expect(action.label).toBe("Продолжить 7 серию");
    expect(action.episode).toBe(7);
  });

  it("offers a show with every episode behind the viewer from the top", () => {
    const finished = entry(anime({ status: "released", episodes: 12, episodesAired: 12 }), 12);
    expect(act(finished, finishedRows(12))).toEqual({ label: "Пересмотреть", episode: 1, positionMs: 0, enabled: true });
  });

  it("starts the show over for a rewatch that has reset the count", () => {
    const rewatching = entry(anime({ status: "released", episodes: 12, episodesAired: 12 }), 0, "rewatching");
    expect(act(rewatching, finishedRows(12))).toEqual({ label: "Смотреть 1 серию", episode: 1, positionMs: 0, enabled: true });
  });

  it("says «Смотреть» for an episode finished here the last time round", () => {
    // Format.kt `seen`: a rewatcher on 4 whose row for 4 is finished is watching it again.
    const rewatching = entry(anime({ status: "released", episodes: 12, episodesAired: 12 }), 3, "rewatching");
    expect(act(rewatching, finishedRows(12)).label).toBe("Смотреть 4 серию");
  });

  it("waits for the next episode of a caught-up show of unannounced length", () => {
    const caughtUp = entry(anime({ episodes: 0, episodesAired: 8, nextEpisodeAt: tomorrowEvening }), 8);
    const action = act(caughtUp, [stopped(8, 1_400_000)]);
    expect(action.label).toBe("9 серия выйдет завтра");
    expect(action.enabled).toBe(false);
  });

  it("does not skip the episodes in between when the finale was watched early", () => {
    const jumped = entry(anime({ status: "released", episodes: 12, episodesAired: 12 }), 3);
    const action = act(jumped, [stopped(12, 1_400_000)]);
    expect(action.label).toBe("Продолжить 4 серию");
    expect(action.episode).toBe(4);
  });

  it("is pressable exactly when its episode has aired", () => {
    const cases = [
      entry(anime({ episodesAired: 8, nextEpisodeAt: tomorrowEvening }), 8),
      entry(anime({ episodesAired: 8 }), 8),
      entry(anime({ status: "anons", episodes: 0, episodesAired: 0 }), 0),
      entry(anime(), 6),
    ];
    for (const e of cases) {
      const action = act(e);
      expect(action.enabled).toBe(action.episode <= availableEpisodes(e.anime));
      if (!action.enabled) expect(action.positionMs).toBe(0);
    }
  });

  it("reads the anime it is given rather than the list card", () => {
    // The list card has no next_episode_at; the details passed in do.
    const listed = entry(anime(), 8);
    const details = anime({ nextEpisodeAt: tomorrowEvening });
    expect(primaryAction(listed, details, [], THRESHOLD, now).label).toBe("9 серия выйдет завтра");
  });

  it("treats a title in no list as nothing counted", () => {
    const released = anime({ status: "released", episodes: 12, episodesAired: 12 });
    expect(primaryAction(null, released, [], THRESHOLD, now)).toEqual({
      label: "Смотреть 1 серию",
      episode: 1,
      positionMs: 0,
      enabled: true,
    });
    const announced = anime({ status: "anons", episodes: 0, episodesAired: 0 });
    expect(primaryAction(null, announced, [], THRESHOLD, now)).toEqual({
      label: "Ещё не вышло",
      episode: 1,
      positionMs: 0,
      enabled: false,
    });
    expect(primaryAction(null, released, [stopped(3, 600_000)], THRESHOLD, now).label).toBe("Продолжить с 10:00");
  });
});

describe("waitingLabel", () => {
  it("names a dated episode still to come by its day", () => {
    expect(waitingLabel(anime({ nextEpisodeAt: tomorrowEvening }), 9, now)).toBe("9 серия выйдет завтра");
    expect(waitingLabel(anime({ nextEpisodeAt: at(2026, 4, 15, 18, 0) }), 9, now)).toBe("9 серия выйдет через 3 дня");
  });

  it("simply awaits an episode with no date", () => {
    expect(waitingLabel(anime(), 9, now)).toBe("Ждём 9 серию");
  });

  it("does not repeat a date already past", () => {
    expect(waitingLabel(anime({ nextEpisodeAt: at(2026, 4, 10, 18, 0) }), 9, now)).toBe("Ждём 9 серию");
  });

  it("says «Ещё не вышло» when nothing has aired", () => {
    expect(waitingLabel(anime({ episodesAired: 0 }), 1, now)).toBe("Ещё не вышло");
  });
});

describe("episodeLine", () => {
  it("says how much of a started episode is left, after a comma", () => {
    expect(episodeLine("continue", entry(), 7, [stopped(7, 600_000)], now)).toBe("7 серия, осталось 14 мин");
  });

  it("lets the episode stand alone without a remembered position", () => {
    expect(episodeLine("continue", entry(), 7, [], now)).toBe("7 серия");
    expect(episodeLine("continue", entry(), 7, [stopped(7, 120_000, 0)], now)).toBe("7 серия");
  });

  it("announces a fresh episode", () => {
    expect(episodeLine("new", entry(), 7, [], now)).toBe("Вышла 7 серия");
  });

  it("names the next episode by its number", () => {
    expect(episodeLine("next", entry(), 7, [], now)).toBe("7 серия");
  });

  it("carries the day of an episode still to air", () => {
    const dated = entry(anime({ nextEpisodeAt: tomorrowEvening }));
    expect(episodeLine("upcoming", dated, 9, [], now)).toBe("9 серия завтра");
    expect(episodeLine("upcoming", entry(), 9, [], now)).toBe("9 серия скоро");
  });

  it("reports the season of a planned title, not an episode", () => {
    expect(episodeLine("planned", entry(anime(), 0, "planned"), 1, [], now)).toBe("В планах, 12 серий");
    const unknownLength = entry(anime({ episodes: 0, episodesAired: 0 }), 0, "planned");
    expect(episodeLine("planned", unknownLength, 1, [], now)).toBe("В планах");
  });

  it("never joins facts with a middle dot", () => {
    const kinds: FeedKind[] = ["continue", "new", "next", "upcoming", "planned"];
    for (const kind of kinds) {
      expect(episodeLine(kind, entry(), 7, [stopped(7, 600_000)], now)).not.toContain("·");
    }
  });
});
