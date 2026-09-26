import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import type { Anime, EpisodeProgress, LibraryEntry, ListStatus } from "../domain/models";
import type { LibraryState } from "../library/library";
import { ProgressStore } from "../library/progress";
import { rememberDub, rememberedDub, rememberedDubs } from "../player/memory";
import { memoryStorage } from "../test/fakes";
import { SyncError, type SyncClient, type SyncTitles } from "./client";
import { SyncService } from "./service";

const T0 = 1_790_000_000_000;
const MINUTE = 60_000;

type Call = { kind: "get" } | { kind: "post"; titles: SyncTitles; keepalive: boolean };

function fakeClient() {
  const calls: Call[] = [];
  const posts = (): { titles: SyncTitles; keepalive: boolean }[] =>
    calls.flatMap((call) => (call.kind === "post" ? [{ titles: call.titles, keepalive: call.keepalive }] : []));
  const state = { remote: {} as SyncTitles, answer: {} as SyncTitles, fail: 0 };
  const client: SyncClient = {
    get: async () => {
      calls.push({ kind: "get" });
      if (state.fail > 0) {
        state.fail -= 1;
        throw new SyncError("offline");
      }
      return structuredClone(state.remote);
    },
    post: async (titles, options) => {
      calls.push({ kind: "post", titles: structuredClone(titles), keepalive: options?.keepalive === true });
      if (state.fail > 0) {
        state.fail -= 1;
        throw new SyncError("offline");
      }
      return structuredClone(state.answer);
    },
  };
  return { client, calls, posts, state };
}

function fakeLibrary() {
  let entries: LibraryEntry[] | null = null;
  const listeners = new Set<() => void>();
  return {
    state: (): LibraryState => (entries === null ? { kind: "idle" } : { kind: "ready", entries }),
    subscribe: (listener: () => void) => {
      listeners.add(listener);
      return () => {
        listeners.delete(listener);
      };
    },
    entry: (animeId: number) => entries?.find((entry) => entry.anime.id === animeId),
    set(list: [number, ListStatus][]) {
      entries = list.map(([id, status]) => ({
        anime: { id } as unknown as Anime,
        rate: { id: id * 10, animeId: id, status, episodes: 0, updatedAt: 0 },
      }));
      for (const listener of [...listeners]) listener();
    },
  };
}

function row(animeId: number, episode: number, positionMs: number, updatedAt: number): EpisodeProgress {
  return { animeId, episode, positionMs, durationMs: 1_440_000, updatedAt };
}

function setup(options: { account?: number | null; storage?: Storage } = {}) {
  const storage = options.storage ?? memoryStorage();
  const progress = new ProgressStore(storage);
  const fake = fakeClient();
  const library = fakeLibrary();
  const who = { account: options.account === undefined ? 42 : options.account };
  const service = new SyncService({ client: fake.client, progress, library, accountId: () => who.account, storage });
  return { service, progress, library, storage, who, ...fake };
}

/** Runs timers and every promise they settle. */
const settle = (ms = 0) => vi.advanceTimersByTimeAsync(ms);

let stops: (() => void)[] = [];

function started(options: Parameters<typeof setup>[0] = {}) {
  const env = setup(options);
  stops.push(() => env.service.stop());
  return env;
}

beforeEach(() => {
  vi.useFakeTimers({ now: T0 });
});

afterEach(() => {
  for (const stop of stops) stop();
  stops = [];
  vi.useRealTimers();
});

