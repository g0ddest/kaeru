// Vectors ported from android/src/test/java/app/kaeru/domain/feed/HomeFeedBuilderTest.kt and
// android/src/test/java/app/kaeru/ui/common/home/HomeRowsTest.kt. Android's WatchState pointer is
// folded into the per-episode progress rows: the web keeps positions only per episode.
import { describe, expect, it } from "vitest";
import type { FeedKind } from "./actions";
import { buildFeed, catalogueCard, feedRows, isFeedEmpty, UPCOMING_WINDOW_MS } from "./feed";
import type { Card, FeedItem, HomeFeed, Row } from "./feed";
import type { AiringStatus, Anime, EpisodeProgress, LibraryEntry, ListStatus } from "./models";

const MINUTE = 60_000;
const HOUR = 60 * MINUTE;
const DAY = 24 * HOUR;
const DEFAULT = 0.9;
// Local wall-clock time, so calendar-day copy («завтра») holds in any time zone.
const NOW = new Date(2026, 8, 13, 20, 0, 0).getTime();

interface AnimeSpec {
  status?: AiringStatus;
  episodes?: number;
  aired?: number;
  next?: number | null;
}

function anime(id: number, spec: AnimeSpec = {}): Anime {
  const episodes = spec.episodes ?? 12;
  return {
    id,
    title: `Аниме ${id}`,
    originalTitle: `Anime ${id}`,
    posterUrl: `https://poster/${id}.jpg`,
    backdropUrl: null,
    status: spec.status ?? "released",
    episodes,
    episodesAired: spec.aired ?? episodes,
    year: 2026,
    score: 8,
    kind: "tv",
    studios: ["Madhouse"],
    description: null,
    nextEpisodeAt: spec.next ?? null,
  };
}

interface RateSpec {
  status?: ListStatus;
  watched?: number;
  updatedAt?: number;
}

function entry(a: Anime, spec: RateSpec = {}): LibraryEntry {
  return {
    anime: a,
    rate: {
      id: a.id,
      animeId: a.id,
      status: spec.status ?? "watching",
      episodes: spec.watched ?? 0,
      updatedAt: spec.updatedAt ?? NOW,
    },
  };
}

/** A stop at `fraction` of a 1 000 000 ms episode, as Android's `stopped` helper writes it. */
function stopped(animeId: number, episode: number, fraction: number, at = NOW): EpisodeProgress {
  return { animeId, episode, positionMs: Math.round(fraction * 1_000_000), durationMs: 1_000_000, updatedAt: at };
}

type Seed = readonly [LibraryEntry, EpisodeProgress[]?];

function build(seeds: readonly Seed[], threshold = DEFAULT): HomeFeed {
  const rows = new Map<number, EpisodeProgress[]>(
    seeds.map(([e, p]): [number, EpisodeProgress[]] => [e.anime.id, p ?? []]),
  );
  return buildFeed(seeds.map(([e]) => e), (id) => rows.get(id) ?? [], NOW, threshold);
}

const ids = (items: readonly FeedItem[]) => items.map((item) => item.entry.anime.id);

const ONGOING_24_10: AnimeSpec = { status: "ongoing", episodes: 24, aired: 10 };

