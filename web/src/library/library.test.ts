// Rules: android/src/main/java/app/kaeru/domain/playback/MarkEpisodeWatched.kt,
// MarkEpisodeUnwatched.kt and data/library/ShikimoriLibraryRepository.kt; contract decisions 6 and 7.
import { act, renderHook } from "@testing-library/react";
import { describe, expect, it } from "vitest";
import { ApiError, NetworkError } from "../api/http";
import type { Shikimori } from "../api/shikimori";
import type { authorized } from "../auth/session";
import type { Anime, EpisodeProgress, ListStatus, UserRate } from "../domain/models";
import { DETAILS_PER_LOAD, DETAILS_TTL_MS, Library, useLibrary } from "./library";
import { ProgressStore } from "./progress";

const NOW = Date.parse("2026-09-24T12:00:00Z");
const HOUR = 3_600_000;

function memoryStorage(): Storage {
  const data = new Map<string, string>();
  return {
    get length() {
      return data.size;
    },
    clear: () => data.clear(),
    getItem: (key: string) => data.get(key) ?? null,
    key: (index: number) => [...data.keys()][index] ?? null,
    removeItem: (key: string) => {
      data.delete(key);
    },
    setItem: (key: string, value: string) => {
      data.set(key, String(value));
    },
  };
}

function anime(id: number, spec: Partial<Anime> = {}): Anime {
  return {
    id,
    title: `Аниме ${id}`,
    originalTitle: `Anime ${id}`,
    posterUrl: `https://shikimori.io/posters/${id}.jpg`,
    backdropUrl: null,
    status: "released",
    episodes: 12,
    episodesAired: 12,
    year: 2024,
    score: 8.1,
    kind: "tv",
    studios: [],
    description: null,
    nextEpisodeAt: null,
    ...spec,
  };
}

function ongoing(id: number, spec: Partial<Anime> = {}): Anime {
  return anime(id, { status: "ongoing", episodes: 24, episodesAired: 7, ...spec });
}

function rate(animeId: number, status: ListStatus, episodes: number): UserRate {
  return { id: 100 + animeId, animeId, status, episodes, updatedAt: 1 };
}

function stopped(animeId: number, episode: number): EpisodeProgress {
  return { animeId, episode, positionMs: 300_000, durationMs: 1_440_000, updatedAt: 1 };
}

function deferred(): { promise: Promise<void>; resolve: () => void } {
  let resolve: () => void = () => undefined;
  const promise = new Promise<void>((done) => {
    resolve = done;
  });
  return { promise, resolve };
}

/** Lets every queued promise callback run. */
function settle(): Promise<void> {
  return new Promise((resolve) => setTimeout(resolve, 0));
}

function fieldsText(fields: { status?: ListStatus; episodes?: number }): string {
  return Object.entries(fields)
    .map(([name, value]) => `${name}=${String(value)}`)
    .join(" ");
}

/** Shikimori as far as the library sees it: rates, cards, details and the two writes. */
class FakeShikimori {
  rates: UserRate[] = [];
  cards: Anime[] = [];
  readonly fullCards = new Map<number, Anime>();
  readonly calls: string[] = [];
  /** Titles whose details fail; each entry is used up by one request. */
  detailsFailures: number[] = [];
  /** A failure for the list read; used up by one load. */
  ratesFailure: unknown = null;
  /** The list read waits for this when set. */
  ratesHold: Promise<void> | null = null;
  /** Details requests wait for this when set. */
  detailsHold: Promise<void> | null = null;
  /** One entry per write, in order; a write waits for its entry before it answers. */
  holds: Array<Promise<void> | null> = [];
  /** One entry per write, in order; a truthy entry is thrown by that write. */
  failures: unknown[] = [];
  private nextId = 900;

  writes(): string[] {
    return this.calls.filter((call) => call.startsWith("POST") || call.startsWith("PATCH"));
  }

  detailsCalls(): number[] {
    return this.calls.filter((call) => call.startsWith("details")).map((call) => Number(call.split(" ")[1]));
  }

