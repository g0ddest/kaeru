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
      const merge = /^Bearer merge-([a-e])$/.exec(auth ?? "");
      if (merge) return Response.json({ id: 6000 + merge[1].charCodeAt(0), nickname: "merge" });
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

  const titleOf = async (token: string, id: string) =>
    ((await (await call("GET", token)).json()) as { titles: Record<string, unknown> }).titles[id];

  it("keeps the newer of each field, and episodes one by one", async () => {
    const t = Date.now() - 100_000;
    await call("POST", "merge-a", { titles: { "7": {
      dub: { id: 1, title: "A", at: t + 200 },
      eps: { "1": { p: 100, d: 1000, at: t + 100 }, "2": { p: 500, d: 1000, at: t + 300 } },
    } } });
    await call("POST", "merge-a", { titles: { "7": {
      dub: { id: 2, title: "B", at: t + 100 },
      eps: { "1": { p: 900, d: 1000, at: t + 400 }, "2": { p: 50, d: 1000, at: t + 250 } },
    } } });
    expect(await titleOf("merge-a", "7")).toEqual({
      dub: { id: 1, title: "A", at: t + 200 },
      eps: { "1": { p: 900, d: 1000, at: t + 400 }, "2": { p: 500, d: 1000, at: t + 300 } },
    });
  });

  it("keeps a title watched «украдкой»", async () => {
    const at = Date.now();
    await call("POST", "merge-b", { titles: { "9": { secret: { on: true, watched: 3, at } } } });
    expect(await titleOf("merge-b", "9")).toEqual({ secret: { on: true, watched: 3, at } });
  });

  it("drops a finished title to a tombstone that an older write cannot bring back", async () => {
    const t = Date.now() - 10_000;
    await call("POST", "merge-c", { titles: { "7": { eps: { "1": { p: 100, d: 1000, at: t + 100 } } } } });
    await call("POST", "merge-c", { titles: { "7": { gone: t + 500 } } });
    expect(await titleOf("merge-c", "7")).toEqual({ gone: t + 500 });
    await call("POST", "merge-c", { titles: { "7": { eps: { "2": { p: 10, d: 1000, at: t + 400 } } } } });
    expect(await titleOf("merge-c", "7")).toEqual({ gone: t + 500 });
    await call("POST", "merge-c", { titles: { "7": { eps: { "2": { p: 10, d: 1000, at: t + 600 } } } } });
    expect(await titleOf("merge-c", "7")).toEqual({ eps: { "2": { p: 10, d: 1000, at: t + 600 } } });
  });

  it("forgets tombstones older than thirty days", async () => {
    const now = Date.now();
    await call("POST", "merge-d", { titles: { "7": { gone: now - 31 * 24 * 3600 * 1000 }, "8": { gone: now - 1000 } } });
    const titles = ((await (await call("GET", "merge-d")).json()) as { titles: Record<string, unknown> }).titles;
    expect(Object.keys(titles)).toEqual(["8"]);
  });

  it("keeps the thirty newest episodes of a title", async () => {
    const t = Date.now() - 100_000;
    const eps: Record<string, { p: number; d: number; at: number }> = {};
    for (let n = 1; n <= 35; n += 1) eps[String(n)] = { p: 1, d: 2, at: t + n };
    await call("POST", "merge-e", { titles: { "7": { eps } } });
    const title = (await titleOf("merge-e", "7")) as { eps: Record<string, unknown> };
    expect(Object.keys(title.eps).map(Number).sort((a, b) => a - b)).toEqual(Array.from({ length: 30 }, (_, i) => i + 6));
  });

  it("answers a write with only the titles it wrote, so a big list is not read on every save", async () => {
    const at = Date.now();
    await call("POST", "merge-a", { titles: { "100": { dub: { id: 1, title: "A", at } }, "101": { dub: { id: 2, title: "B", at } } } });
    const answer = (await (await call("POST", "merge-a", { titles: { "100": { eps: { "1": { p: 5, d: 10, at: at + 1 } } } } })).json()) as { titles: Record<string, unknown> };
    expect(Object.keys(answer.titles)).toEqual(["100"]);
    expect(answer.titles["100"]).toEqual({ dub: { id: 1, title: "A", at }, eps: { "1": { p: 5, d: 10, at: at + 1 } } });
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