describe("buildFeed", () => {
  // --- positions kept per episode ---

  it("keeps the card on the episode being watched after a mis-tap on an earlier one", () => {
    const a = anime(1, ONGOING_24_10);
    const feed = build([[entry(a, { watched: 6 }), [stopped(1, 7, 0.4, NOW - 2 * HOUR), stopped(1, 6, 0.01)]]]);
    expect(feed.top?.kind).toBe("continue");
    expect(feed.top?.episode).toBe(7);
  });

  it("makes no card of an episode nobody really started", () => {
    const a = anime(1, ONGOING_24_10);
    const feed = build([[entry(a, { watched: 6 }), [stopped(1, 7, 0.01)]]]);
    expect(feed.continueWatching).toEqual([]);
    expect(feed.top?.kind).toBe("new");
    expect(feed.top?.episode).toBe(7);
  });

  it("orders «Продолжить» by when each title was last watched", () => {
    const older = anime(1, ONGOING_24_10);
    const fresher = anime(2, ONGOING_24_10);
    const feed = build([
      [entry(older, { watched: 6 }), [stopped(1, 7, 0.4, NOW - 2 * DAY)]],
      [entry(fresher, { watched: 3 }), [stopped(2, 4, 0.4, NOW - 5 * MINUTE)]],
    ]);
    expect(ids(feed.continueWatching)).toEqual([2, 1]);
  });

  it("raises a title when any of its episodes is touched, not only the target", () => {
    const revisited = anime(1, ONGOING_24_10);
    const untouched = anime(2, ONGOING_24_10);
    const feed = build([
      [
        entry(revisited, { watched: 5 }),
        [stopped(1, 7, 0.4, NOW - 2 * DAY), stopped(1, 6, 0.3, NOW - 5 * MINUTE)],
      ],
      [entry(untouched, { watched: 3 }), [stopped(2, 4, 0.4, NOW - DAY)]],
    ]);
    expect(ids(feed.continueWatching)).toEqual([1, 2]);
    expect(feed.continueWatching[0]?.episode).toBe(7);
  });

  it("does not carry a mis-tapped title to the head of the row", () => {
    const misTapped = anime(1, ONGOING_24_10);
    const watched = anime(2, ONGOING_24_10);
    const feed = build([
      [
        entry(misTapped, { watched: 6 }),
        [stopped(1, 7, 0.4, NOW - 3 * DAY), stopped(1, 4, 0.005, NOW - MINUTE)],
      ],
      [entry(watched, { watched: 3 }), [stopped(2, 4, 0.4, NOW - DAY)]],
    ]);
    expect(ids(feed.continueWatching)).toEqual([2, 1]);
    expect(feed.continueWatching[feed.continueWatching.length - 1]?.episode).toBe(7);
  });

  it("prefers a later unfinished episode to an earlier one", () => {
    const a = anime(1, ONGOING_24_10);
    const feed = build([[entry(a, { watched: 3 }), [stopped(1, 4, 0.4), stopped(1, 9, 0.2)]]]);
    expect(feed.continueWatching.map((item) => item.episode)).toEqual([9]);
  });

  it("puts an unfinished episode first in «Продолжить» and in the hero", () => {
    const feed = build([[entry(anime(1), { watched: 4 }), [stopped(1, 5, 0.4)]]]);
    expect(feed.continueWatching).toHaveLength(1);
    expect(feed.continueWatching[0]?.episode).toBe(5);
    expect(feed.top?.kind).toBe("continue");
    expect(feed.top?.episode).toBe(5);
  });

  it("obeys the threshold the caller passes", () => {
    const seeds: Seed[] = [[entry(anime(1, ONGOING_24_10), { watched: 5 }), [stopped(1, 6, 0.85)]]];

    const strict = build(seeds, 0.9);
    expect(strict.top?.kind).toBe("continue");
    expect(strict.top?.episode).toBe(6);

    const lenient = build(seeds, 0.8);
    expect(lenient.top?.kind).toBe("new");
    expect(lenient.top?.episode).toBe(7);
  });

  it("moves past an episode watched beyond the threshold before Shikimori hears of it", () => {
    const feed = build([[entry(anime(1), { watched: 4 }), [stopped(1, 5, 0.95)]]]);
    expect(feed.continueWatching).toEqual([]);
    expect(feed.top?.kind).toBe("next");
    expect(feed.top?.episode).toBe(6);
  });

  it("lists ongoing titles with aired episodes ahead of the count as new episodes", () => {
    const ongoing = anime(2, { status: "ongoing", episodes: 24, aired: 7 });
    const caughtUp = anime(3, { status: "ongoing", episodes: 24, aired: 7 });
    const feed = build([[entry(ongoing, { watched: 6 })], [entry(caughtUp, { watched: 7 })]]);
    expect(ids(feed.newEpisodes)).toEqual([2]);
    expect(feed.newEpisodes[0]?.episode).toBe(7);
    expect(feed.newEpisodes[0]?.kind).toBe("new");
  });

  it("lets a new episode beat next-up for the hero when nothing is in progress", () => {
    const ongoing = anime(2, { status: "ongoing", episodes: 24, aired: 7 });
    const released = anime(1);
    const feed = build([[entry(released, { watched: 3 })], [entry(ongoing, { watched: 6 })]]);
    expect(feed.top?.entry.anime.id).toBe(2);
    expect(feed.top?.kind).toBe("new");
  });

  it("sorts next-up released titles by recent list activity", () => {
    const feed = build([
      [entry(anime(1), { watched: 3, updatedAt: NOW - 3 * DAY })],
      [entry(anime(2), { watched: 1, updatedAt: NOW - HOUR })],
    ]);
    expect(ids(feed.nextUp)).toEqual([2, 1]);
    expect(feed.nextUp[0]?.episode).toBe(2);
  });

  it("never puts an announcement into next-up", () => {
    const announced = anime(1, { status: "anons", episodes: 12, aired: 0 });
    const feed = build([[entry(announced, { watched: 0 })]]);
    expect(feed.nextUp).toEqual([]);
  });

  it("lists ongoing titles whose next episode lands within the window as upcoming", () => {
    const soon = anime(2, { status: "ongoing", episodes: 24, aired: 7, next: NOW + 2 * DAY });
    const far = anime(3, { status: "ongoing", episodes: 24, aired: 7, next: NOW + 20 * DAY });
    const feed = build([[entry(soon, { watched: 7 })], [entry(far, { watched: 7 })]]);
    expect(ids(feed.upcoming)).toEqual([2]);
    expect(feed.upcoming[0]?.episode).toBe(8);
  });

  it("fills «В планах» with planned titles and never the hero", () => {
    const feed = build([[entry(anime(9), { status: "planned" })]]);
    expect(feed.planned).toHaveLength(1);
    expect(feed.planned[0]?.episode).toBe(1);
    expect(feed.top).toBeNull();
  });

  it("ignores completed and dropped titles", () => {
    const feed = build([
      [entry(anime(1), { status: "completed", watched: 12 })],
      [entry(anime(2), { status: "dropped", watched: 2 })],
    ]);
    expect(isFeedEmpty(feed)).toBe(true);
  });

  it("drops a fully watched released title from next-up", () => {
    const feed = build([[entry(anime(1, { episodes: 12 }), { watched: 12 })]]);
    expect(feed.nextUp).toEqual([]);
  });

  it("drops a released title finished on this device from next-up too", () => {
    const all = Array.from({ length: 12 }, (_, i) => stopped(1, i + 1, 0.95));
    const feed = build([[entry(anime(1, { episodes: 12 }), { watched: 6 }), all]]);
    expect(feed.nextUp).toEqual([]);
    expect(feed.top).toBeNull();
  });

  it("keeps a rewatcher who reset the count in next-up from the first episode", () => {
    const all = Array.from({ length: 12 }, (_, i) => stopped(1, i + 1, 0.95));
    const feed = build([[entry(anime(1, { episodes: 12 }), { status: "rewatching", watched: 0 }), all]]);
    expect(ids(feed.nextUp)).toEqual([1]);
    expect(feed.nextUp[0]?.episode).toBe(1);
  });

  // --- a position is what puts a title in «Продолжить», whatever the list says ---

  it("leads «Продолжить» with a started episode of a planned title", () => {
    const a = anime(1, { status: "ongoing", episodes: 12, aired: 4 });
    const feed = build([[entry(a, { status: "planned", watched: 0 }), [stopped(1, 2, 0.4)]]]);
    expect(ids(feed.continueWatching)).toEqual([1]);
    expect(feed.continueWatching[0]?.episode).toBe(2);
    expect(feed.top?.kind).toBe("continue");
  });

  it("keeps a title shelved mid-episode in «Продолжить»", () => {
    const a = anime(1, { status: "ongoing", episodes: 12, aired: 4 });
    const feed = build([[entry(a, { status: "on_hold", watched: 1 }), [stopped(1, 2, 0.4)]]]);
    expect(feed.continueWatching.map((item) => item.episode)).toEqual([2]);
  });

  it("orders «Продолжить» by the newest position across every list status", () => {
    const planned = anime(1, ONGOING_24_10);
    const watching = anime(2, ONGOING_24_10);
    const feed = build([
      [entry(planned, { status: "planned", watched: 0 }), [stopped(1, 2, 0.4, NOW - 5 * MINUTE)]],
      [entry(watching, { watched: 3 }), [stopped(2, 4, 0.4, NOW - DAY)]],
    ]);
    expect(ids(feed.continueWatching)).toEqual([1, 2]);
  });

  it("does not offer a planned title being watched as something to plan", () => {
    const a = anime(1, { status: "ongoing", episodes: 12, aired: 4 });
    const feed = build([[entry(a, { status: "planned", watched: 0 }), [stopped(1, 2, 0.4)]]]);
    expect(feed.planned).toEqual([]);
  });

  it("leaves a planned title nobody opened in «В планах» alone", () => {
    const a = anime(1, { status: "ongoing", episodes: 12, aired: 4 });
    const feed = build([[entry(a, { status: "planned", watched: 0 })]]);
    expect(feed.continueWatching).toEqual([]);
    expect(ids(feed.planned)).toEqual([1]);
  });

  it("never offers to continue a title marked completed mid-episode", () => {
    const a = anime(1, ONGOING_24_10);
    const feed = build([[entry(a, { status: "completed", watched: 4 }), [stopped(1, 5, 0.4)]]]);
    expect(feed.continueWatching).toEqual([]);
    expect(feed.top).toBeNull();
  });

  // --- the same rules as map 1 states them for the web ---

  it("keeps a continued title in «Скоро» as well", () => {
    const a = anime(1, { status: "ongoing", episodes: 24, aired: 7, next: NOW + DAY });
    const feed = build([[entry(a, { watched: 6 }), [stopped(1, 7, 0.4)]]]);
    expect(ids(feed.continueWatching)).toEqual([1]);
    expect(feed.newEpisodes).toEqual([]);
    expect(ids(feed.upcoming)).toEqual([1]);
    expect(feed.upcoming[0]?.episode).toBe(8);
  });

  it("keeps the upcoming window open at both ends", () => {
    const soonAt = (id: number, next: number) =>
      entry(anime(id, { status: "ongoing", episodes: 24, aired: 7, next }), { watched: 7 });
    const feed = build([
      [soonAt(1, NOW)],
      [soonAt(2, NOW + UPCOMING_WINDOW_MS)],
      [soonAt(3, NOW + UPCOMING_WINDOW_MS - 1)],
      [soonAt(4, NOW + 1)],
    ]);
    expect(ids(feed.upcoming)).toEqual([4, 3]);
    expect(UPCOMING_WINDOW_MS).toBe(604_800_000);
  });

  it("sorts new episodes by next episode date, falling back to the rate's update", () => {
    const make = (id: number, next: number | null, updatedAt: number) =>
      entry(anime(id, { status: "ongoing", episodes: 24, aired: 7, next }), { watched: 6, updatedAt });
    const feed = build([
      [make(1, NOW + DAY, NOW - 5 * DAY)],
      [make(2, null, NOW - HOUR)],
      [make(3, NOW + 3 * DAY, NOW - 10 * DAY)],
    ]);
    expect(ids(feed.newEpisodes)).toEqual([3, 1, 2]);
  });

  it("sorts «В планах» by the rate's update and starts each at episode 1", () => {
    const feed = build([
      [entry(anime(1), { status: "planned", updatedAt: NOW - 3 * DAY })],
      [entry(anime(2), { status: "planned", updatedAt: NOW - HOUR })],
    ]);
    expect(ids(feed.planned)).toEqual([2, 1]);
    expect(feed.planned.map((item) => [item.episode, item.kind])).toEqual([
      [1, "planned"],
      [1, "planned"],
    ]);
  });

  it("never makes an upcoming title the hero", () => {
    const a = anime(1, { status: "ongoing", episodes: 24, aired: 7, next: NOW + DAY });
    const feed = build([[entry(a, { watched: 7 })]]);
    expect(ids(feed.upcoming)).toEqual([1]);
    expect(feed.top).toBeNull();
    expect(isFeedEmpty(feed)).toBe(false);
  });
});