  api(): Shikimori {
    const unused = async (): Promise<never> => {
      throw new Error("not used by the library");
    };
    return {
      whoami: unused,
      search: unused,
      popularNow: unused,
      popularInSeason: unused,
      userRates: async (userId, token) => {
        this.calls.push(`rates ${userId} ${token}`);
        if (this.ratesHold) await this.ratesHold;
        const failure = this.ratesFailure;
        this.ratesFailure = null;
        if (failure) throw failure;
        return this.rates.map((r) => ({ ...r }));
      },
      byIds: async (ids) => {
        this.calls.push(`cards ${ids.join(",")}`);
        return this.cards.filter((card) => ids.includes(card.id));
      },
      details: async (id) => {
        this.calls.push(`details ${id}`);
        if (this.detailsHold) await this.detailsHold;
        const failing = this.detailsFailures.indexOf(id);
        if (failing >= 0) {
          this.detailsFailures.splice(failing, 1);
          throw new NetworkError("Failed to fetch");
        }
        const card = this.fullCards.get(id) ?? this.cards.find((c) => c.id === id);
        if (!card) throw new ApiError(404);
        return card;
      },
      createRate: async (token, userId, animeId, fields) => {
        this.calls.push(`POST ${userId}/${animeId} ${fieldsText(fields)} ${token}`);
        await this.answer();
        const created: UserRate = {
          id: this.nextId++,
          animeId,
          status: fields.status,
          episodes: fields.episodes ?? 0,
          updatedAt: 5,
        };
        this.rates.push(created);
        return { ...created };
      },
      updateRate: async (token, rateId, fields) => {
        this.calls.push(`PATCH ${rateId} ${fieldsText(fields)} ${token}`);
        await this.answer();
        const index = this.rates.findIndex((r) => r.id === rateId);
        const current = this.rates[index];
        if (!current) throw new ApiError(404);
        const updated: UserRate = { ...current, ...fields, updatedAt: 5 };
        this.rates[index] = updated;
        return { ...updated };
      },
    };
  }

  private async answer(): Promise<void> {
    const hold = this.holds.shift();
    if (hold) await hold;
    const failure = this.failures.shift();
    if (failure) throw failure;
  }
}

const signedIn: typeof authorized = (call) => call("tok");

function setup(options: { account?: () => number | null } = {}) {
  const server = new FakeShikimori();
  const progress = new ProgressStore(memoryStorage());
  let clock = NOW;
  const library = new Library({
    shikimori: server.api(),
    authorized: signedIn,
    accountId: options.account ?? (() => 42),
    progress,
    now: () => clock,
  });
  return {
    server,
    progress,
    library,
    later(ms: number) {
      clock += ms;
    },
  };
}

/** A loaded library holding one title with the given rate. */
async function holding(title: Anime, status: ListStatus, episodes: number) {
  const s = setup();
  s.server.cards = [title];
  s.server.rates = [rate(title.id, status, episodes)];
  await s.library.load();
  s.server.calls.length = 0;
  return s;
}

