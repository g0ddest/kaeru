import { webOrigin } from "./web";

/**
 * Who may use the web client: Shikimori user ids in the `WEB_ALLOWED_SHIKIMORI_IDS` secret.
 *
 * Checked here and not in the browser, because a check in the browser is one line of the console
 * away from not being there. What is worth guarding — Kodik resolved through this Worker, and a
 * Shikimori token handed to a page — never leaves this Worker for an account not on the list.
 * An empty or missing list closes the web client to everyone rather than opening it.
 *
 * The apps are not affected: they resolve Kodik themselves, and their token requests carry no
 * Origin of the site.
 */
export const WEB_REDIRECTS: readonly string[] = [
  "https://kaeru.vitaliy.velikodniy.name/auth",
  "http://localhost:5173/auth",
];

const WHOAMI_URL = "https://shikimori.io/api/users/whoami";
const WHOAMI_TTL_MS = 10 * 60 * 1000;
const CACHE_LIMIT = 500;

export interface Viewer { id: number; nickname: string }

const cache = new Map<string, { viewer: Viewer | null; at: number }>();

export function parseAllowed(raw: string | undefined): Set<number> {
  const ids = new Set<number>();
  for (const part of (raw ?? "").split(/[\s,]+/)) {
    if (/^\d{1,12}$/.test(part)) ids.add(Number(part));
  }
  return ids;
}

async function keyOf(token: string): Promise<string> {
  const digest = await crypto.subtle.digest("SHA-256", new TextEncoder().encode(token));
  return [...new Uint8Array(digest)].map((b) => b.toString(16).padStart(2, "0")).join("");
}

export async function whoami(token: string, fetcher: typeof fetch, now: () => number): Promise<Viewer | null | "unavailable"> {
  const key = await keyOf(token);
  const held = cache.get(key);
  if (held !== undefined && now() - held.at >= 0 && now() - held.at < WHOAMI_TTL_MS) return held.viewer;
  let response: Response;
  try {
    response = await fetcher(WHOAMI_URL, { headers: { authorization: `Bearer ${token}`, "user-agent": "Kaeru", accept: "application/json" } });
  } catch {
    return "unavailable";
  }
  let viewer: Viewer | null;
  // Only a 401 is Shikimori's word on the token. A 403 from that host is its DDoS-Guard or WAF,
  // and reading it as «sign in again» — remembered for ten minutes — looped every listed viewer
  // through sign-in for as long as the block lasted.
  if (response.status === 401) viewer = null;
  else if (!response.ok) return "unavailable";
  else {
    const body = (await response.json().catch(() => null)) as { id?: unknown; nickname?: unknown } | null;
    if (typeof body?.id !== "number") return "unavailable";
    viewer = { id: body.id, nickname: typeof body.nickname === "string" ? body.nickname : "" };
  }
  if (cache.size >= CACHE_LIMIT) cache.delete(cache.keys().next().value as string);
  cache.set(key, { viewer, at: now() });
  return viewer;
}

function json(body: unknown, status: number): Response {
  return new Response(JSON.stringify(body), { status, headers: { "content-type": "application/json; charset=utf-8" } });
}

/** The verdict on one owner of a token, or null when they may pass. */
export function verdict(viewer: Viewer | null | "unavailable", allowed: Set<number>): Response | null {
  if (viewer === "unavailable") return json({ error: "unavailable" }, 502);
  if (viewer === null) return json({ error: "sign_in" }, 401);
  if (!allowed.has(viewer.id)) return json({ error: "not_allowed", nickname: viewer.nickname }, 403);
  return null;
}

/** For `/kodik/*`: the bearer token's owner must be on the list. */
export async function gate(request: Request, env: Cloudflare.Env): Promise<Response | null> {
  const header = request.headers.get("Authorization") ?? "";
  const token = header.startsWith("Bearer ") ? header.slice(7).trim() : "";
  if (token === "") return json({ error: "sign_in" }, 401);
  return verdict(await whoami(token, (input, init) => fetch(input, init), Date.now), parseAllowed(env.WEB_ALLOWED_SHIKIMORI_IDS));
}

export function fromSite(request: Request): boolean {
  return webOrigin(request) !== null;
}