// --- rows and cards (HomeRowsTest) ---

function show(id: number, spec: AnimeSpec = {}): Anime {
  return anime(id, { status: "ongoing", episodes: 12, aired: 8, ...spec });
}

function watching(id: number, spec: AnimeSpec = {}): LibraryEntry {
  return entry(show(id, spec), { watched: 6 });
}

function item(e: LibraryEntry, episode: number, kind: FeedKind): FeedItem {
  return { entry: e, episode, kind };
}

function feedOf(parts: Partial<HomeFeed>): HomeFeed {
  return { top: null, newEpisodes: [], continueWatching: [], nextUp: [], upcoming: [], planned: [], ...parts };
}

function pos(animeId: number, episode: number, positionMs: number, durationMs = 1_440_000): EpisodeProgress {
  return { animeId, episode, positionMs, durationMs, updatedAt: NOW };
}

function rowsOf(feed: HomeFeed, progress: Record<number, EpisodeProgress[]> = {}): Row[] {
  return feedRows(feed, (id) => progress[id] ?? [], NOW, DEFAULT);
}

function onlyCard(rows: readonly Row[]): Card {
  expect(rows).toHaveLength(1);
  const [row] = rows;
  expect(row?.cards).toHaveLength(1);
  const card = row?.cards[0];
  if (!card) throw new Error("the row has no card");
  return card;
}