describe("Library.load", () => {
  it("starts idle, then joins the account's rates with their cards", async () => {
    const { server, library } = setup();
    server.cards = [anime(1), anime(2)];
    server.rates = [rate(1, "watching", 3), rate(2, "planned", 0)];
    expect(library.state()).toEqual({ kind: "idle" });

    const loading = library.load();
    expect(library.state()).toEqual({ kind: "loading", entries: null });
    await loading;

    expect(server.calls).toEqual(["rates 42 tok", "cards 1,2"]);
    expect(library.state()).toEqual({
      kind: "ready",
      entries: [
        { anime: anime(1), rate: rate(1, "watching", 3) },
        { anime: anime(2), rate: rate(2, "planned", 0) },
      ],
    });
    expect(library.entry(2)?.rate.status).toBe("planned");
    expect(library.entry(3)).toBeUndefined();
  });

  it("is ready and empty for an empty list, without asking for cards", async () => {
    const { server, library } = setup();
    await library.load();
    expect(server.calls).toEqual(["rates 42 tok"]);
    expect(library.state()).toEqual({ kind: "ready", entries: [] });
  });

  it("skips a rate whose card Shikimori did not return", async () => {
    const { server, library } = setup();
    server.cards = [anime(1)];
    server.rates = [rate(1, "watching", 3), rate(2, "watching", 1)];
    await library.load();
    expect(library.entry(2)).toBeUndefined();
    expect(library.state().kind).toBe("ready");
  });

  it("stays idle and asks nothing while signed out", async () => {
    const { server, library } = setup({ account: () => null });
    await library.load();
    expect(library.state()).toEqual({ kind: "idle" });
    expect(server.calls).toEqual([]);
  });

  it("shares one read between callers that ask at once", async () => {
    const { server, library } = setup();
    await Promise.all([library.load(), library.load()]);
    expect(server.calls).toEqual(["rates 42 tok"]);
  });

  it("explains a failed first read and keeps the list a failed reread had", async () => {
    const { server, library } = setup();
    server.cards = [anime(1)];
    server.rates = [rate(1, "watching", 3)];
    server.ratesFailure = new NetworkError("offline");
    await library.load();
    expect(library.state()).toEqual({ kind: "error", message: "Нет соединения. Проверьте интернет", entries: null });

    await library.load();
    expect(library.state().kind).toBe("ready");

    server.ratesFailure = new ApiError(503);
    const again = library.load();
    expect(library.state()).toEqual({
      kind: "loading",
      entries: [{ anime: anime(1), rate: rate(1, "watching", 3) }],
    });
    await again;
    expect(library.state()).toEqual({
      kind: "error",
      message: "Shikimori недоступен, попробуйте позже",
      entries: [{ anime: anime(1), rate: rate(1, "watching", 3) }],
    });
  });

  it("never shows one account's list under another", async () => {
    let account: number | null = 42;
    const { server, library } = setup({ account: () => account });
    server.cards = [anime(1)];
    server.rates = [rate(1, "watching", 3)];
    await library.load();

    account = 7;
    server.rates = [];
    const loading = library.load();
    expect(library.state()).toEqual({ kind: "loading", entries: null });
    expect(library.entry(1)).toBeUndefined();
    await loading;
    expect(server.calls).toContain("rates 7 tok");
    expect(library.state()).toEqual({ kind: "ready", entries: [] });

    account = null;
    await library.load();
    expect(library.state()).toEqual({ kind: "idle" });
  });

  it("keeps this tab's value for a title written to while the list was in flight", async () => {
    const { server, library } = await holding(anime(1), "watching", 3);
    const gate = deferred();
    server.ratesHold = gate.promise;

    const reading = library.load();
    await settle();
    await library.markWatched(anime(1), 5);
    // The list in flight was read before the mark landed.
    server.rates = [rate(1, "watching", 3)];
    gate.resolve();
    await reading;

    expect(library.entry(1)?.rate.episodes).toBe(5);
  });
});

