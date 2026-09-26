import { whoami } from "./whitelist";

/**
 * Viewing sync: where each episode stopped, the dub chosen for a title, and the titles watched
 * «украдкой» — one JSON document per Shikimori user in KV (docs/superpowers/specs/2026-09-26-kaeru-sync-design.md).
 * Every field carries the device's own `at`; the newer one wins, field by field and episode by episode.
 */
export interface Position { p: number; d: number; at: number }
export interface Dub { id: number; title: string; at: number }
export interface Secret { on: boolean; watched: number; at: number }
export interface Title { dub?: Dub; eps?: Record<string, Position>; secret?: Secret; gone?: number }
export interface SyncDocument { v: 1; titles: Record<string, Title> }

export const EMPTY: SyncDocument = { v: 1, titles: {} };
export const TOMBSTONE_TTL_MS = 30 * 24 * 60 * 60 * 1000;
const MAX_EPISODES = 30;
const MAX_TITLES = 1000;
const MAX_BODY = 256 * 1024;

function newest<T extends { at: number }>(a: T | undefined, b: T | undefined): T | undefined {
  if (a === undefined) return b;
  if (b === undefined) return a;
  return b.at > a.at ? b : a;
}

function latestAt(title: Title): number {
  let at = Math.max(title.dub?.at ?? 0, title.secret?.at ?? 0);
  for (const position of Object.values(title.eps ?? {})) at = Math.max(at, position.at);
  return at;
}

function mergeTitle(stored: Title | undefined, incoming: Title): Title {
  // A tombstone stands against anything written before it, and gives way to anything after.
  const gone = Math.max(stored?.gone ?? 0, incoming.gone ?? 0);
  const keep = <T extends { at: number }>(value: T | undefined) => (value !== undefined && value.at > gone ? value : undefined);
  const eps: Record<string, Position> = {};
  for (const source of [stored?.eps ?? {}, incoming.eps ?? {}]) {
    for (const [episode, position] of Object.entries(source)) {
      const chosen = keep(newest(eps[episode], position));
      if (chosen !== undefined) eps[episode] = chosen;
    }
  }
  const newestEps = Object.entries(eps).sort((a, b) => b[1].at - a[1].at).slice(0, MAX_EPISODES);
  const title: Title = {};
  const dub = keep(newest(stored?.dub, incoming.dub));
  if (dub !== undefined) title.dub = dub;
  if (newestEps.length > 0) title.eps = Object.fromEntries(newestEps);
  const secret = keep(newest(stored?.secret, incoming.secret));
  if (secret !== undefined) title.secret = secret;
  if (Object.keys(title).length === 0 && gone > 0) return { gone };
  return title;
}

/** The stored document with `incoming` folded in, and anything past its time let go. */
export function merge(stored: SyncDocument, incoming: Record<string, Title>, now: number): SyncDocument {
  const titles: Record<string, Title> = {};
  for (const id of new Set([...Object.keys(stored.titles), ...Object.keys(incoming)])) {
    const title = incoming[id] === undefined ? stored.titles[id] : mergeTitle(stored.titles[id], incoming[id]);
    if (title === undefined) continue;
    if (title.gone !== undefined && Object.keys(title).length === 1 && now - title.gone > TOMBSTONE_TTL_MS) continue;
    titles[id] = title;
  }
  const kept = Object.entries(titles).sort((a, b) => Math.max(latestAt(b[1]), b[1].gone ?? 0) - Math.max(latestAt(a[1]), a[1].gone ?? 0)).slice(0, MAX_TITLES);
  return { v: 1, titles: Object.fromEntries(kept) };
}

const finite = (value: unknown): value is number => typeof value === "number" && Number.isFinite(value);

/** What a client sent, checked field by field; null when it is not the shape §3 of the spec names. */
export function parseTitles(raw: unknown): Record<string, Title> | null {
  if (typeof raw !== "object" || raw === null) return null;
  const titles = (raw as { titles?: unknown }).titles;
  if (typeof titles !== "object" || titles === null || Array.isArray(titles)) return null;
  const parsed: Record<string, Title> = {};
  for (const [id, value] of Object.entries(titles as Record<string, unknown>)) {
    if (!/^\d{1,9}$/.test(id) || typeof value !== "object" || value === null) return null;
    const source = value as Record<string, unknown>;
    const title: Title = {};
    if (source.dub !== undefined) {
      const dub = source.dub as Record<string, unknown>;
      if (!finite(dub?.id) || typeof dub.title !== "string" || dub.title.length > 200 || !finite(dub.at)) return null;
      title.dub = { id: dub.id, title: dub.title, at: dub.at };
    }
    if (source.eps !== undefined) {
      if (typeof source.eps !== "object" || source.eps === null) return null;
      title.eps = {};
      for (const [episode, raw] of Object.entries(source.eps as Record<string, unknown>)) {
        const position = raw as Record<string, unknown>;
        if (!/^\d{1,5}$/.test(episode) || !finite(position?.p) || !finite(position.d) || !finite(position.at)) return null;
        title.eps[episode] = { p: Math.max(0, position.p), d: Math.max(0, position.d), at: position.at };
      }
    }
    if (source.secret !== undefined) {
      const secret = source.secret as Record<string, unknown>;
      if (typeof secret?.on !== "boolean" || !finite(secret.watched) || !finite(secret.at)) return null;
      title.secret = { on: secret.on, watched: Math.max(0, Math.floor(secret.watched)), at: secret.at };
    }
    if (source.gone !== undefined) {
      if (!finite(source.gone)) return null;
      title.gone = source.gone;
    }
    parsed[id] = title;
  }
  return parsed;
}

function json(body: unknown, status = 200): Response {
  return new Response(JSON.stringify(body), { status, headers: { "content-type": "application/json; charset=utf-8" } });
}

async function read(env: Cloudflare.Env, key: string): Promise<SyncDocument> {
  const stored = await env.SYNC.get<SyncDocument>(key, "json");
  return stored !== null && stored.v === 1 && typeof stored.titles === "object" ? stored : EMPTY;
}

/**
 * `GET /sync` and `POST /sync`, for any Shikimori user: the web whitelist does not apply here —
 * the apps' viewers sync too. The token only says whose document it is.
 */
export async function handleSync(request: Request, env: Cloudflare.Env, now = Date.now()): Promise<Response> {
  const header = request.headers.get("Authorization") ?? "";
  const token = header.startsWith("Bearer ") ? header.slice(7).trim() : "";
  if (token === "") return json({ error: "sign_in" }, 401);
  const viewer = await whoami(token, (input, init) => fetch(input, init), Date.now);
  if (viewer === "unavailable") return json({ error: "unavailable" }, 502);
  if (viewer === null) return json({ error: "sign_in" }, 401);
  const key = `u:${viewer.id}`;

  if (request.method === "GET") {
    return json({ titles: merge(await read(env, key), {}, now).titles });
  }
  const text = await request.text();
  if (text.length > MAX_BODY) return json({ error: "parameters" }, 400);
  let body: unknown;
  try { body = JSON.parse(text); } catch { return json({ error: "parameters" }, 400); }
  const incoming = parseTitles(body);
  if (incoming === null) return json({ error: "parameters" }, 400);
  const merged = merge(await read(env, key), incoming, now);
  await env.SYNC.put(key, JSON.stringify(merged));
  return json({ titles: merged.titles });
}
