import { whoami } from "./whitelist";

/**
 * Viewing sync: where each episode stopped, the dub chosen for a title, and the titles watched
 * «украдкой» — one JSON document per Shikimori user, one row in D1 (migrations/0002;
 * docs/superpowers/specs/2026-09-26-kaeru-sync-design.md). A save is one read and one write; the
 * row's `version` makes a save that raced another device's start over instead of losing it.
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
const SAVE_ATTEMPTS = 3;

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

/** What the first version kept for one viewer in four tables (migrations/0001), in the wire shape. */
async function legacy(db: D1Database, user: number, now: number, only?: number[]): Promise<Record<string, Title>> {
  const titles: Record<string, Title> = {};
  const title = (anime: number) => (titles[String(anime)] ??= {});
  // A write is answered with the titles it wrote, not the whole list: read on every minute's save,
  // a list of a few hundred titles would spend D1's daily reads in a few dozen episodes.
  const scope = only === undefined ? "" : " AND anime IN (SELECT value FROM json_each(?3))";
  const ids = JSON.stringify(only ?? []);
  const scoped = (sql: string) => db.prepare(only === undefined ? sql : sql + scope.replace("?3", "?2"));
  const bind = (statement: D1PreparedStatement) => (only === undefined ? statement.bind(user) : statement.bind(user, ids));
  const [positions, dubs, secrets, gone] = await db.batch([
    bind(scoped("SELECT anime, episode, p, d, at FROM positions WHERE user = ?1")),
    bind(scoped("SELECT anime, id, title, at FROM dubs WHERE user = ?1")),
    bind(scoped('SELECT anime, "on", watched, at FROM secrets WHERE user = ?1')),
    bind(scoped(`SELECT anime, at FROM gone WHERE user = ?1 AND at > ${now - TOMBSTONE_TTL_MS}`)),
  ]);
  for (const row of positions.results as { anime: number; episode: number; p: number; d: number; at: number }[]) {
    (title(row.anime).eps ??= {})[String(row.episode)] = { p: row.p, d: row.d, at: row.at };
  }
  for (const row of dubs.results as { anime: number; id: number; title: string; at: number }[]) {
    title(row.anime).dub = { id: row.id, title: row.title, at: row.at };
  }
  for (const row of secrets.results as { anime: number; on: number; watched: number; at: number }[]) {
    title(row.anime).secret = { on: row.on === 1, watched: row.watched, at: row.at };
  }
  for (const row of gone.results as { anime: number; at: number }[]) {
    if (titles[String(row.anime)] === undefined) titles[String(row.anime)] = { gone: row.at };
  }
  return titles;
}

/** The viewer's document and its version; the first read moves the first version's rows into it. */
async function load(db: D1Database, user: number, now: number): Promise<{ doc: SyncDocument; version: number }> {
  const row = await db.prepare("SELECT doc, version FROM documents WHERE user = ?1").bind(user).first<{ doc: string; version: number }>();
  if (row !== null) {
    try {
      const doc = JSON.parse(row.doc) as SyncDocument;
      if (doc.v === 1 && typeof doc.titles === "object" && doc.titles !== null) return { doc, version: row.version };
    } catch { /* an unreadable document starts over below */ }
    return { doc: EMPTY, version: row.version };
  }
  const moved = merge(EMPTY, await legacy(db, user, now), now);
  // Once: the document takes the rows over (or starts empty), and the old tables forget this
  // viewer — from then on every read is this one query.
  await db.batch([
    db.prepare("INSERT OR IGNORE INTO documents (user, doc, version) VALUES (?1, ?2, 1)").bind(user, JSON.stringify(moved)),
    ...["positions", "dubs", "secrets", "gone"].map((table) => db.prepare(`DELETE FROM ${table} WHERE user = ?1`).bind(user)),
  ]);
  return load(db, user, now);
}

/** Writes `doc` if nobody else has since `version`; false means another save got in first. */
async function store(db: D1Database, user: number, doc: SyncDocument, version: number): Promise<boolean> {
  const text = JSON.stringify(doc);
  const result = version === 0
    ? await db.prepare("INSERT OR IGNORE INTO documents (user, doc, version) VALUES (?1, ?2, 1)").bind(user, text).run()
    : await db.prepare("UPDATE documents SET doc = ?2, version = version + 1 WHERE user = ?1 AND version = ?3").bind(user, text, version).run();
  return result.meta.changes === 1;
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
  const db = env.SYNC_DB;

  if (request.method === "GET") {
    const { doc } = await load(db, viewer.id, now);
    return json({ titles: merge(doc, {}, now).titles });
  }
  const text = await request.text();
  if (text.length > MAX_BODY) return json({ error: "parameters" }, 400);
  let body: unknown;
  try { body = JSON.parse(text); } catch { return json({ error: "parameters" }, 400); }
  const incoming = parseTitles(body);
  if (incoming === null) return json({ error: "parameters" }, 400);
  for (let attempt = 0; attempt < SAVE_ATTEMPTS; attempt += 1) {
    const { doc, version } = await load(db, viewer.id, now);
    const merged = merge(doc, incoming, now);
    if (await store(db, viewer.id, merged, version)) {
      // Only what was written goes back: the client merges it, and the rest it already has.
      const written = Object.fromEntries(Object.keys(incoming).filter((id) => merged.titles[id] !== undefined).map((id) => [id, merged.titles[id]]));
      return json({ titles: written });
    }
  }
  return json({ error: "unavailable" }, 502);
}
