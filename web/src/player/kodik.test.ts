// Vectors: infra/relay/src/kodik/routes.ts and client.ts (the worker's answers), infra/relay/src/whitelist.ts
// (401/403/502 from the gate), infra/relay/src/index.ts (the plain-text 429), and the link rules of
// shared/src/commonMain/kotlin/app/kaeru/shared/data/kodik/KodikClient.kt.
import { afterEach, describe, expect, it, vi } from "vitest";
import { ApiError } from "../api/http";
import type { authorized } from "../auth/session";
import { createKodik, KodikError } from "./kodik";
import type { KodikFailure } from "./kodik";

interface Sent {
  url: string;
  init: RequestInit | undefined;
}

function worker(answer: (call: number) => Response) {
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

/** Rotates once on a 401 and calls again with the new token, the way `authorized` does. */
function retrying(): { authorized: typeof authorized; tokens: string[] } {
  const tokens: string[] = [];
  const fake: typeof authorized = async (call) => {
    tokens.push("old");
    try {
      return await call("old");
    } catch (error) {
      if (!(error instanceof ApiError) || error.status !== 401) throw error;
      tokens.push("new");
      return call("new");
    }
  };
  return { authorized: fake, tokens };
}

function kodikFor(answer: (call: number) => Response, deps: { authorized?: typeof authorized; onClosed?: (nickname: string) => void } = {}) {
  const { fetch, sent } = worker(answer);
  const kodik = createKodik({ authorized: deps.authorized ?? signedIn, onClosed: deps.onClosed ?? (() => {}), fetch });
  return { kodik, sent };
}

/** The kind of the KodikError [pending] fails with, or whatever else it threw. */
async function failure(pending: Promise<unknown>): Promise<unknown> {
  const error = await pending.then(
    () => null,
    (caught: unknown) => caught,
  );
  return error instanceof KodikError ? error.kind : error;
}

const TRACKS = {
  translations: [
    { id: 610, title: "AniLibria.TV", type: "voice", episodesCount: 12, mediaId: "91234", mediaHash: "0a1b2c3d" },
    { id: 1291, title: "Crunchyroll", type: "subtitles", episodesCount: null, mediaId: "91235", mediaHash: "4e5f6a7b" },
  ],
};

const STREAM = {
  urls: [
    { quality: 720, url: "http://cloud.kodik-storage.com/useruploads/abc/720.mp4:hls:manifest.m3u8" },
    { quality: 480, url: "//cloud.kodik-storage.com/useruploads/abc/480.mp4:hls:manifest.m3u8" },
    { quality: 360, url: "https://cloud.kodik-storage.com/useruploads/abc/360.mp4:hls:manifest.m3u8" },
  ],
  translationId: 610,
  episode: 3,
  season: 1,
};

afterEach(() => {
  vi.useRealTimers();
});

describe("createKodik", () => {
  it("sends the bearer and no other header, so the worker's preflight lets it through", async () => {
    const { kodik, sent } = kodikFor(() => json(TRACKS));

    await kodik.translations(5114);

    expect(sent).toHaveLength(1);
    // Any custom header (X-Requested-With included) fails the worker's preflight.
    expect(sent[0]?.init?.headers).toEqual({ Authorization: "Bearer tok" });
  });

  it("asks the worker for the title's dubs and for one episode of a dub, with no season", async () => {
    const { kodik, sent } = kodikFor((call) => (call === 1 ? json(TRACKS) : json({ ...STREAM, translationId: -5 })));

    await kodik.translations(5114);
    // A film with no dub chooser has a negative id, and it goes out as it is.
    await kodik.resolve(5114, -5, 1);

    expect(sent.map((s) => s.url)).toEqual([
      "https://kaeru-relay.vitaliy-velikodniy.workers.dev/kodik/translations?anime=5114",
      "https://kaeru-relay.vitaliy-velikodniy.workers.dev/kodik/resolve?anime=5114&translation=-5&episode=1",
    ]);
  });

  it("keeps a dub's id, title, kind and episode count and drops the worker's media fields", async () => {
    const { kodik } = kodikFor(() => json(TRACKS));

    const tracks = await kodik.translations(5114);

    expect(tracks).toEqual([
      { id: 610, title: "AniLibria.TV", type: "voice", episodesCount: 12 },
      { id: 1291, title: "Crunchyroll", type: "subtitles", episodesCount: null },
    ]);
  });

  it("serves every link over https, best quality first, one link per quality", async () => {
    const { kodik } = kodikFor(() =>
      json({
        ...STREAM,
        urls: [
          { quality: 360, url: "https://cdn.example/360.m3u8" },
          { quality: 720, url: "http://cdn.example/720.m3u8" },
          { quality: 480, url: "//cdn.example/480.m3u8" },
          { quality: 720, url: "https://cdn.example/720-again.m3u8" },
          { quality: 0, url: "https://cdn.example/0.m3u8" },
          { quality: 240, url: "" },
        ],
      }),
    );

    const stream = await kodik.resolve(5114, 610, 3);

    expect(stream).toEqual({
      translationId: 610,
      links: [
        { quality: 720, url: "https://cdn.example/720.m3u8" },
        { quality: 480, url: "https://cdn.example/480.m3u8" },
        { quality: 360, url: "https://cdn.example/360.m3u8" },
      ],
    });
  });

  it("calls an answer it cannot read a changed source", async () => {
    const { kodik } = kodikFor(() => plain("<html>captive portal</html>", 200));
    expect(await failure(kodik.translations(5114))).toBe("parser");
    const { kodik: other } = kodikFor(() => json({ urls: "none" }));
    expect(await failure(other.resolve(5114, 610, 3))).toBe("parser");
  });

  it("calls a stream with no playable link a changed source", async () => {
    const { kodik } = kodikFor(() => json({ ...STREAM, urls: [{ quality: 0, url: "https://cdn.example/0.m3u8" }] }));
    expect(await failure(kodik.resolve(5114, 610, 3))).toBe("parser");
  });

  it("answers a 401 with the ApiError that makes authorized refresh and call once more", async () => {
    const { authorized, tokens } = retrying();
    const { kodik, sent } = kodikFor((call) => (call === 1 ? json({ error: "sign_in" }, 401) : json(STREAM)), { authorized });

    const stream = await kodik.resolve(5114, 610, 3);

    expect(stream.links).toHaveLength(3);
    expect(sent).toHaveLength(2);
    expect(tokens).toEqual(["old", "new"]);
    expect(sent[1]?.init?.headers).toEqual({ Authorization: "Bearer new" });
  });

  it("closes the session for an account taken off the list", async () => {
    const closed: string[] = [];
    const { kodik } = kodikFor(() => json({ error: "not_allowed", nickname: "nick" }, 403), { onClosed: (nickname) => closed.push(nickname) });

    expect(await failure(kodik.translations(5114))).toBe("unavailable");
    expect(closed).toEqual(["nick"]);
  });

  it("tells the worker's refusals apart by their JSON error, not the status", async () => {
    const cases: Array<[() => Response, KodikFailure]> = [
      [() => json({ error: "episode" }, 404), "episode"],
      [() => json({ error: "title" }, 404), "title"],
      [() => json({ error: "token" }, 502), "token"],
      [() => json({ error: "parser", step: "links" }, 502), "parser"],
      [() => json({ error: "upstream", step: "/ftor" }, 502), "upstream"],
      [() => json({ error: "unavailable" }, 502), "unavailable"],
      [() => plain("too many requests", 429), "throttled"],
      [() => json({ error: "not_found" }, 404), "unknown"],
      [() => json({ error: "parameters" }, 400), "unknown"],
      [() => plain("method not allowed", 405), "unknown"],
    ];
    for (const [answer, kind] of cases) {
      const { kodik } = kodikFor(answer);
      expect(await failure(kodik.resolve(5114, 610, 3))).toBe(kind);
    }
  });

  it("hears sign_in under any status as the 401 authorized refreshes on", async () => {
    const { kodik } = kodikFor(() => json({ error: "sign_in" }, 400));
    const error = await failure(kodik.translations(5114));
    expect(error).toBeInstanceOf(ApiError);
    expect((error as ApiError).status).toBe(401);
  });

  it("reports a fetch that throws as offline", async () => {
    const fetch: typeof globalThis.fetch = async () => {
      throw new TypeError("Failed to fetch");
    };
    const kodik = createKodik({ authorized: signedIn, onClosed: () => {}, fetch });
    expect(await failure(kodik.translations(5114))).toBe("offline");
  });

  it("gives up on a silent worker as upstream: dubs after 20 s, an episode after 25 s", async () => {
    vi.useFakeTimers();
    const hanging: typeof globalThis.fetch = (_input, init) =>
      new Promise((_resolve, reject) => {
        init?.signal?.addEventListener("abort", () => reject(new DOMException("The operation was aborted.", "AbortError")));
      });
    const kodik = createKodik({ authorized: signedIn, onClosed: () => {}, fetch: hanging });

    const settled: string[] = [];
    const dubs = failure(kodik.translations(5114)).then((kind) => {
      settled.push("dubs");
      return kind;
    });
    const episode = failure(kodik.resolve(5114, 610, 3)).then((kind) => {
      settled.push("episode");
      return kind;
    });

    await vi.advanceTimersByTimeAsync(19_999);
    expect(settled).toEqual([]);
    await vi.advanceTimersByTimeAsync(1);
    expect(settled).toEqual(["dubs"]);
    await vi.advanceTimersByTimeAsync(4_999);
    expect(settled).toEqual(["dubs"]);
    await vi.advanceTimersByTimeAsync(1);
    expect(settled).toEqual(["dubs", "episode"]);
    expect(await dubs).toBe("upstream");
    expect(await episode).toBe("upstream");
  });
});
