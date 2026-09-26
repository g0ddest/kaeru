import { ApiError } from "../api/http";
import type { authorized } from "../auth/session";
import { RELAY_URL } from "../config";

/**
 * The worker's `/sync` document (infra/relay/src/sync.ts; spec 2026-09-26-kaeru-sync-design.md §2):
 * per anime id, where each episode stopped, the chosen dub and a tombstone for a finished title.
 * Every `at` is the device's own clock in ms, and the newer one wins, field by field and episode by
 * episode. `secret` («Смотреть украдкой») is not read here yet.
 */
export interface SyncPosition {
  p: number;
  d: number;
  at: number;
}

export interface SyncDub {
  id: number;
  title: string;
  at: number;
}

export interface SyncTitle {
  dub?: SyncDub;
  eps?: Record<string, SyncPosition>;
  gone?: number;
}

export type SyncTitles = Record<string, SyncTitle>;

export type SyncFailure = "offline" | "throttled" | "unavailable" | "parameters" | "parser" | "unknown";

export class SyncError extends Error {
  readonly kind: SyncFailure;

  constructor(kind: SyncFailure) {
    super(`Sync ${kind}`);
    this.name = "SyncError";
    this.kind = kind;
  }
}

export interface SyncClient {
  get(): Promise<SyncTitles>;
  /** Sends a batch; the answer is the whole merged document. */
  post(titles: SyncTitles, options?: { keepalive?: boolean }): Promise<SyncTitles>;
}

const TIMEOUT_MS = 20_000;
/** Browsers refuse a keepalive request whose body, with the others in flight, is over 64 KB. */
const KEEPALIVE_LIMIT = 60_000;

function record(value: unknown): Record<string, unknown> | null {
  return typeof value === "object" && value !== null && !Array.isArray(value) ? (value as Record<string, unknown>) : null;
}

const finite = (value: unknown): value is number => typeof value === "number" && Number.isFinite(value);

function parseJson(text: string): unknown {
  try {
    return JSON.parse(text) as unknown;
  } catch {
    // The worker's rate limit is plain text.
    return null;
  }
}

/** A non-2xx answer; the 401 goes out as the ApiError `authorized` refreshes on. */
function refusal(status: number, body: unknown): Error {
  const error = record(body)?.["error"];
  if (status === 401 || error === "sign_in") return new ApiError(401, body);
  if (status === 429) return new SyncError("throttled");
  if (error === "unavailable") return new SyncError("unavailable");
  if (error === "parameters") return new SyncError("parameters");
  return new SyncError("unknown");
}

function readTitle(value: unknown): SyncTitle | null {
  const source = record(value);
  if (source === null) return null;
  const title: SyncTitle = {};
  const dub = record(source["dub"]);
  if (dub !== null && finite(dub["id"]) && typeof dub["title"] === "string" && finite(dub["at"])) {
    title.dub = { id: dub["id"], title: dub["title"], at: dub["at"] };
  }
  const eps = record(source["eps"]);
  if (eps !== null) {
    const read: Record<string, SyncPosition> = {};
    for (const [episode, raw] of Object.entries(eps)) {
      const position = record(raw);
      if (!/^\d{1,5}$/.test(episode) || position === null) continue;
      const { p, d, at } = position;
      if (finite(p) && finite(d) && finite(at)) read[episode] = { p, d, at };
    }
    if (Object.keys(read).length > 0) title.eps = read;
  }
  if (finite(source["gone"])) title.gone = source["gone"];
  return title;
}

/** Whatever of the document this build can read; a title or field it cannot is skipped. */
function readTitles(body: unknown): SyncTitles {
  const titles = record(record(body)?.["titles"]);
  // A 200 nobody can read means the worker and this page no longer agree on the shape.
  if (titles === null) throw new SyncError("parser");
  const read: SyncTitles = {};
  for (const [id, value] of Object.entries(titles)) {
    if (!/^\d{1,9}$/.test(id)) continue;
    const title = readTitle(value);
    if (title !== null) read[id] = title;
  }
  return read;
}

/** `GET /sync` and `POST /sync` with the Shikimori bearer; failures are SyncErrors except the 401. */
export function createSyncClient(deps: { authorized: typeof authorized; fetch?: typeof fetch }): SyncClient {
  const send: typeof fetch = deps.fetch ?? ((input, init) => fetch(input, init));

  async function call(token: string, body: string | null, keepalive: boolean): Promise<SyncTitles> {
    const controller = new AbortController();
    const timer = setTimeout(() => controller.abort(), TIMEOUT_MS);
    // The bearer and content-type and nothing else: the worker's preflight allows only those.
    const headers: Record<string, string> = { Authorization: `Bearer ${token}` };
    const init: RequestInit = { method: body === null ? "GET" : "POST", headers, signal: controller.signal };
    if (body !== null) {
      headers["Content-Type"] = "application/json";
      init.body = body;
      if (keepalive && body.length <= KEEPALIVE_LIMIT) init.keepalive = true;
    }
    let status: number;
    let text: string;
    try {
      const response = await send(`${RELAY_URL}/sync`, init);
      status = response.status;
      text = await response.text();
    } catch {
      throw new SyncError("offline");
    } finally {
      clearTimeout(timer);
    }
    const parsed = parseJson(text);
    if (status < 200 || status > 299) throw refusal(status, parsed);
    return readTitles(parsed);
  }

  return {
    get: () => deps.authorized((token) => call(token, null, false)),
    post: (titles, options) => {
      const body = JSON.stringify({ titles });
      return deps.authorized((token) => call(token, body, options?.keepalive === true));
    },
  };
}
