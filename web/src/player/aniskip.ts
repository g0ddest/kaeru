import { NO_MARKS, plausibleMarks } from "./rules";
import type { Interval, SkipMarks } from "./rules";

/**
 * The community's opening and ending marks for one file (AniSkipMarks.kt). Never throws and never
 * waits long: marks are a convenience, and an episode plays perfectly well without them.
 */
export interface AniSkip {
  marks(animeId: number, episode: number, durationMs: number): Promise<SkipMarks>;
}

/** One JSON object across titles: "<anime>:<episode>" → the last answer about that episode. */
const KEY = "kaeru.aniskip";
// A free public service with CORS open to any page (checked 2026-09-24): nothing may depend on it.
const BASE_URL = "https://api.aniskip.com/v2/skip-times";
// Openings and endings, and the «mixed» kinds, which is what they are called when they run over the
// episode rather than standing apart from it.
const TYPES = ["op", "ed", "mixed-op", "mixed-ed"] as const;
/** SkipModule.SKIP_TIMEOUT: an episode never waits on whether it can offer a button. */
const TIMEOUT_MS = 5_000;
/** How long an answer about one episode stands before it is worth asking again. */
const FRESH_MS = 7 * 86_400_000;
// A manifest's summed segments and a container's duration disagree by a second or so for one file.
const LENGTH_TOLERANCE_S = 2;
/** How many episodes the store remembers: the oldest answers go first. */
const KEEP = 200;
/** How AniSkip says «nobody has marked this one», the commonest true answer there is. */
const NOT_FOUND = 404;

interface Entry {
  /** The length the marks were asked for, in whole seconds. */
  lengthS: number;
  /** When it was asked, which is the whole of the once-a-week rule. */
  at: number;
  marks: SkipMarks;
}

function browserStorage(): Storage | null {
  try {
    return window.localStorage;
  } catch {
    // Blocked site data makes the getter itself throw.
    return null;
  }
}

function record(value: unknown): Record<string, unknown> | null {
  return typeof value === "object" && value !== null && !Array.isArray(value) ? (value as Record<string, unknown>) : null;
}

function finite(value: unknown): value is number {
  return typeof value === "number" && Number.isFinite(value);
}

// Undefined is an entry this module cannot read; null is a kind nobody marked.
function readInterval(value: unknown): Interval | null | undefined {
  if (value === null) return null;
  const row = record(value);
  const startMs = row?.["startMs"];
  const endMs = row?.["endMs"];
  return finite(startMs) && finite(endMs) ? { startMs, endMs } : undefined;
}

function readEntry(value: unknown): Entry | null {
  const row = record(value);
  const lengthS = row?.["lengthS"];
  const at = row?.["at"];
  const marks = record(row?.["marks"]);
  const opening = readInterval(marks?.["opening"]);
  const ending = readInterval(marks?.["ending"]);
  if (!finite(lengthS) || !finite(at) || opening === undefined || ending === undefined) return null;
  return { lengthS, at, marks: { opening, ending } };
}

function readAll(storage: Storage | null): Map<string, Entry> {
  const entries = new Map<string, Entry>();
  try {
    const raw = storage?.getItem(KEY);
    for (const [key, value] of Object.entries((raw ? record(JSON.parse(raw)) : null) ?? {})) {
      const entry = readEntry(value);
      if (entry !== null) entries.set(key, entry);
    }
  } catch {
    // A store some other build broke reads as empty: the marks are asked for again.
  }
  return entries;
}

function writeAll(storage: Storage | null, entries: Map<string, Entry>): void {
  const newest = [...entries].sort(([, a], [, b]) => b.at - a.at).slice(0, KEEP);
  try {
    storage?.setItem(KEY, JSON.stringify(Object.fromEntries(newest)));
  } catch {
    // Quota or blocked storage: the answer still serves this call, and the next one asks again.
  }
}

