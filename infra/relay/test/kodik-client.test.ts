import { describe, expect, it } from "vitest";
import player from "../../../android/src/test/resources/kodik/player.html?raw";
import single from "../../../android/src/test/resources/kodik/movie-single-track.html?raw";
import addPlayers from "../../../android/src/test/resources/kodik/add-players.js?raw";
import links from "../../../android/src/test/resources/kodik/links.json?raw";
import { KodikClient } from "../src/kodik/client";
import { KodikError } from "../src/kodik/errors";

interface Seen { url: string; method: string; body: string }

/** A Kodik that answers from the fixtures, recording what it was asked. */
function fakeKodik(overrides: { getPlayer?: (n: number) => Response; page?: string } = {}) {
  const seen: Seen[] = [];
  let getPlayerCalls = 0;
  const fetch = (async (input: RequestInfo | URL, init?: RequestInit) => {
    const url = String(input);
    const body = typeof init?.body === "string" ? init.body : init?.body instanceof URLSearchParams ? init.body.toString() : "";
    seen.push({ url, method: init?.method ?? "GET", body });
    if (url.startsWith("https://kodik-add.com/")) return new Response(addPlayers);
    if (url === "https://kodik-api.com/get-player") {
      getPlayerCalls += 1;
      if (overrides.getPlayer) return overrides.getPlayer(getPlayerCalls);
      return Response.json({ found: true, link: "//kodikplayer.com/serial/55917/d1d44d5cd59af5af897ce899a776dacf/720p" });
    }
    if (url.includes("/ftor")) return new Response(links);
    if (url.startsWith("https://kodikplayer.com/")) return new Response(overrides.page ?? player);
    return new Response("unexpected", { status: 599 });
  }) as typeof globalThis.fetch;
  return { fetch, seen };
}

describe("KodikClient", () => {
  it("lists the translations of a title", async () => {
    const kodik = fakeKodik();
    const list = await new KodikClient({ fetch: kodik.fetch }).translations(1535);
    expect(list).toHaveLength(33);
    expect(list[0].id).toBe(3560);
  });

  it("keeps the catalogue for six hours", async () => {
    const kodik = fakeKodik();
    let now = 0;
    const client = new KodikClient({ fetch: kodik.fetch, now: () => now });
    await client.translations(1535);
    await client.translations(1535);
    expect(kodik.seen.filter((s) => s.url.endsWith("/get-player"))).toHaveLength(1);
    now = 6 * 60 * 60 * 1000;
    await client.translations(1535);
    expect(kodik.seen.filter((s) => s.url.endsWith("/get-player"))).toHaveLength(2);
  });

  it("resolves an episode into signed manifests, posting the page's signing parameters", async () => {
    const kodik = fakeKodik();
    const stream = await new KodikClient({ fetch: kodik.fetch }).resolve(1535, 3560, 1);
    expect(stream.urls.map((u) => u.quality)).toEqual([720, 480, 360]);
    expect(stream.episode).toBe(1);
    const ftor = kodik.seen.find((s) => s.url.includes("/ftor"));
    expect(ftor?.method).toBe("POST");
    const form = new URLSearchParams(ftor?.body);
    expect(form.get("type")).toBe("seria");
    expect(form.get("id")).toBe("1211482");
    expect(form.get("ref")).toBe("https://kodikplayer.com/");
  });

  it("says the episode is missing when the track's page does not list it", async () => {
    const kodik = fakeKodik();
    await expect(new KodikClient({ fetch: kodik.fetch }).resolve(1535, 3560, 99))
      .rejects.toMatchObject({ kind: "episode" });
  });

  it("says the title is missing when Kodik has no player for it", async () => {
    const kodik = fakeKodik({ getPlayer: () => Response.json({ found: false }) });
    await expect(new KodikClient({ fetch: kodik.fetch }).translations(1)).rejects.toMatchObject({ kind: "title" });
  });

  it("re-reads a rejected token once and asks again", async () => {
    const kodik = fakeKodik({
      getPlayer: (n) => n === 1
        ? Response.json({ error: "Отсутствует или неверный токен" })
        : Response.json({ found: true, link: "//kodikplayer.com/serial/55917/d1d44d5cd59af5af897ce899a776dacf/720p" }),
    });
    const list = await new KodikClient({ fetch: kodik.fetch }).translations(1535);
    expect(list).toHaveLength(33);
    expect(kodik.seen.filter((s) => s.url.startsWith("https://kodik-add.com/"))).toHaveLength(2);
  });

  it("gives up with a token error after the second rejection", async () => {
    const kodik = fakeKodik({ getPlayer: () => new Response("", { status: 401 }) });
    await expect(new KodikClient({ fetch: kodik.fetch }).translations(1535)).rejects.toMatchObject({ kind: "token" });
  });

  it("gives a single-voice film the one track its page names", async () => {
    const kodik = fakeKodik({ page: single });
    const list = await new KodikClient({ fetch: kodik.fetch }).translations(1535);
    expect(list).toHaveLength(1);
    expect(list[0].episodesCount).toBe(1);
  });

  it("uses a configured token instead of scraping", async () => {
    const kodik = fakeKodik();
    await new KodikClient({ fetch: kodik.fetch, configuredToken: "abc123" }).translations(1535);
    expect(kodik.seen.some((s) => s.url.startsWith("https://kodik-add.com/"))).toBe(false);
    expect(new URLSearchParams(kodik.seen[0].body).get("token")).toBe("abc123");
  });

  it("is an upstream error when Kodik answers 5xx", async () => {
    const kodik = fakeKodik({ getPlayer: () => new Response("down", { status: 503 }) });
    const failure = new KodikClient({ fetch: kodik.fetch }).translations(1535);
    await expect(failure).rejects.toBeInstanceOf(KodikError);
    await expect(failure).rejects.toMatchObject({ kind: "upstream" });
  });
});