describe("SyncService pull", () => {
  it("reads the document on start and takes positions newer than this browser's", async () => {
    const env = started();
    env.progress.restore([row(1535, 1, 100_000, T0 - 5_000), row(1535, 2, 200_000, T0 - 1_000)]);
    env.state.remote = {
      "1535": {
        eps: {
          "1": { p: 500_000, d: 1_400_000, at: T0 - 2_000 },
          "2": { p: 900_000, d: 1_400_000, at: T0 - 3_000 },
          "3": { p: 300_000, d: 1_400_000, at: T0 - 4_000 },
        },
      },
    };

    env.service.start();
    await settle();

    expect(env.calls[0]).toEqual({ kind: "get" });
    expect(env.progress.of(1535)).toEqual([
      { animeId: 1535, episode: 1, positionMs: 500_000, durationMs: 1_400_000, updatedAt: T0 - 2_000 },
      row(1535, 2, 200_000, T0 - 1_000),
      { animeId: 1535, episode: 3, positionMs: 300_000, durationMs: 1_400_000, updatedAt: T0 - 4_000 },
    ]);
  });

  it("takes a dub newer than the remembered one, including one remembered before stamps", async () => {
    const env = started();
    rememberDub(1, { id: 610, title: "AniLibria.TV" }, env.storage, T0 - 1_000);
    rememberDub(2, { id: 610, title: "AniLibria.TV" }, env.storage, T0 - 9_000);
    const legacy = JSON.parse(env.storage.getItem("kaeru.dubs") ?? "{}") as Record<string, unknown>;
    legacy["3"] = { id: 610, title: "AniLibria.TV" };
    env.storage.setItem("kaeru.dubs", JSON.stringify(legacy));
    env.state.remote = {
      "1": { dub: { id: 1978, title: "Studio Band", at: T0 - 5_000 } },
      "2": { dub: { id: 1978, title: "Studio Band", at: T0 - 5_000 } },
      "3": { dub: { id: 1978, title: "Studio Band", at: 1 } },
      "4": { dub: { id: 1978, title: "Studio Band", at: 1 } },
    };

    env.service.start();
    await settle();

    expect(rememberedDub(1, env.storage)).toEqual({ id: 610, title: "AniLibria.TV" });
    expect(rememberedDub(2, env.storage)).toEqual({ id: 1978, title: "Studio Band" });
    expect(rememberedDub(3, env.storage)).toEqual({ id: 1978, title: "Studio Band" });
    expect(rememberedDubs(env.storage).get(4)).toEqual({ id: 1978, title: "Studio Band", at: 1 });
    // Only this browser's newer dub goes back; what came from the server does not.
    await settle(2 * MINUTE);
    const sentDubs = env.posts().flatMap((post) => Object.entries(post.titles).filter(([, title]) => title.dub !== undefined));
    expect(sentDubs.map(([id]) => id)).toEqual(["1"]);
  });

  it("drops positions a finished title had before the server's tombstone", async () => {
    const env = started();
    env.progress.restore([row(21, 1, 1, T0 - 9_000), row(21, 2, 1, T0 - 1_000), row(5, 1, 1, T0 - 9_000)]);
    env.state.remote = { "21": { gone: T0 - 5_000 } };

    env.service.start();
    await settle();

    expect(env.progress.of(21).map((p) => p.episode)).toEqual([2]);
    expect(env.progress.of(5)).toHaveLength(1);
  });

  it("reads again when the tab comes back after five minutes away, not sooner", async () => {
    const env = started();
    env.service.start();
    await settle();
    const gets = () => env.calls.filter((call) => call.kind === "get").length;
    expect(gets()).toBe(1);

    const visibility = vi.spyOn(document, "visibilityState", "get");
    visibility.mockReturnValue("hidden");
    document.dispatchEvent(new Event("visibilitychange"));
    await settle(4 * MINUTE);
    visibility.mockReturnValue("visible");
    document.dispatchEvent(new Event("visibilitychange"));
    await settle();
    expect(gets()).toBe(1);

    visibility.mockReturnValue("hidden");
    document.dispatchEvent(new Event("visibilitychange"));
    await settle(5 * MINUTE);
    visibility.mockReturnValue("visible");
    document.dispatchEvent(new Event("visibilitychange"));
    await settle();
    expect(gets()).toBe(2);
    visibility.mockRestore();
  });

  it("applies the document a write answers with", async () => {
    const env = started();
    env.service.start();
    await settle();
    env.state.answer = { "1535": { eps: { "7": { p: 42_000, d: 1_400_000, at: T0 + 5 } } } };

    env.progress.put(row(1535, 1, 1_000, T0));
    await settle();

    expect(env.progress.of(1535).map((p) => p.episode)).toEqual([1, 7]);
  });
});