// Seconds with a fraction, as AniSkip speaks; undefined for a result that is not an interval.
function readResult(value: unknown): { kind: "op" | "ed"; startMs: number; endMs: number } | undefined {
  const row = record(value);
  const type = row?.["skipType"];
  const interval = record(row?.["interval"]);
  const start = interval?.["startTime"];
  const end = interval?.["endTime"];
  const kind = type === "op" || type === "mixed-op" ? "op" : type === "ed" || type === "mixed-ed" ? "ed" : null;
  if (kind === null || !finite(start) || !finite(end)) return undefined;
  return { kind, startMs: Math.round(start * 1_000), endMs: Math.round(end * 1_000) };
}

/**
 * The answer as the player can use it: milliseconds, and only intervals that could belong to a file of
 * this length. Several people may have marked one episode; the first plausible mark of each kind wins.
 */
function marksFor(body: unknown, durationMs: number): SkipMarks {
  const root = record(body);
  // Not an object at all is a broken answer, not an unmarked episode.
  if (root === null) throw new Error("AniSkip answered with no object");
  if (root["found"] !== true) return NO_MARKS;
  const results = Array.isArray(root["results"]) ? root["results"] : [];
  const raw = results.map(readResult).filter((result) => result !== undefined);
  return plausibleMarks(raw, durationMs);
}

/**
 * AniSkip over `kaeru.aniskip`, per browser like Android's skip_marks table. The Shikimori id goes
 * out as the MyAnimeList id, which it is for everything Kodik plays.
 *
 * * **One question per file per week**, «nobody marked this» included, or every unmarked episode
 *   would cost a request on every play.
 * * **The length is the question.** The engine's duration goes out with the request and every
 *   interval is measured against it, so one dub never inherits another dub's timings.
 * * **Nothing here can stop an episode.** A refusal, a timeout, no network: the answer is whatever
 *   is remembered for this file, however old, and otherwise nothing at all.
 */
export function createAniSkip(deps: { fetch?: typeof fetch; storage?: Storage; now?: () => number } = {}): AniSkip {
  const send: typeof fetch = deps.fetch ?? ((input, init) => fetch(input, init));
  const now = deps.now ?? Date.now;

  async function ask(animeId: number, episode: number, lengthS: number, durationMs: number): Promise<SkipMarks | null> {
    // A literal types[]: URLSearchParams would send types%5B%5D.
    const types = TYPES.map((type) => `types[]=${type}`).join("&");
    const controller = new AbortController();
    const timer = setTimeout(() => controller.abort(), TIMEOUT_MS);
    try {
      // No headers, so the browser sends it as is, without a preflight.
      const response = await send(`${BASE_URL}/${animeId}/${episode}?${types}&episodeLength=${lengthS}`, {
        signal: controller.signal,
      });
      if (response.status === NOT_FOUND) return NO_MARKS;
      // A service that is merely broken says nothing about the episode.
      if (!response.ok) return null;
      return marksFor(JSON.parse(await response.text()) as unknown, durationMs);
    } catch {
      return null;
    } finally {
      clearTimeout(timer);
    }
  }

  return {
    async marks(animeId, episode, durationMs) {
      // Rounded rather than cut: half a second either side is the same file. No length yet is no
      // question: the answer would be for some other file.
      const lengthS = Math.round(durationMs / 1_000);
      if (!Number.isFinite(lengthS) || lengthS <= 0) return NO_MARKS;

      const storage = deps.storage ?? browserStorage();
      const key = `${animeId}:${episode}`;
      const entry = readAll(storage).get(key);
      // A remembered length a second or two off is this file; three off is another dub's.
      const cached = entry !== undefined && Math.abs(entry.lengthS - lengthS) <= LENGTH_TOLERANCE_S ? entry : null;
      if (cached !== null && now() - cached.at < FRESH_MS) return cached.marks;

      const answered = await ask(animeId, episode, lengthS, durationMs);
      if (answered === null) return cached?.marks ?? NO_MARKS;

      // Read again: another tab may have written while the request was out. Under the length this
      // file already has, so one that wandered by a second stays one file.
      const all = readAll(storage);
      all.set(key, { lengthS: cached?.lengthS ?? lengthS, at: now(), marks: answered });
      writeAll(storage, all);
      return answered;
    },
  };
}
