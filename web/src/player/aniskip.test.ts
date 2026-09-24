// Vectors: android data/skip/AniSkipMarksTest.kt (Frieren's answer from the spike, the cache rules) and
// AniSkipApi.kt (the request).
import { afterEach, describe, expect, it, vi } from "vitest";
import { createAniSkip } from "./aniskip";
import { NO_MARKS } from "./rules";

const KEY = "kaeru.aniskip";
const DAY_MS = 86_400_000;
const T0 = Date.UTC(2026, 8, 19, 10);
const FILE_MS = 1_560_000;

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

function refusing(): Storage {
  const storage = memoryStorage();
  storage.getItem = () => {
    throw new DOMException("blocked", "SecurityError");
  };
  storage.setItem = () => {
    throw new DOMException("blocked", "SecurityError");
  };
  return storage;
}

interface Sent {
  url: string;
  init: RequestInit | undefined;
}

const json = (body: unknown, status = 200): Response =>
  new Response(JSON.stringify(body), { status, headers: { "Content-Type": "application/json" } });

/** Frieren, episode 1, as AniSkip answered during the Android spike. */
const FRIEREN = {
  found: true,
  results: [
    { interval: { startTime: 3.0, endTime: 93.0 }, skipType: "op", skipId: "a", episodeLength: 1560.0 },
    { interval: { startTime: 1460.0, endTime: 1560.0 }, skipType: "ed", skipId: "b", episodeLength: 1560.0 },
  ],
  message: "",
  statusCode: 200,
};
const FRIEREN_MARKS = {
  opening: { startMs: 3_000, endMs: 93_000 },
  ending: { startMs: 1_460_000, endMs: 1_560_000 },
};

/** An AniSkip that answers with [answer] and remembers every request, over a clock the test moves. */
function service(answer: (call: number) => Response | Promise<Response>, storage: Storage = memoryStorage()) {
  const sent: Sent[] = [];
  const clock = { now: T0 };
  const fetch: typeof globalThis.fetch = async (input, init) => {
    sent.push({ url: String(input), init });
    return answer(sent.length);
  };
  const aniskip = createAniSkip({ fetch, storage, now: () => clock.now });
  return { aniskip, sent, clock, storage };
}

afterEach(() => {
  vi.useRealTimers();
});