describe("SyncService push", () => {
  it("sends this browser's position changes at most once a minute", async () => {
    const env = started();
    env.service.start();
    await settle();

    env.progress.put(row(1535, 1, 10_000, Date.now()));
    await settle();
    expect(env.posts()).toHaveLength(1);
    expect(env.posts()[0]?.titles).toEqual({ "1535": { eps: { "1": { p: 10_000, d: 1_440_000, at: T0 } } } });

    await settle(10_000);
    env.progress.put(row(1535, 1, 20_000, Date.now()));
    await settle(10_000);
    env.progress.put(row(1535, 1, 30_000, Date.now()));
    await settle(30_000);
    expect(env.posts()).toHaveLength(1);

    await settle(10_000);
    expect(env.posts()).toHaveLength(2);
    expect(env.posts()[1]?.titles).toEqual({ "1535": { eps: { "1": { p: 30_000, d: 1_440_000, at: T0 + 20_000 } } } });

    await settle(5 * MINUTE);
    expect(env.posts()).toHaveLength(2);
  });

  it("sends a dub the viewer settled on", async () => {
    const env = started();
    env.service.start();
    await settle();

    rememberDub(1535, { id: 610, title: "AniLibria.TV" }, env.storage, T0 + 1);
    // Another storage is another browser's business.
    rememberDub(21, { id: 610, title: "AniLibria.TV" }, memoryStorage(), T0 + 1);
    await settle();

    expect(env.posts().map((post) => post.titles)).toEqual([{ "1535": { dub: { id: 610, title: "AniLibria.TV", at: T0 + 1 } } }]);
  });

  it("sends at once when asked (pause, leaving the player, a new episode), whatever the minute says", async () => {
    const env = started();
    env.service.start();
    await settle();
    env.progress.put(row(1535, 1, 10_000, Date.now()));
    await settle();
    env.progress.put(row(1535, 1, 20_000, Date.now()));

    env.service.push();
    await settle();

    expect(env.posts()).toHaveLength(2);
    expect(env.posts()[1]?.keepalive).toBe(false);
  });

  it("closing the player sends the position just saved, in the same moment, with keepalive", async () => {
    const env = started();
    env.service.start();
    await settle();
    // What PlayerController.dispose() does: save the position, then ask for a batch at once.
    env.progress.put(row(1535, 3, 845_000, Date.now()));
    env.service.push({ keepalive: true });
    await settle();

    const last = env.posts().at(-1);
    expect(last?.keepalive).toBe(true);
    expect(last?.titles["1535"]?.eps?.["3"]?.p).toBe(845_000);
  });

  it("sends with keepalive on pagehide and when the tab is hidden", async () => {
    const env = started();
    env.service.start();
    await settle();
    env.progress.put(row(1535, 1, 10_000, Date.now()));
    await settle();

    env.progress.put(row(1535, 1, 20_000, Date.now()));
    window.dispatchEvent(new Event("pagehide"));
    await settle();
    expect(env.posts()).toHaveLength(2);
    expect(env.posts()[1]?.keepalive).toBe(true);

    env.progress.put(row(1535, 1, 30_000, Date.now()));
    const visibility = vi.spyOn(document, "visibilityState", "get").mockReturnValue("hidden");
    document.dispatchEvent(new Event("visibilitychange"));
    await settle();
    visibility.mockRestore();
    expect(env.posts()).toHaveLength(3);
    expect(env.posts()[2]?.keepalive).toBe(true);
  });

  it("sends one batch when the player and the page both say it is going away", async () => {
    const env = started();
    env.service.start();
    await settle();
    env.progress.put(row(1535, 1, 10_000, Date.now()));
    await settle();
    env.progress.put(row(1535, 1, 20_000, Date.now()));

    env.service.push({ keepalive: true });
    window.dispatchEvent(new Event("pagehide"));
    await settle();

    expect(env.posts()).toHaveLength(2);
  });

  it("sends nothing when nothing changed", async () => {
    const env = started();
    env.service.start();
    await settle();

    env.service.push();
    window.dispatchEvent(new Event("pagehide"));
    await settle(5 * MINUTE);

    expect(env.posts()).toEqual([]);
  });

  it("keeps a failed batch and tries it again later", async () => {
    const env = started();
    env.service.start();
    await settle();
    env.state.fail = 1;

    env.progress.put(row(1535, 1, 10_000, Date.now()));
    await settle();
    expect(env.posts()).toHaveLength(1);

    await settle(MINUTE);
    expect(env.posts()).toHaveLength(2);
    expect(env.posts()[1]?.titles).toEqual(env.posts()[0]?.titles);

    await settle(5 * MINUTE);
    expect(env.posts()).toHaveLength(2);
  });

  it("keeps unsent changes across a reload of the page", async () => {
    const storage = memoryStorage();
    const first = started({ storage });
    first.service.start();
    await settle();
    first.state.fail = 10;
    first.progress.put(row(1535, 1, 10_000, Date.now()));
    await settle();
    first.service.stop();

    const second = started({ storage });
    second.service.start();
    await settle();

    expect(second.posts().map((post) => post.titles)).toEqual([{ "1535": { eps: { "1": { p: 10_000, d: 1_440_000, at: T0 } } } }]);
  });

  it("keeps a change made while a batch was on its way", async () => {
    const env = started();
    env.service.start();
    await settle();
    const gate: { release?: () => void } = {};
    const post = env.client.post;
    env.client.post = async (titles, options) => {
      await new Promise<void>((resolve) => {
        gate.release = resolve;
      });
      return post(titles, options);
    };

    env.progress.put(row(1535, 1, 10_000, Date.now()));
    await settle();
    env.progress.put(row(1535, 1, 20_000, Date.now() + 1));
    gate.release?.();
    env.client.post = post;
    await settle(MINUTE);

    expect(env.posts().map((p) => p.titles["1535"]?.eps?.["1"]?.p)).toEqual([10_000, 20_000]);
  });

  it("sends positions this browser had before sync, once per account", async () => {
    const storage = memoryStorage();
    const first = started({ storage });
    first.progress.restore([row(1535, 1, 10_000, T0 - 9_000), row(1535, 2, 10_000, T0 - 9_000)]);
    rememberDub(1535, { id: 610, title: "AniLibria.TV" }, storage, T0 - 9_000);
    first.state.remote = { "1535": { eps: { "2": { p: 1, d: 1, at: T0 - 1_000 } } } };

    first.service.start();
    await settle();

    expect(first.posts().map((post) => post.titles)).toEqual([
      { "1535": { eps: { "1": { p: 10_000, d: 1_440_000, at: T0 - 9_000 } }, dub: { id: 610, title: "AniLibria.TV", at: T0 - 9_000 } } },
    ]);
    first.service.stop();

    const second = started({ storage });
    second.service.start();
    await settle(MINUTE);
    expect(second.posts()).toEqual([]);
  });
});