describe("details for ongoing titles being watched", () => {
  it("fetches details only for watching and rewatching titles still airing, and merges them", async () => {
    const { server, library } = setup();
    server.cards = [ongoing(1), ongoing(2), ongoing(3), anime(4), ongoing(5)];
    server.rates = [
      rate(1, "watching", 3),
      rate(2, "rewatching", 1),
      rate(3, "planned", 0),
      rate(4, "watching", 2),
      rate(5, "completed", 24),
    ];
    const airs = NOW + 26 * HOUR;
    server.fullCards.set(1, ongoing(1, {
      nextEpisodeAt: airs,
      studios: ["Madhouse"],
      description: "Эльфийка-маг переживает своих спутников.",
      backdropUrl: "https://shikimori.io/system/screenshots/original/1.jpg",
    }));

    await library.load();

    expect(server.detailsCalls()).toEqual([1, 2]);
    expect(library.entry(1)?.anime).toEqual(ongoing(1, {
      nextEpisodeAt: airs,
      studios: ["Madhouse"],
      description: "Эльфийка-маг переживает своих спутников.",
      backdropUrl: "https://shikimori.io/system/screenshots/original/1.jpg",
    }));
    expect(library.entry(1)?.rate).toEqual(rate(1, "watching", 3));
  });

  it("is ready with the list before the details arrive", async () => {
    const { server, library } = setup();
    server.cards = [ongoing(1)];
    server.rates = [rate(1, "watching", 3)];
    server.fullCards.set(1, ongoing(1, { nextEpisodeAt: NOW + HOUR }));
    const seen: Array<number | null | undefined> = [];
    library.subscribe(() => {
      if (library.state().kind === "ready") seen.push(library.entry(1)?.anime.nextEpisodeAt);
    });

    await library.load();

    expect(seen[0]).toBeNull();
    expect(seen.at(-1)).toBe(NOW + HOUR);
  });

  it("keeps details for six hours, across rereads of the list", async () => {
    const { server, library, later } = setup();
    server.cards = [ongoing(1)];
    server.rates = [rate(1, "watching", 3)];
    server.fullCards.set(1, ongoing(1, { nextEpisodeAt: NOW + HOUR, studios: ["MAPPA"] }));
    await library.load();

    later(DETAILS_TTL_MS - 1);
    server.cards = [ongoing(1, { episodesAired: 8 })];
    await library.load();
    expect(server.detailsCalls()).toEqual([1]);
    expect(library.entry(1)?.anime.episodesAired).toBe(8);
    expect(library.entry(1)?.anime.studios).toEqual(["MAPPA"]);
    expect(library.entry(1)?.anime.nextEpisodeAt).toBe(NOW + HOUR);

    later(1);
    await library.load();
    expect(server.detailsCalls()).toEqual([1, 1]);
  });

  it(`fetches at most ${DETAILS_PER_LOAD} per load: never fetched first, then the oldest`, async () => {
    const { server, library, later } = setup();
    const first = Array.from({ length: 25 }, (_, i) => i + 1);
    server.cards = first.map((id) => ongoing(id));
    server.rates = first.map((id) => rate(id, "watching", 1));
    await library.load();
    expect(server.detailsCalls()).toEqual(first);

    // Title 26 joins at the top of the list and gets its details an hour later.
    later(HOUR);
    server.cards = [ongoing(26), ...server.cards];
    server.rates = [rate(26, "watching", 1), ...server.rates];
    server.calls.length = 0;
    await library.load();
    expect(server.detailsCalls()).toEqual([26]);

    // Everything is stale now; title 27 is new. 26 was fetched last, so it waits for the next load.
    later(DETAILS_TTL_MS);
    server.cards = [...server.cards, ongoing(27)];
    server.rates = [...server.rates, rate(27, "watching", 1)];
    server.calls.length = 0;
    await library.load();
    expect(server.detailsCalls()).toEqual([27, ...first.slice(0, 24)]);
  });

  it("ignores a failed details request and asks again on the next load", async () => {
    const { server, library } = setup();
    server.cards = [ongoing(1), ongoing(2)];
    server.rates = [rate(1, "watching", 3), rate(2, "watching", 1)];
    server.fullCards.set(2, ongoing(2, { nextEpisodeAt: NOW + HOUR }));
    server.detailsFailures = [1];

    await library.load();

    expect(library.state().kind).toBe("ready");
    expect(library.entry(1)?.anime).toEqual(ongoing(1));
    expect(library.entry(2)?.anime.nextEpisodeAt).toBe(NOW + HOUR);

    await library.load();
    expect(server.detailsCalls()).toEqual([1, 2, 1]);
  });

  it("stops asking for details once another account signs in", async () => {
    let account: number | null = 42;
    const { server, library } = setup({ account: () => account });
    server.cards = [ongoing(1), ongoing(2)];
    server.rates = [rate(1, "watching", 3), rate(2, "watching", 1)];
    const gate = deferred();
    server.detailsHold = gate.promise;

    const loading = library.load();
    await settle();
    account = 7;
    gate.resolve();
    await loading;

    expect(server.detailsCalls()).toEqual([1]);
  });

  it("keeps a rate written while its details were in flight", async () => {
    const { server, library } = setup();
    server.cards = [ongoing(1)];
    server.rates = [rate(1, "watching", 3)];
    server.fullCards.set(1, ongoing(1, { nextEpisodeAt: NOW + HOUR }));
    const gate = deferred();
    server.detailsHold = gate.promise;

    const loading = library.load();
    await settle();
    expect(library.state().kind).toBe("ready");
    await library.markWatched(ongoing(1), 4);
    gate.resolve();
    await loading;

    expect(library.entry(1)?.rate.episodes).toBe(4);
    expect(library.entry(1)?.anime.nextEpisodeAt).toBe(NOW + HOUR);
  });

  it("keeps details that arrived while a write was in flight", async () => {
    const { server, library } = setup();
    server.cards = [ongoing(1)];
    server.rates = [rate(1, "watching", 3)];
    server.fullCards.set(1, ongoing(1, { nextEpisodeAt: NOW + HOUR, studios: ["MAPPA"] }));
    const details = deferred();
    const write = deferred();
    server.detailsHold = details.promise;
    server.holds = [write.promise];

    const loading = library.load();
    await settle();
    const marking = library.markWatched(ongoing(1), 4);
    await settle();
    details.resolve();
    await loading;
    write.resolve();
    await marking;

    expect(library.entry(1)?.rate.episodes).toBe(4);
    expect(library.entry(1)?.anime.studios).toEqual(["MAPPA"]);
    expect(library.entry(1)?.anime.nextEpisodeAt).toBe(NOW + HOUR);
  });
});