describe("feedRows", () => {
  it("keeps the rows in Android's order", () => {
    const titles = rowsOf(
      feedOf({
        top: item(watching(2), 7, "continue"),
        continueWatching: [item(watching(2), 7, "continue")],
        newEpisodes: [item(watching(1), 7, "new")],
        nextUp: [item(watching(3, { status: "released" }), 7, "next")],
        upcoming: [item(watching(4, { next: NOW + DAY }), 9, "upcoming")],
        planned: [item(watching(5), 1, "planned")],
      }),
      { 2: [pos(2, 7, 60_000)] },
    ).map((row) => row.title);

    expect(titles).toEqual(["Новые серии", "Продолжить", "Дальше по списку", "Скоро", "В планах"]);
  });

  it("builds only the rows with items in them", () => {
    const rows = rowsOf(feedOf({ planned: [item(watching(5), 1, "planned")] }));
    expect(rows.map((row) => row.title)).toEqual(["В планах"]);
  });

  it("names the episode on a new-episode card and says nothing else", () => {
    const card = onlyCard(rowsOf(feedOf({ newEpisodes: [item(watching(1), 7, "new")] })));
    expect(card.badge).toBe("7 серия");
    expect(card.subtitle).toBeNull();
    expect(card.progress).toBeNull();
    expect(card.key).toBe("1:7 серия");
  });

  it("carries the title, poster and id of its anime", () => {
    const card = onlyCard(rowsOf(feedOf({ newEpisodes: [item(watching(1), 7, "new")] })));
    expect(card.animeId).toBe(1);
    expect(card.title).toBe("Аниме 1");
    expect(card.posterUrl).toBe("https://poster/1.jpg");
  });

  it("shows how far into a continued episode the viewer is and how much is left", () => {
    const card = onlyCard(
      rowsOf(feedOf({ continueWatching: [item(watching(2), 7, "continue")] }), { 2: [pos(2, 7, 600_000)] }),
    );
    expect(card.badge).toBe("7 серия");
    expect(card.subtitle).toBe("осталось 14 мин");
    expect(card.progress).toBeCloseTo(600_000 / 1_440_000, 3);
  });

  it("describes the badged episode, not the one opened last", () => {
    const card = onlyCard(
      rowsOf(feedOf({ continueWatching: [item(watching(2), 7, "continue")] }), {
        2: [pos(2, 7, 600_000), pos(2, 6, 10_000)],
      }),
    );
    expect(card.badge).toBe("7 серия");
    expect(card.subtitle).toBe("осталось 14 мин");
    expect(card.progress).toBeCloseTo(600_000 / 1_440_000, 3);
  });

  it("drops the strip of an episode past the threshold", () => {
    const card = onlyCard(
      rowsOf(feedOf({ continueWatching: [item(watching(2), 7, "continue")] }), { 2: [pos(2, 7, 1_400_000)] }),
    );
    expect(card.progress).toBeNull();
  });

  it("promises no time for an episode of unknown length", () => {
    const card = onlyCard(
      rowsOf(feedOf({ continueWatching: [item(watching(2), 7, "continue")] }), { 2: [pos(2, 7, 600_000, 0)] }),
    );
    expect(card.badge).toBe("7 серия");
    expect(card.subtitle).toBeNull();
  });

  it("says which day an upcoming episode arrives", () => {
    const card = onlyCard(rowsOf(feedOf({ upcoming: [item(watching(4, { next: NOW + 6 * HOUR }), 9, "upcoming")] })));
    expect(card.badge).toBe("9 серия");
    expect(card.subtitle).toBe("завтра");
  });

  it("still says something about an upcoming episode with no date", () => {
    const card = onlyCard(rowsOf(feedOf({ upcoming: [item(watching(4), 9, "upcoming")] })));
    expect(card.subtitle).toBe("скоро");
  });

  it("offers a planned title's season length instead of an episode number", () => {
    const card = onlyCard(rowsOf(feedOf({ planned: [item(watching(5, { episodes: 24 }), 1, "planned")] })));
    expect(card.badge).toBeNull();
    expect(card.subtitle).toBe("24 серии");
    expect(card.key).toBe("5");
  });

  it("says nothing about a planned title of unknown length", () => {
    const card = onlyCard(rowsOf(feedOf({ planned: [item(watching(5, { episodes: 0, aired: 0 }), 1, "planned")] })));
    expect(card.subtitle).toBeNull();
  });

  it("never joins facts with a middle dot", () => {
    const rows = rowsOf(
      feedOf({
        continueWatching: [item(watching(2), 7, "continue")],
        newEpisodes: [item(watching(1), 7, "new")],
        nextUp: [item(watching(3, { status: "released" }), 7, "next")],
        upcoming: [item(watching(4, { next: NOW + 3 * DAY }), 9, "upcoming")],
        planned: [item(watching(5, { episodes: 24 }), 1, "planned")],
      }),
      { 2: [pos(2, 7, 600_000)] },
    );
    const text = rows.flatMap((row) => [
      row.title,
      ...row.cards.flatMap((card) => [card.badge, card.subtitle].filter((s): s is string => s !== null)),
    ]);
    expect(text.length).toBeGreaterThan(5);
    for (const line of text) expect(line).not.toContain("·");
  });
});