describe("SyncService finished titles", () => {
  it("sends a tombstone when a title turns «completed» in the list", async () => {
    const env = started();
    env.library.set([[1535, "watching"], [21, "completed"]]);
    env.service.start();
    await settle(MINUTE);
    expect(env.posts()).toEqual([]);

    env.progress.put(row(1535, 12, 1_400_000, Date.now()));
    await settle(MINUTE);
    env.library.set([[1535, "completed"], [21, "completed"]]);
    await settle(MINUTE);

    expect(env.posts().at(-1)?.titles).toEqual({ "1535": { gone: T0 + 2 * MINUTE } });
  });

  it("stops sending positions of a finished title", async () => {
    const env = started();
    env.library.set([[1535, "completed"]]);
    env.service.start();
    await settle();

    env.progress.put(row(1535, 12, 1_400_000, Date.now()));
    await settle(MINUTE);

    expect(env.posts()).toEqual([]);
  });

  it("takes back a tombstone not yet sent when the status changes back", async () => {
    const env = started();
    env.library.set([[1535, "watching"]]);
    env.service.start();
    await settle();
    env.progress.put(row(1535, 1, 1, Date.now()));
    await settle();

    env.library.set([[1535, "completed"]]);
    env.library.set([[1535, "watching"]]);
    await settle(MINUTE);

    expect(env.posts()).toHaveLength(1);
  });

  it("drops local positions once the server confirms the tombstone", async () => {
    const env = started();
    env.library.set([[1535, "watching"]]);
    env.service.start();
    await settle();
    env.progress.restore([row(1535, 1, 1, T0 - 1)]);

    env.state.answer = { "1535": { gone: T0 } };
    env.library.set([[1535, "completed"]]);
    await settle();

    expect(env.progress.of(1535)).toEqual([]);
  });
});