describe("Library.setStatus", () => {
  it("adds a title that is in no list with a POST of the status alone", async () => {
    const s = setup();
    await s.library.load();
    const title = anime(9);

    const adding = s.library.setStatus(title, "planned");
    expect(s.library.entry(9)?.rate.status).toBe("planned");
    await adding;

    expect(s.server.writes()).toEqual(["POST 42/9 status=planned tok"]);
    expect(s.library.entry(9)).toEqual({
      anime: title,
      rate: { id: 900, animeId: 9, status: "planned", episodes: 0, updatedAt: NOW },
    });
    expect(s.library.state()).toEqual({ kind: "ready", entries: [s.library.entry(9)] });
  });

  it("PATCHes only the status and keeps the count", async () => {
    const { server, library } = await holding(anime(1), "watching", 7);
    await library.setStatus(anime(1), "on_hold");
    expect(server.writes()).toEqual(["PATCH 101 status=on_hold tok"]);
    expect(library.entry(1)?.rate).toMatchObject({ status: "on_hold", episodes: 7 });
  });

  it("sends nothing when the status is already that", async () => {
    const { server, library } = await holding(anime(1), "watching", 7);
    await library.setStatus(anime(1), "watching");
    expect(server.calls).toEqual([]);
  });

  it("puts the old status back and rethrows when Shikimori refuses", async () => {
    const { server, library } = await holding(anime(1), "watching", 7);
    server.failures = [new ApiError(500)];

    const changing = library.setStatus(anime(1), "dropped");
    expect(library.entry(1)?.rate.status).toBe("dropped");
    await expect(changing).rejects.toMatchObject({ status: 500 });

    expect(library.entry(1)?.rate).toEqual(rate(1, "watching", 7));
  });

  it("refuses to write for nobody", async () => {
    const s = setup({ account: () => null });
    await expect(s.library.setStatus(anime(9), "planned")).rejects.toBeInstanceOf(ApiError);
    expect(s.library.entry(9)).toBeUndefined();
    expect(s.server.writes()).toEqual([]);
  });
});