describe("createAniSkip", () => {
  it("asks for the four kinds with a literal types[] and the length in whole seconds, with no headers", async () => {
    const { aniskip, sent } = service(() => json(FRIEREN));

    // Rounded rather than cut: 1469.7 s is the 1470 s file.
    await aniskip.marks(52991, 7, 1_469_700);

    expect(sent.map((s) => s.url)).toEqual([
      "https://api.aniskip.com/v2/skip-times/52991/7?types[]=op&types[]=ed&types[]=mixed-op&types[]=mixed-ed&episodeLength=1470",
    ]);
    // A plain GET: nothing that would make the browser send a preflight.
    expect(sent[0]?.init?.headers).toBeUndefined();
    expect(sent[0]?.init?.method).toBeUndefined();
  });

  it("maps openings and endings, mixed ones too, to milliseconds and keeps the first plausible of each", async () => {
    const { aniskip } = service(() =>
      json({
        found: true,
        results: [
          // Past five minutes: somebody's mistake, dropped for the one after it.
          { interval: { startTime: 310, endTime: 400 }, skipType: "op" },
          { interval: { startTime: 3.5, endTime: 93.25 }, skipType: "mixed-op" },
          // Dandadan's «ending» at the start of the episode.
          { interval: { startTime: 5, endTime: 95 }, skipType: "ed" },
          { interval: { startTime: 1460, endTime: 1560 }, skipType: "mixed-ed" },
          { interval: { startTime: 700, endTime: 790 }, skipType: "recap" },
          { interval: { startTime: 10, endTime: 100 }, skipType: "op" },
        ],
      }),
    );

    expect(await aniskip.marks(5114, 1, FILE_MS)).toEqual({
      opening: { startMs: 3_500, endMs: 93_250 },
      ending: { startMs: 1_460_000, endMs: 1_560_000 },
    });
  });

  it("remembers «nobody marked this», a 404 or found: false, and does not ask again", async () => {
    for (const answer of [() => json({ found: false }, 404), () => json({ found: false, results: [] })]) {
      const { aniskip, sent, clock, storage } = service(answer);

      expect(await aniskip.marks(5114, 1, FILE_MS)).toEqual(NO_MARKS);
      clock.now += 6 * DAY_MS;
      expect(await aniskip.marks(5114, 1, FILE_MS)).toEqual(NO_MARKS);

      expect(sent).toHaveLength(1);
      expect(JSON.parse(storage.getItem(KEY) ?? "")).toEqual({
        "5114:1": { lengthS: 1560, at: T0, marks: { opening: null, ending: null } },
      });
    }
  });

  it("answers the same episode from the cache", async () => {
    const { aniskip, sent, clock } = service(() => json(FRIEREN));

    await aniskip.marks(5114, 1, FILE_MS);
    clock.now += 6 * DAY_MS;

    expect(await aniskip.marks(5114, 1, FILE_MS)).toEqual(FRIEREN_MARKS);
    expect(sent).toHaveLength(1);
  });

  it("takes a length within 2 s for the same file and one 3 s off for another", async () => {
    const { aniskip, sent } = service(() => json(FRIEREN));

    await aniskip.marks(5114, 1, FILE_MS);
    expect(await aniskip.marks(5114, 1, 1_558_400)).toEqual(FRIEREN_MARKS);
    expect(await aniskip.marks(5114, 1, 1_562_000)).toEqual(FRIEREN_MARKS);
    expect(sent).toHaveLength(1);

    await aniskip.marks(5114, 1, 1_556_600);
    expect(sent).toHaveLength(2);
    expect(sent[1]?.url).toContain("episodeLength=1557");
  });

  it("asks again once the answer is a week old", async () => {
    let answer = json({ found: false, results: [] });
    const { aniskip, sent, clock, storage } = service(() => answer);

    await aniskip.marks(5114, 1, FILE_MS);
    clock.now += 8 * DAY_MS;
    answer = json(FRIEREN);

    expect(await aniskip.marks(5114, 1, FILE_MS)).toEqual(FRIEREN_MARKS);
    expect(sent).toHaveLength(2);
    expect(JSON.parse(storage.getItem(KEY) ?? "")["5114:1"]).toEqual({ lengthS: 1560, at: T0 + 8 * DAY_MS, marks: FRIEREN_MARKS });
  });

  it("falls back on what it remembers when AniSkip is broken, however old, and writes nothing", async () => {
    const failures: Array<() => Response | Promise<Response>> = [
      () => new Response("", { status: 500 }),
      () => new Response("<html>bad gateway</html>", { status: 200 }),
      () => Promise.reject(new TypeError("Failed to fetch")),
    ];
    for (const failure of failures) {
      let broken = false;
      const { aniskip, sent, clock, storage } = service(() => (broken ? failure() : json(FRIEREN)));
      await aniskip.marks(5114, 1, FILE_MS);
      const remembered = storage.getItem(KEY);
      clock.now += 30 * DAY_MS;
      broken = true;

      expect(await aniskip.marks(5114, 1, FILE_MS)).toEqual(FRIEREN_MARKS);
      expect(sent).toHaveLength(2);
      expect(storage.getItem(KEY)).toBe(remembered);
    }
  });

  it("shows nothing and writes nothing when AniSkip is broken and nothing is remembered", async () => {
    const failures: Array<() => Response | Promise<Response>> = [
      () => new Response("", { status: 500 }),
      () => new Response("<html>bad gateway</html>", { status: 200 }),
      () => Promise.reject(new TypeError("Failed to fetch")),
    ];
    for (const failure of failures) {
      const { aniskip, storage } = service(failure);

      expect(await aniskip.marks(5114, 1, FILE_MS)).toEqual(NO_MARKS);
      expect(storage.getItem(KEY)).toBeNull();
    }
  });

  it("gives up after 5 s with nothing to show", async () => {
    vi.useFakeTimers();
    const hanging: typeof globalThis.fetch = (_input, init) =>
      new Promise((_resolve, reject) => {
        init?.signal?.addEventListener("abort", () => reject(new DOMException("The operation was aborted.", "AbortError")));
      });
    const storage = memoryStorage();
    const aniskip = createAniSkip({ fetch: hanging, storage, now: () => T0 });

    let settled = false;
    const pending = aniskip.marks(5114, 1, FILE_MS).then((marks) => {
      settled = true;
      return marks;
    });

    await vi.advanceTimersByTimeAsync(4_999);
    expect(settled).toBe(false);
    await vi.advanceTimersByTimeAsync(1);
    expect(settled).toBe(true);
    expect(await pending).toEqual(NO_MARKS);
    expect(storage.getItem(KEY)).toBeNull();
  });

  it("still answers when storage throws or holds something broken", async () => {
    const { aniskip, sent } = service(() => json(FRIEREN), refusing());

    expect(await aniskip.marks(5114, 1, FILE_MS)).toEqual(FRIEREN_MARKS);
    expect(await aniskip.marks(5114, 1, FILE_MS)).toEqual(FRIEREN_MARKS);
    expect(sent).toHaveLength(2);

    const broken = memoryStorage();
    broken.setItem(KEY, "{");
    const { aniskip: other, storage } = service(() => json(FRIEREN), broken);
    expect(await other.marks(5114, 1, FILE_MS)).toEqual(FRIEREN_MARKS);
    expect(JSON.parse(storage.getItem(KEY) ?? "")).toEqual({ "5114:1": { lengthS: 1560, at: T0, marks: FRIEREN_MARKS } });
  });

  it("keeps the 200 newest answers", async () => {
    const storage = memoryStorage();
    const old: Record<string, unknown> = {};
    for (let i = 0; i < 200; i += 1) old[`${1000 + i}:1`] = { lengthS: 1440, at: T0 - (200 - i) * 1_000, marks: NO_MARKS };
    storage.setItem(KEY, JSON.stringify(old));
    const { aniskip } = service(() => json(FRIEREN), storage);

    await aniskip.marks(5114, 1, FILE_MS);

    const kept = Object.keys(JSON.parse(storage.getItem(KEY) ?? "") as Record<string, unknown>);
    expect(kept).toHaveLength(200);
    expect(kept).toContain("5114:1");
    expect(kept).not.toContain("1000:1");
    expect(kept).toContain("1001:1");
  });

  it("asks nothing while the file has no length yet", async () => {
    const { aniskip, sent } = service(() => json(FRIEREN));

    for (const durationMs of [0, 400, Number.NaN, Number.POSITIVE_INFINITY]) {
      expect(await aniskip.marks(5114, 1, durationMs)).toEqual(NO_MARKS);
    }
    expect(sent).toEqual([]);
  });
});