describe("catalogueCard", () => {
  it("says how much of the show there is to watch", () => {
    expect(catalogueCard(show(1, { episodes: 12, aired: 8 })).subtitle).toBe("8 серий");
  });

  it("counts the whole season of a finished show", () => {
    expect(catalogueCard(show(1, { status: "released", episodes: 24, aired: 24 })).subtitle).toBe("24 серии");
  });

  it("says nothing about a title with nothing aired", () => {
    // Android falls back to the studio here; the web contract says null.
    expect(catalogueCard(show(1, { status: "anons", episodes: 12, aired: 0 })).subtitle).toBeNull();
  });

  it("carries no badge and no progress, keyed by the id", () => {
    expect(catalogueCard(show(1))).toEqual({
      key: "1",
      animeId: 1,
      title: "Аниме 1",
      posterUrl: "https://poster/1.jpg",
      badge: null,
      subtitle: "8 серий",
      progress: null,
    });
  });
});

describe("isFeedEmpty", () => {
  it("counts the hero, «Скоро» and «В планах» and nothing else", () => {
    const e = entry(anime(1));
    expect(isFeedEmpty(feedOf({}))).toBe(true);
    expect(isFeedEmpty(feedOf({ top: item(e, 1, "next") }))).toBe(false);
    expect(isFeedEmpty(feedOf({ upcoming: [item(e, 2, "upcoming")] }))).toBe(false);
    expect(isFeedEmpty(feedOf({ planned: [item(e, 1, "planned")] }))).toBe(false);
  });
});