describe("Library.markWatched", () => {
  it("adds a title that is in no list as watching with the count, in one POST", async () => {
    const s = setup();
    await s.library.load();
    await expect(s.library.markWatched(anime(9), 3)).resolves.toEqual({ suggestCompleted: false });
    expect(s.server.writes()).toEqual(["POST 42/9 status=watching episodes=3 tok"]);
    expect(s.library.entry(9)?.rate).toMatchObject({ id: 900, status: "watching", episodes: 3 });
  });

  it.each<ListStatus>(["planned", "on_hold"])("moves a %s title to watching, then raises the count", async (status) => {
    const { server, library } = await holding(anime(1), status, 2);
    await library.markWatched(anime(1), 4);
    expect(server.writes()).toEqual(["PATCH 101 status=watching tok", "PATCH 101 episodes=4 tok"]);
    expect(library.entry(1)?.rate).toMatchObject({ status: "watching", episodes: 4 });
  });

  it("moves a planned title to watching even when the count already covers the episode", async () => {
    const { server, library } = await holding(anime(1), "planned", 5);
    await library.markWatched(anime(1), 4);
    expect(server.writes()).toEqual(["PATCH 101 status=watching tok"]);
    expect(library.entry(1)?.rate).toMatchObject({ status: "watching", episodes: 5 });
  });

  it.each<ListStatus>(["watching", "rewatching", "completed", "dropped"])(
    "leaves %s as it is and sends only the count",
    async (status) => {
      const { server, library } = await holding(anime(1), status, 2);
      await library.markWatched(anime(1), 4);
      expect(server.writes()).toEqual(["PATCH 101 episodes=4 tok"]);
      expect(library.entry(1)?.rate).toMatchObject({ status, episodes: 4 });
    },
  );

  it("never lowers the count", async () => {
    const { server, library } = await holding(anime(1), "watching", 6);
    const marking = library.markWatched(anime(1), 4);
    expect(library.entry(1)?.rate.episodes).toBe(6);
    await expect(marking).resolves.toEqual({ suggestCompleted: false });
    expect(server.writes()).toEqual([]);
    expect(library.entry(1)?.rate.episodes).toBe(6);
  });

  it("clamps the count to the announced length, and not when the length is unknown", async () => {
    const clamped = await holding(anime(1, { episodes: 12 }), "watching", 10);
    await clamped.library.markWatched(anime(1, { episodes: 12 }), 13);
    expect(clamped.server.writes()).toEqual(["PATCH 101 episodes=12 tok"]);

    const open = await holding(ongoing(2, { episodes: 0, episodesAired: 30 }), "watching", 10);
    await open.library.markWatched(ongoing(2, { episodes: 0, episodesAired: 30 }), 30);
    expect(open.server.writes()).toEqual(["PATCH 102 episodes=30 tok"]);
  });

  it("suggests completing only when the last announced episode is newly counted", async () => {
    const twelve = anime(1, { episodes: 12 });
    const finale = await holding(twelve, "watching", 11);
    expect(await finale.library.markWatched(twelve, 12)).toEqual({ suggestCompleted: true });

    const again = await holding(twelve, "watching", 12);
    expect(await again.library.markWatched(twelve, 12)).toEqual({ suggestCompleted: false });

    const open = ongoing(2, { episodes: 0 });
    const unknown = await holding(open, "watching", 11);
    expect(await unknown.library.markWatched(open, 12)).toEqual({ suggestCompleted: false });

    const film = anime(3, { episodes: 1 });
    const fresh = setup();
    await fresh.library.load();
    expect(await fresh.library.markWatched(film, 1)).toEqual({ suggestCompleted: true });
  });

  it("shows the new count before Shikimori answers", async () => {
    const { server, library } = await holding(anime(1), "watching", 2);
    const gate = deferred();
    server.holds = [gate.promise];

    const marking = library.markWatched(anime(1), 5);
    expect(library.entry(1)?.rate.episodes).toBe(5);
    gate.resolve();
    await marking;
    expect(library.entry(1)?.rate.episodes).toBe(5);
  });

  it("puts the old count back and rethrows when Shikimori refuses", async () => {
    const { server, library } = await holding(anime(1), "watching", 2);
    server.failures = [new ApiError(422)];
    await expect(library.markWatched(anime(1), 5)).rejects.toMatchObject({ status: 422 });
    expect(library.entry(1)?.rate).toEqual(rate(1, "watching", 2));
  });

  it("keeps the status that landed when only the count is refused", async () => {
    const { server, library } = await holding(anime(1), "planned", 0);
    server.failures = [null, new NetworkError("offline")];
    await expect(library.markWatched(anime(1), 1)).rejects.toBeInstanceOf(NetworkError);
    expect(library.entry(1)?.rate).toMatchObject({ status: "watching", episodes: 0 });
  });
});

