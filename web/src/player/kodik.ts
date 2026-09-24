import { ApiError } from "../api/http";
import type { authorized } from "../auth/session";
import { RELAY_URL } from "../config";

/** One dub or subtitle track of a title, as the worker lists it in Kodik's own order. */
export interface Translation {
  id: number;
  title: string;
  type: "voice" | "subtitles";
  /** Episodes the track has; null when Kodik does not say. */
  episodesCount: number | null;
}

/** One quality's HLS playlist: Kodik has no master playlist, so a quality is a separate source. */
export interface StreamLink {
  quality: number;
  url: string;
}

/** Signed links of one episode, best quality first; they expire within hours. */
export interface KodikStream {
  links: StreamLink[];
  translationId: number;
}

/** `nowhere` is the player's own verdict after walking every dub; the worker never says it. */
export type KodikFailure =
  | "title" | "episode" | "nowhere" | "token" | "upstream" | "parser"
  | "unavailable" | "throttled" | "offline" | "unknown";

export class KodikError extends Error {
  readonly kind: KodikFailure;

  constructor(kind: KodikFailure) {
    super(`Kodik ${kind}`);
    this.name = "KodikError";
    this.kind = kind;
  }
}

export interface Kodik {
  translations(animeId: number): Promise<Translation[]>;
  resolve(animeId: number, translationId: number, episode: number): Promise<KodikStream>;
}

// The worker walks several Kodik pages per answer; these bound a worker that went silent.
const TRANSLATIONS_TIMEOUT_MS = 20_000;
const RESOLVE_TIMEOUT_MS = 25_000;

// The worker's own `error` values that name a failure the player tells apart.
const KINDS: ReadonlySet<string> = new Set<KodikFailure>(["title", "episode", "token", "upstream", "parser", "unavailable"]);

function record(value: unknown): Record<string, unknown> | null {
  return typeof value === "object" && value !== null && !Array.isArray(value) ? (value as Record<string, unknown>) : null;
}

function parseJson(text: string): unknown {
  try {
    return JSON.parse(text) as unknown;
  } catch {
    // The worker's rate limit and method refusals are plain text.
    return null;
  }
}

/** A non-2xx answer as the error the player acts on. Told apart by the JSON `error`, not the status. */
function refusal(status: number, body: unknown, onClosed: (nickname: string) => void): Error {
  const error = record(body)?.["error"];
  // authorized refreshes the token and calls once more on exactly this.
  if (status === 401 || error === "sign_in") return new ApiError(401, body);
  if (status === 403 && error === "not_allowed") {
    const nickname = record(body)?.["nickname"];
    onClosed(typeof nickname === "string" ? nickname : "");
    return new KodikError("unavailable");
  }
  if (status === 429) return new KodikError("throttled");
  return new KodikError(typeof error === "string" && KINDS.has(error) ? (error as KodikFailure) : "unknown");
}

function readTranslation(value: unknown): Translation | null {
  const row = record(value);
  const id = row?.["id"];
  const title = row?.["title"];
  const count = row?.["episodesCount"];
  if (typeof id !== "number" || !Number.isInteger(id) || typeof title !== "string") return null;
  // Only the fields the player uses: the worker's mediaId and mediaHash never leave this module.
  return {
    id,
    title,
    type: row?.["type"] === "subtitles" ? "subtitles" : "voice",
    episodesCount: typeof count === "number" && Number.isInteger(count) && count >= 0 ? count : null,
  };
}

function readTranslations(body: unknown): Translation[] {
  const rows = record(body)?.["translations"];
  // A 200 nobody can read means the worker and this page no longer agree on the shape.
  if (!Array.isArray(rows)) throw new KodikError("parser");
  return rows.map(readTranslation).filter((track): track is Translation => track !== null);
}

// KodikClient.kt: Kodik hands out http:// and protocol-relative links; the page is https.
function secure(url: string): string {
  if (url.startsWith("//")) return `https:${url}`;
  if (url.startsWith("http://")) return `https://${url.slice("http://".length)}`;
  return url;
}

function readStream(body: unknown, asked: number): KodikStream {
  const root = record(body);
  const urls = root?.["urls"];
  const links: StreamLink[] = [];
  for (const value of Array.isArray(urls) ? urls : []) {
    const row = record(value);
    const quality = row?.["quality"];
    const url = row?.["url"];
    if (typeof quality !== "number" || !Number.isFinite(quality) || quality <= 0) continue;
    if (typeof url !== "string" || url.trim() === "") continue;
    // One source per quality: the first the worker gave wins.
    if (links.some((link) => link.quality === quality)) continue;
    links.push({ quality, url: secure(url.trim()) });
  }
  if (links.length === 0) throw new KodikError("parser");
  links.sort((a, b) => b.quality - a.quality);
  const used = root?.["translationId"];
  return { links, translationId: typeof used === "number" && Number.isInteger(used) ? used : asked };
}

/**
 * The worker's Kodik routes (`/kodik/translations`, `/kodik/resolve`) with the Shikimori bearer the
 * worker's allow-list checks. Every failure comes out as a KodikError, except the 401 that
 * `authorized` refreshes on.
 */
export function createKodik(deps: {
  authorized: typeof authorized;
  /** An account taken off the allow-list: the session closes with the worker's nickname. */
  onClosed: (nickname: string) => void;
  fetch?: typeof fetch;
}): Kodik {
  const send: typeof fetch = deps.fetch ?? ((input, init) => fetch(input, init));

  async function get(path: string, query: Record<string, string>, timeoutMs: number, token: string): Promise<unknown> {
    const controller = new AbortController();
    const timer = setTimeout(() => controller.abort(), timeoutMs);
    let status: number;
    let text: string;
    try {
      // The bearer and nothing else: the worker's preflight allows only authorization and content-type.
      const response = await send(`${RELAY_URL}/kodik/${path}?${new URLSearchParams(query)}`, {
        headers: { Authorization: `Bearer ${token}` },
        signal: controller.signal,
      });
      status = response.status;
      text = await response.text();
    } catch {
      // A worker that never finished answering is the source failing, not this device's network.
      throw new KodikError(controller.signal.aborted ? "upstream" : "offline");
    } finally {
      clearTimeout(timer);
    }
    const body = parseJson(text);
    if (status < 200 || status > 299) throw refusal(status, body, deps.onClosed);
    return body;
  }

  return {
    translations: (animeId) =>
      deps.authorized(async (token) =>
        readTranslations(await get("translations", { anime: String(animeId) }, TRANSLATIONS_TIMEOUT_MS, token)),
      ),
    // No season: the apps never ask for one above 1, and the worker defaults to 1.
    resolve: (animeId, translationId, episode) =>
      deps.authorized(async (token) =>
        readStream(
          await get(
            "resolve",
            { anime: String(animeId), translation: String(translationId), episode: String(episode) },
            RESOLVE_TIMEOUT_MS,
            token,
          ),
          translationId,
        ),
      ),
  };
}