describe("SyncService signed out", () => {
  it("makes no request at all", async () => {
    const env = started({ account: null });
    env.progress.restore([row(1535, 1, 1, T0 - 1)]);
    env.service.start();
    await settle();

    env.progress.put(row(1535, 1, 10_000, Date.now()));
    env.service.push();
    window.dispatchEvent(new Event("pagehide"));
    await settle(10 * MINUTE);

    expect(env.calls).toEqual([]);
  });

  it("drops changes queued for another account", async () => {
    const storage = memoryStorage();
    const first = started({ storage, account: 7 });
    first.service.start();
    await settle();
    first.state.fail = 10;
    first.progress.put(row(1535, 1, 10_000, Date.now()));
    await settle();
    first.service.stop();
    // Signing out clears this browser's positions, as the settings screen does.
    first.progress.clear();

    const second = started({ storage, account: 8 });
    second.service.start();
    await settle(MINUTE);

    expect(second.posts().some((post) => post.titles["1535"]?.eps?.["1"]?.p === 10_000)).toBe(false);
  });

  it("never lets one account's positions reach another who signs in on the same browser", async () => {
    const storage = memoryStorage();
    const a = started({ storage, account: 7 });
    a.service.start();
    await settle();
    a.progress.put(row(1535, 3, 600_000, Date.now()));
    rememberDub(1535, { id: 610, title: "AniLibria.TV" }, storage, Date.now());
    a.state.fail = 10;
    a.progress.put(row(1535, 4, 700_000, Date.now() + 1));
    await settle(MINUTE);
    // The session expires: nothing is cleared on the way out.
    a.service.stop();

    const b = started({ storage, account: 8 });
    expect(b.progress.of(1535)).toEqual([]);
    b.service.start();
    await settle(MINUTE);

    expect(b.progress.of(1535)).toEqual([]);
    expect(rememberedDub(1535, storage)).toBeNull();
    expect(storage.getItem("kaeru.sync.outbox")).toBeNull();
    const sent = JSON.stringify(b.posts());
    expect(sent).not.toContain("1535");
  });

  it("keeps everything when the same account signs back in", async () => {
    const storage = memoryStorage();
    const first = started({ storage, account: 7 });
    first.service.start();
    await settle();
    first.progress.put(row(1535, 3, 600_000, Date.now()));
    rememberDub(1535, { id: 610, title: "AniLibria.TV" }, storage, Date.now());
    await settle();
    first.service.stop();

    const again = started({ storage, account: 7 });
    again.service.start();
    await settle(MINUTE);

    expect(again.progress.of(1535).map((p) => p.positionMs)).toEqual([600_000]);
    expect(rememberedDub(1535, storage)).toEqual({ id: 610, title: "AniLibria.TV" });
    // Already sent once: the one-time upload does not run again.
    expect(again.posts()).toEqual([]);
  });

  it("forgets its listeners once stopped", async () => {
    const env = started();
    env.service.start();
    await settle();
    env.service.stop();

    env.progress.put(row(1535, 1, 10_000, Date.now()));
    window.dispatchEvent(new Event("pagehide"));
    await settle(MINUTE);

    expect(env.posts()).toEqual([]);
  });
});
