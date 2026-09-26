import { SELF } from "cloudflare:test";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";

const SITE = "https://kaeru.vitaliy.velikodniy.name";
const realFetch = globalThis.fetch;

beforeEach(() => {
  vi.stubGlobal("fetch", async (input: RequestInfo | URL, init?: RequestInit) => {
    const url = String(input instanceof Request ? input.url : input);
    if (url === "https://shikimori.io/api/users/whoami") {
      const auth = new Headers(init?.headers).get("authorization");
      if (auth === "Bearer sync-owner") return Response.json({ id: 5001, nickname: "owner" });
      // Not on the web whitelist (42, 100): sync is for every Shikimori user all the same.
      if (auth === "Bearer sync-friend") return Response.json({ id: 5002, nickname: "friend" });
      return new Response("", { status: 401 });
    }
    return realFetch(input, init);
  });
});
afterEach(() => vi.unstubAllGlobals());

let ip = 0;
function call(method: string, token: string | null, body?: unknown, origin: string | null = null): Promise<Response> {
  ip += 1;
  const headers: Record<string, string> = { "CF-Connecting-IP": `203.0.113.${ip % 250}` };
  if (token !== null) headers.Authorization = `Bearer ${token}`;
  if (origin !== null) headers.Origin = origin;
  if (body !== undefined) headers["content-type"] = "application/json";
  return SELF.fetch("https://relay.test/sync", { method, headers, body: body === undefined ? undefined : JSON.stringify(body) });
}

describe("/sync", () => {
  it("keeps each viewer's document apart, whitelist or not", async () => {
    const at = Date.now();
    const saved = await call("POST", "sync-owner", { titles: { "7": { eps: { "3": { p: 861000, d: 1440000, at } }, dub: { id: 610, title: "AniLibria.TV", at } } } });
    expect(saved.status).toBe(200);
    const mine = (await (await call("GET", "sync-owner")).json()) as { titles: Record<string, unknown> };
    expect(mine.titles["7"]).toEqual({ dub: { id: 610, title: "AniLibria.TV", at }, eps: { "3": { p: 861000, d: 1440000, at } } });
    const theirs = (await (await call("GET", "sync-friend")).json()) as { titles: Record<string, unknown> };
    expect(theirs.titles).toEqual({});
  });

  it("asks to sign in without a token or with one Shikimori refuses", async () => {
    expect((await call("GET", null)).status).toBe(401);
    expect(await (await call("GET", "nobody")).json()).toEqual({ error: "sign_in" });
  });

  it("refuses a body that is not the sync shape", async () => {
    const response = await call("POST", "sync-friend", { titles: { "abc": {} } });
    expect(response.status).toBe(400);
    expect(await response.json()).toEqual({ error: "parameters" });
  });

  it("answers the site with CORS, preflight included", async () => {
    const preflight = await SELF.fetch("https://relay.test/sync", { method: "OPTIONS", headers: { Origin: SITE } });
    expect(preflight.status).toBe(204);
    const response = await call("GET", "sync-friend", undefined, SITE);
    expect(response.headers.get("access-control-allow-origin")).toBe(SITE);
  });
});