describe("Library.markUnwatched", () => {
  it("lowers the count to the episode before, keeps the status and forgets later positions", async () => {
    const { server, library, progress } = await holding(anime(1), "completed", 7);
    for (const episode of [4, 5, 6]) progress.put(stopped(1, episode));

    await library.markUnwatched(anime(1), 5);

    expect(server.writes()).toEqual(["PATCH 101 episodes=4 tok"]);
    expect(library.entry(1)?.rate).toMatchObject({ status: "completed", episodes: 4 });
    expect(progress.of(1).map((p) => p.episode)).toEqual([4]);
  });

  it("does nothing for an episode that is not counted, or episode 0", async () => {
    const { server, library, progress } = await holding(anime(1), "watching", 3);
    progress.put(stopped(1, 4));

    const undoLater = await library.markUnwatched(anime(1), 4);
    const undoZero = await library.markUnwatched(anime(1), 0);
    await undoLater();
    await undoZero();

    expect(server.writes()).toEqual([]);
    expect(library.entry(1)?.rate.episodes).toBe(3);
    expect(progress.of(1)).toEqual([stopped(1, 4)]);
  });

  it("keeps the count and the positions when Shikimori refuses", async () => {
    const { server, library, progress } = await holding(anime(1), "watching", 7);
    progress.put(stopped(1, 6));
    server.failures = [new ApiError(500)];

    const unmarking = library.markUnwatched(anime(1), 5);
    expect(library.entry(1)?.rate.episodes).toBe(4);
    await expect(unmarking).rejects.toMatchObject({ status: 500 });

    expect(library.entry(1)?.rate.episodes).toBe(7);
    expect(progress.of(1)).toEqual([stopped(1, 6)]);
  });

  it("undo restores the previous count, not the tapped episode, and the forgotten positions", async () => {
    const { server, library, progress } = await holding(anime(1), "watching", 7);
    progress.put(stopped(1, 5));
    progress.put(stopped(1, 6));

    const undo = await library.markUnwatched(anime(1), 5);
    expect(library.entry(1)?.rate.episodes).toBe(4);
    expect(progress.of(1)).toEqual([]);

    await undo();

    expect(server.writes()).toEqual(["PATCH 101 episodes=4 tok", "PATCH 101 episodes=7 tok"]);
    expect(library.entry(1)?.rate.episodes).toBe(7);
    expect(progress.of(1)).toEqual([stopped(1, 5), stopped(1, 6)]);
  });
});

describe("write order", () => {
  it("sends a title's writes one after another, in the order they were made", async () => {
    const { server, library } = await holding(anime(1), "watching", 2);
    const first = deferred();
    server.holds = [first.promise];

    const slow = library.setStatus(anime(1), "on_hold");
    const quick = library.setStatus(anime(1), "dropped");
    await settle();
    expect(server.writes()).toEqual(["PATCH 101 status=on_hold tok"]);
    expect(library.entry(1)?.rate.status).toBe("dropped");

    first.resolve();
    await Promise.all([slow, quick]);
    expect(server.writes()).toEqual(["PATCH 101 status=on_hold tok", "PATCH 101 status=dropped tok"]);
    expect(library.entry(1)?.rate.status).toBe("dropped");
    expect(server.rates[0]?.status).toBe("dropped");
  });

  it("does not hold one title's write behind another title's", async () => {
    const s = setup();
    s.server.cards = [anime(1), anime(2)];
    s.server.rates = [rate(1, "watching", 2), rate(2, "watching", 2)];
    await s.library.load();
    const gate = deferred();
    s.server.holds = [gate.promise];

    const slow = s.library.markWatched(anime(1), 3);
    await s.library.markWatched(anime(2), 3);
    expect(s.library.entry(2)?.rate.episodes).toBe(3);

    gate.resolve();
    await slow;
    expect(s.server.writes()).toEqual(["PATCH 101 episodes=3 tok", "PATCH 102 episodes=3 tok"]);
  });

  it("goes on with the next write after a refused one", async () => {
    const { server, library } = await holding(anime(1), "watching", 2);
    server.failures = [new ApiError(500)];

    const refused = library.markWatched(anime(1), 3);
    const next = library.markWatched(anime(1), 4);
    await expect(refused).rejects.toBeInstanceOf(ApiError);
    await next;

    expect(server.writes()).toEqual(["PATCH 101 episodes=3 tok", "PATCH 101 episodes=4 tok"]);
    expect(library.entry(1)?.rate.episodes).toBe(4);
  });
});

describe("useLibrary", () => {
  it("re-renders with each state the library passes through", async () => {
    const { server, library } = setup();
    server.cards = [anime(1)];
    server.rates = [rate(1, "watching", 3)];
    const { result } = renderHook(() => useLibrary(library));
    expect(result.current).toEqual({ kind: "idle" });

    await act(async () => {
      await library.load();
    });
    expect(result.current).toEqual({ kind: "ready", entries: [{ anime: anime(1), rate: rate(1, "watching", 3) }] });

    await act(async () => {
      await library.markWatched(anime(1), 4);
    });
    expect(result.current.kind === "ready" && result.current.entries[0]?.rate.episodes).toBe(4);
  });
});
