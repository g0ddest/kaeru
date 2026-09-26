import { whoami } from "./whitelist";

/**
 * Viewing sync: where each episode stopped, the dub chosen for a title, and the titles watched
 * «украдкой» — rows per Shikimori user in D1 (migrations/0001_sync.sql;
 * docs/superpowers/specs/2026-09-26-kaeru-sync-design.md). Every row carries the device's own `at`;
 * a newer one wins, field by field and episode by episode, in one upsert each — no read-modify-write.
 */
export interface Position { p: number; d: number; at: number }
export interface Dub { id: number; title: string; at: number }
export interface Secret { on: boolean; watched: number; at: number }
export interface Title { dub?: Dub; eps?: Record<string, Position>; secret?: Secret; gone?: number }

export const TOMBSTONE_TTL_MS = 30 * 24 * 60 * 60 * 1000;
const MAX_EPISODES = 30;
const MAX_BODY = 256 * 1024;

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

/** A write that a tombstone at least as new as it does not forbid. */
const NOT_GONE = "WHERE NOT EXISTS (SELECT 1 FROM gone WHERE user = ?1 AND anime = ?2 AND at >= ?AT)";

function statements(db: D1Database, user: number, anime: number, title: Title): D1PreparedStatement[] {
  const out: D1PreparedStatement[] = [];
  if (title.gone !== undefined) {
    out.push(db.prepare("INSERT INTO gone (user, anime, at) VALUES (?1, ?2, ?3) ON CONFLICT (user, anime) DO UPDATE SET at = excluded.at WHERE excluded.at > gone.at").bind(user, anime, title.gone));
    for (const table of ["positions", "dubs", "secrets"]) {
      out.push(db.prepare(`DELETE FROM ${table} WHERE user = ?1 AND anime = ?2 AND at <= (SELECT at FROM gone WHERE user = ?1 AND anime = ?2)`).bind(user, anime));
    }
  }
  let newest = 0;
  for (const [episode, position] of Object.entries(title.eps ?? {})) {
    newest = Math.max(newest, position.at);
    out.push(db.prepare(`INSERT INTO positions (user, anime, episode, p, d, at) SELECT ?1, ?2, ?3, ?4, ?5, ?6 ${NOT_GONE.replace("?AT", "?6")}
      ON CONFLICT (user, anime, episode) DO UPDATE SET p = excluded.p, d = excluded.d, at = excluded.at WHERE excluded.at > positions.at`)
      .bind(user, anime, Number(episode), Math.round(position.p), Math.round(position.d), position.at));
  }
  if (title.dub !== undefined) {
    newest = Math.max(newest, title.dub.at);
    out.push(db.prepare(`INSERT INTO dubs (user, anime, id, title, at) SELECT ?1, ?2, ?3, ?4, ?5 ${NOT_GONE.replace("?AT", "?5")}
      ON CONFLICT (user, anime) DO UPDATE SET id = excluded.id, title = excluded.title, at = excluded.at WHERE excluded.at > dubs.at`)
      .bind(user, anime, title.dub.id, title.dub.title, title.dub.at));
  }
  if (title.secret !== undefined) {
    newest = Math.max(newest, title.secret.at);
    out.push(db.prepare(`INSERT INTO secrets (user, anime, "on", watched, at) SELECT ?1, ?2, ?3, ?4, ?5 ${NOT_GONE.replace("?AT", "?5")}
      ON CONFLICT (user, anime) DO UPDATE SET "on" = excluded."on", watched = excluded.watched, at = excluded.at WHERE excluded.at > secrets.at`)
      .bind(user, anime, title.secret.on ? 1 : 0, title.secret.watched, title.secret.at));
  }
  if (newest > 0) {
    // Written after the title was finished: it is being watched again, and the tombstone goes.
    out.push(db.prepare("DELETE FROM gone WHERE user = ?1 AND anime = ?2 AND at < ?3").bind(user, anime, newest));
    out.push(db.prepare(`DELETE FROM positions WHERE user = ?1 AND anime = ?2 AND episode NOT IN
      (SELECT episode FROM positions WHERE user = ?1 AND anime = ?2 ORDER BY at DESC LIMIT ${MAX_EPISODES})`).bind(user, anime));
  }
  return out;
}

/** Everything stored for one viewer, in the wire shape the clients read. */
async function document(db: D1Database, user: number, now: number): Promise<Record<string, Title>> {
  const titles: Record<string, Title> = {};
  const title = (anime: number) => (titles[String(anime)] ??= {});
  const [positions, dubs, secrets, gone] = await db.batch([
    db.prepare("SELECT anime, episode, p, d, at FROM positions WHERE user = ?1").bind(user),
    db.prepare("SELECT anime, id, title, at FROM dubs WHERE user = ?1").bind(user),
    db.prepare('SELECT anime, "on", watched, at FROM secrets WHERE user = ?1').bind(user),
    db.prepare("SELECT anime, at FROM gone WHERE user = ?1 AND at > ?2").bind(user, now - TOMBSTONE_TTL_MS),
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

/**
 * `GET /sync` and `POST /sync`, for any Shikimori user: the web whitelist does not apply here —
 * the apps' viewers sync too. The token only says whose rows they are.
 */
export async function handleSync(request: Request, env: Cloudflare.Env, now = Date.now()): Promise<Response> {
  const header = request.headers.get("Authorization") ?? "";
  const token = header.startsWith("Bearer ") ? header.slice(7).trim() : "";
  if (token === "") return json({ error: "sign_in" }, 401);
  const viewer = await whoami(token, (input, init) => fetch(input, init), Date.now);
  if (viewer === "unavailable") return json({ error: "unavailable" }, 502);
  if (viewer === null) return json({ error: "sign_in" }, 401);
  const db = env.SYNC_DB;

  if (request.method === "POST") {
    const text = await request.text();
    if (text.length > MAX_BODY) return json({ error: "parameters" }, 400);
    let body: unknown;
    try { body = JSON.parse(text); } catch { return json({ error: "parameters" }, 400); }
    const incoming = parseTitles(body);
    if (incoming === null) return json({ error: "parameters" }, 400);
    const writes = Object.entries(incoming).flatMap(([anime, title]) => statements(db, viewer.id, Number(anime), title));
    writes.push(db.prepare("DELETE FROM gone WHERE user = ?1 AND at <= ?2").bind(viewer.id, now - TOMBSTONE_TTL_MS));
    await db.batch(writes);
  }
  return json({ titles: await document(db, viewer.id, now) });
}
