import { render, screen } from "@testing-library/react";
import { describe, expect, it } from "vitest";
import { SessionStore, type Session } from "../auth/session";
import { progressStore } from "../library/progress";
import { createServices, ServicesProvider, useServices } from "./services";

const SESSION: Session = {
  account: { id: 42, nickname: "Vitaliy", avatar: null },
  tokens: { accessToken: "tok", refreshToken: "ref", expiresIn: 86400, createdAt: Math.floor(Date.now() / 1000) },
};

function Probe() {
  const services = useServices();
  return <p>{services.progress === progressStore ? "общий прогресс" : "другой прогресс"}</p>;
}

describe("services", () => {
  it("refuse to work outside the provider", () => {
    expect(() => render(<Probe />)).toThrow(/ServicesProvider/);
  });

  it("reach every screen through the provider", () => {
    render(
      <ServicesProvider services={createServices()}>
        <Probe />
      </ServicesProvider>,
    );
    expect(screen.getByText("общий прогресс")).toBeInTheDocument();
  });

  it("start with an idle library", () => {
    expect(createServices().library.state()).toEqual({ kind: "idle" });
  });

  it("load the signed-in account's list with its token", async () => {
    const calls: { url: string; authorization: string | null }[] = [];
    const fakeFetch: typeof fetch = async (input, init) => {
      const url = typeof input === "string" ? input : input instanceof URL ? input.href : input.url;
      const headers = new Headers(init?.headers ?? (input instanceof Request ? input.headers : undefined));
      calls.push({ url, authorization: headers.get("Authorization") });
      return new Response("[]", { status: 200, headers: { "Content-Type": "application/json" } });
    };
    const store = new SessionStore(window.sessionStorage);
    store.setSession(SESSION);
    const services = createServices({ fetch: fakeFetch, store });

    await services.library.load();

    const rates = calls.filter((call) => call.url.includes("user_rates"));
    expect(rates.length).toBeGreaterThan(0);
    for (const call of rates) {
      expect(call.url).toContain("user_id=42");
      expect(call.authorization).toBe("Bearer tok");
    }
    expect(services.library.state()).toEqual({ kind: "ready", entries: [] });
  }, 10_000);

  it("ask the worker for dubs with the chosen store's token", async () => {
    const calls: { url: string; authorization: string | null }[] = [];
    const fakeFetch: typeof fetch = async (input, init) => {
      const url = typeof input === "string" ? input : input instanceof URL ? input.href : input.url;
      calls.push({ url, authorization: new Headers(init?.headers).get("Authorization") });
      const body = { translations: [{ id: 610, title: "AniLibria.TV", type: "voice", episodesCount: 12 }] };
      return new Response(JSON.stringify(body), { status: 200, headers: { "Content-Type": "application/json" } });
    };
    const store = new SessionStore(window.sessionStorage);
    store.setSession(SESSION);
    const services = createServices({ fetch: fakeFetch, store });

    const tracks = await services.kodik.translations(52991);

    expect(tracks).toEqual([{ id: 610, title: "AniLibria.TV", type: "voice", episodesCount: 12 }]);
    expect(calls).toEqual([
      { url: "https://kaeru-relay.vitaliy-velikodniy.workers.dev/kodik/translations?anime=52991", authorization: "Bearer tok" },
    ]);
  });

  it("close the chosen store when the worker takes the account off its list", async () => {
    const fakeFetch: typeof fetch = async () =>
      new Response(JSON.stringify({ error: "not_allowed", nickname: "friend" }), {
        status: 403,
        headers: { "Content-Type": "application/json" },
      });
    const store = new SessionStore(window.sessionStorage);
    store.setSession(SESSION);
    const services = createServices({ fetch: fakeFetch, store });

    await expect(services.kodik.resolve(52991, 610, 7)).rejects.toMatchObject({ kind: "unavailable" });

    expect(store.get()).toEqual({ kind: "closed", nickname: "friend" });
  });

  it("build the browser's playback engine for a video element", async () => {
    // jsdom plays no HLS at all, natively or through Media Source: the real engine says so.
    const failures: string[] = [];
    const engine = createServices().engine(document.createElement("video"), (kind) => failures.push(kind));
    await engine.load("https://cdn.test/610/7/720.m3u8", 0);
    expect(failures).toEqual(["unsupported"]);
  });
});
