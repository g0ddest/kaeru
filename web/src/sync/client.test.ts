// Vectors: infra/relay/src/sync.ts (the wire document, 400/401/502) and infra/relay/src/index.ts (the
// plain-text 429 of the `sync` bucket); infra/relay/test/sync-routes.test.ts shows the same shapes.
import { describe, expect, it } from "vitest";
import { ApiError } from "../api/http";
import type { authorized } from "../auth/session";
import { RELAY_URL } from "../config";
import { createSyncClient, SyncError } from "./client";

interface Sent {
  url: string;
  init: RequestInit | undefined;
}

function worker(answer: (call: number) => Response | Promise<Response>) {
  const sent: Sent[] = [];
  const fetch: typeof globalThis.fetch = async (input, init) => {
    sent.push({ url: String(input), init });
    return answer(sent.length);
  };
  return { fetch, sent };
}

const json = (body: unknown, status = 200): Response =>
  new Response(JSON.stringify(body), { status, headers: { "Content-Type": "application/json; charset=utf-8" } });
const plain = (body: string, status: number): Response =>
  new Response(body, { status, headers: { "Content-Type": "text/plain; charset=utf-8" } });

const signedIn: typeof authorized = (call) => call("tok");

function clientFor(answer: (call: number) => Response | Promise<Response>, auth: typeof authorized = signedIn) {
  const { fetch, sent } = worker(answer);
  return { client: createSyncClient({ authorized: auth, fetch }), sent };
}

async function failure(pending: Promise<unknown>): Promise<unknown> {
  const error = await pending.then(
    () => null,
    (caught: unknown) => caught,
  );
  return error instanceof SyncError ? error.kind : error;
}

const DOC = {
  titles: {
    "1535": {
      dub: { id: 610, title: "AniLibria.TV", at: 1_790_000_000_000 },
      eps: { "3": { p: 861_000, d: 1_440_000, at: 1_790_000_000_001 } },
      secret: { on: true, watched: 7, at: 1 },
    },
    "21": { gone: 1_790_000_000_002 },
  },
};

describe("sync client", () => {
  it("reads the document with the bearer and nothing else", async () => {
    const { client, sent } = clientFor(() => json(DOC));

    const titles = await client.get();

    expect(sent).toHaveLength(1);
    expect(sent[0]?.url).toBe(`${RELAY_URL}/sync`);
    expect(sent[0]?.init?.method ?? "GET").toBe("GET");
    expect(sent[0]?.init?.headers).toEqual({ Authorization: "Bearer tok" });
    expect(titles).toEqual({
      "1535": {
        dub: { id: 610, title: "AniLibria.TV", at: 1_790_000_000_000 },
        eps: { "3": { p: 861_000, d: 1_440_000, at: 1_790_000_000_001 } },
        secret: { on: true, watched: 7, at: 1 },
      },
      "21": { gone: 1_790_000_000_002 },
    });
  });

  it("sends a batch as { titles } JSON and reads the merged document back", async () => {
    const { client, sent } = clientFor(() => json(DOC));
    const batch = { "1535": { eps: { "4": { p: 1_000, d: 1_440_000, at: 5 } } }, "21": { gone: 6 } };

    const titles = await client.post(batch);

    expect(sent[0]?.url).toBe(`${RELAY_URL}/sync`);
    expect(sent[0]?.init?.method).toBe("POST");
    expect(sent[0]?.init?.headers).toEqual({ Authorization: "Bearer tok", "Content-Type": "application/json" });
    expect(JSON.parse(String(sent[0]?.init?.body))).toEqual({ titles: batch });
    expect(sent[0]?.init?.keepalive).toBeUndefined();
    expect(Object.keys(titles).sort()).toEqual(["1535", "21"]);
  });

  it("asks the browser to keep a small batch going past the page's end", async () => {
    const { client, sent } = clientFor(() => json({ titles: {} }));

    await client.post({ "1": { gone: 1 } }, { keepalive: true });

    expect(sent[0]?.init?.keepalive).toBe(true);
  });

  it("does not ask keepalive for a batch over the browser's 64 KB allowance", async () => {
    const { client, sent } = clientFor(() => json({ titles: {} }));
    const eps: Record<string, { p: number; d: number; at: number }> = {};
    for (let episode = 1; episode <= 2_000; episode += 1) eps[String(episode)] = { p: 1_000_000, d: 1_440_000, at: 1_790_000_000_000 };

    await client.post({ "1535": { eps } }, { keepalive: true });

    expect(sent[0]?.init?.keepalive).toBeUndefined();
  });

  it("skips what it cannot read in an answer", async () => {
    const { client } = clientFor(() =>
      json({
        titles: {
          abc: { gone: 1 },
          "1": { dub: { id: "610", title: "x", at: 1 }, eps: { "2": { p: 1, d: 2, at: 3 }, x: { p: 1, d: 2, at: 3 }, "4": { p: "1", d: 2, at: 3 } } },
          "2": "nope",
          "3": { gone: "yesterday" },
          "4": { secret: { on: "yes", watched: 1, at: 1 } },
          "5": { secret: { on: false, watched: "2", at: 1 } },
        },
      }),
    );

    expect(await client.get()).toEqual({ "1": { eps: { "2": { p: 1, d: 2, at: 3 } } }, "3": {}, "4": {}, "5": {} });
  });

  it("calls an answer without titles a parser failure", async () => {
    const { client } = clientFor(() => json({ nope: true }));

    expect(await failure(client.get())).toBe("parser");
  });

  it("hands a 401 to authorized, which refreshes and calls again", async () => {
    const tokens: string[] = [];
    const retrying: typeof authorized = async (call) => {
      tokens.push("old");
      try {
        return await call("old");
      } catch (error) {
        if (!(error instanceof ApiError) || error.status !== 401) throw error;
        tokens.push("new");
        return call("new");
      }
    };
    const { client, sent } = clientFor((call) => (call === 1 ? json({ error: "sign_in" }, 401) : json({ titles: {} })), retrying);

    expect(await client.get()).toEqual({});
    expect(tokens).toEqual(["old", "new"]);
    expect(sent.map((s) => (s.init?.headers as Record<string, string>)["Authorization"])).toEqual(["Bearer old", "Bearer new"]);
  });

  it("names the worker's refusals", async () => {
    expect(await failure(clientFor(() => json({ error: "unavailable" }, 502)).client.get())).toBe("unavailable");
    expect(await failure(clientFor(() => json({ error: "parameters" }, 400)).client.post({}))).toBe("parameters");
    expect(await failure(clientFor(() => plain("Too Many Requests", 429)).client.get())).toBe("throttled");
    expect(await failure(clientFor(() => plain("oops", 500)).client.get())).toBe("unknown");
    const signIn = await failure(clientFor(() => json({ error: "sign_in" }, 401)).client.get());
    expect(signIn).toBeInstanceOf(ApiError);
    expect((signIn as ApiError).status).toBe(401);
  });

  it("calls a request that never got an answer offline", async () => {
    const { client } = clientFor(() => Promise.reject(new TypeError("Failed to fetch")));

    expect(await failure(client.get())).toBe("offline");
  });
});
