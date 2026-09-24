import { SELF } from "cloudflare:test";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import player from "../../../android/src/test/resources/kodik/player.html?raw";
import addPlayers from "../../../android/src/test/resources/kodik/add-players.js?raw";
import links from "../../../android/src/test/resources/kodik/links.json?raw";

const SITE = "https://kaeru.vitaliy.velikodniy.name";
const realFetch = globalThis.fetch;

beforeEach(() => {
  // The worker runs in this isolate, so a stubbed global fetch is the Kodik it talks to.
  vi.stubGlobal("fetch", async (input: RequestInfo | URL, init?: RequestInit) => {
    const url = String(input instanceof Request ? input.url : input);
    if (url.startsWith("https://kodik-add.com/")) return new Response(addPlayers);
    if (url === "https://kodik-api.com/get-player") {
      return Response.json({ found: true, link: "//kodikplayer.com/serial/55917/d1d44d5cd59af5af897ce899a776dacf/720p" });
    }
    if (url.includes("/ftor")) return new Response(links);
    if (url.startsWith("https://kodikplayer.com/")) return new Response(player);
    if (url === "https://shikimori.io/api/users/whoami") {
      const auth = new Headers(init?.headers).get("authorization");
      if (auth === "Bearer allowed-token") return Response.json({ id: 42, nickname: "vitaliy" });
      if (auth === "Bearer stranger-token") return Response.json({ id: 7, nickname: "stranger" });
      return new Response("", { status: 401 });
    }
    return realFetch(input, init);
  });
});
afterEach(() => vi.unstubAllGlobals());

let ip = 0;
function get(path: string, origin: string | null = SITE, token: string | null = "allowed-token"): Promise<Response> {
  ip += 1;
  const headers: Record<string, string> = { "CF-Connecting-IP": `198.51.100.${ip}` };
  if (origin !== null) headers.Origin = origin;
  if (token !== null) headers.Authorization = `Bearer ${token}`;
  return SELF.fetch(`https://relay.test${path}`, { headers });
}

describe("/kodik/*", () => {
  it("lists translations with CORS for the site", async () => {
    const response = await get("/kodik/translations?anime=1535");
    expect(response.status).toBe(200);
    expect(response.headers.get("access-control-allow-origin")).toBe(SITE);
    const body = (await response.json()) as { translations: { id: number }[] };
    expect(body.translations[0].id).toBe(3560);
  });

  it("resolves an episode", async () => {
    const response = await get("/kodik/resolve?anime=1535&translation=3560&episode=1");
    expect(response.status).toBe(200);
    const body = (await response.json()) as { urls: { quality: number; url: string }[]; episode: number };
    expect(body.urls[0].quality).toBe(720);
    expect(body.episode).toBe(1);
  });

  it("answers 404 with the kind for a missing episode", async () => {
    const response = await get("/kodik/resolve?anime=1535&translation=3560&episode=99");
    expect(response.status).toBe(404);
    expect(await response.json()).toEqual({ error: "episode" });
  });

  it("answers 400 for parameters that are not positive integers", async () => {
    expect((await get("/kodik/resolve?anime=abc&translation=1&episode=1")).status).toBe(400);
    expect((await get("/kodik/translations")).status).toBe(400);
    expect((await get("/kodik/resolve?anime=1&translation=1&episode=0")).status).toBe(400);
  });

  it("gives no CORS to an origin that is not the site", async () => {
    const response = await get("/kodik/translations?anime=1535", "https://evil.example");
    expect(response.headers.get("access-control-allow-origin")).toBeNull();
  });

  it("answers a browser preflight with 204 and CORS, before anything else", async () => {
    const response = await SELF.fetch("https://relay.test/kodik/resolve?anime=1", {
      method: "OPTIONS",
      headers: { Origin: SITE, "Access-Control-Request-Method": "GET", "Access-Control-Request-Headers": "authorization" },
    });
    expect(response.status).toBe(204);
    expect(response.headers.get("access-control-allow-origin")).toBe(SITE);
    expect(response.headers.get("access-control-allow-headers")?.toLowerCase()).toContain("authorization");
  });

  it("refuses other methods", async () => {
    const response = await SELF.fetch("https://relay.test/kodik/translations?anime=1535", { method: "POST", headers: { Origin: SITE } });
    expect(response.status).toBe(405);
  });
});

describe("/kodik/* behind the whitelist", () => {
  it("asks to sign in without a token", async () => {
    const response = await get("/kodik/translations?anime=1535", SITE, null);
    expect(response.status).toBe(401);
    expect(await response.json()).toEqual({ error: "sign_in" });
    expect(response.headers.get("access-control-allow-origin")).toBe(SITE);
  });

  it("closes the door on an account that is not listed, naming it", async () => {
    const response = await get("/kodik/translations?anime=1535", SITE, "stranger-token");
    expect(response.status).toBe(403);
    expect(await response.json()).toEqual({ error: "not_allowed", nickname: "stranger" });
  });

  it("asks to sign in again for a token Shikimori refuses", async () => {
    expect((await get("/kodik/translations?anime=1535", SITE, "expired-token")).status).toBe(401);
  });
});
