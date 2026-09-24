// Vectors: shared/src/commonTest/kotlin/app/kaeru/shared/data/shikimori/ShikimoriRateLimiterTest.kt,
// shared/src/commonTest/kotlin/app/kaeru/shared/data/shikimori/ShikimoriClientTest.kt,
// android/src/test/java/app/kaeru/ui/common/ErrorMessagesTest.kt
import { describe, expect, it } from "vitest";
import { ApiError, NetworkError, RateLimiter, createShikimoriHttp, errorMessage } from "./http";

interface Call {
  url: string;
  method: string;
  headers: Headers;
  body: string | null;
}

function fakeFetch(answer: (call: Call, index: number) => Response | Promise<Response>) {
  const calls: Call[] = [];
  const fetch: typeof globalThis.fetch = async (input, init) => {
    const call: Call = {
      url: String(input),
      method: init?.method ?? "GET",
      headers: new Headers(init?.headers),
      body: typeof init?.body === "string" ? init.body : null,
    };
    calls.push(call);
    return answer(call, calls.length - 1);
  };
  return { fetch, calls };
}

const json = (body: unknown, status = 200): Response =>
  new Response(JSON.stringify(body), { status, headers: { "Content-Type": "application/json" } });

/** A real limiter with room for everything a test sends. */
const roomy = (): RateLimiter => new RateLimiter({ perSecond: 1_000, perMinute: 10_000 });

/** A limiter on a virtual clock that moves only when the limiter sleeps. */
function virtualLimiter(opts: { perSecond?: number; perMinute?: number } = {}) {
  const clock = { t: 0 };
  const waits: number[] = [];
  const limiter = new RateLimiter({
    ...opts,
    now: () => clock.t,
    sleep: async (ms) => {
      waits.push(ms);
      clock.t += ms;
    },
  });
  return { limiter, clock, waits };
}

describe("RateLimiter", () => {
  it("lets five through in a second and holds the sixth until the window moves", async () => {
    const { limiter, clock } = virtualLimiter();
    for (let i = 0; i < 5; i += 1) await limiter.acquire();
    expect(clock.t).toBe(0);
    await limiter.acquire();
    expect(clock.t).toBe(1_000);
  });

  it("does not wait exactly a second after the first of five", async () => {
    const { limiter, clock, waits } = virtualLimiter();
    for (let i = 0; i < 5; i += 1) await limiter.acquire();
    clock.t += 1_000;
    await limiter.acquire();
    expect(waits).toEqual([]);
  });

  it("holds the ninety-first request in a minute until the oldest ages", async () => {
    const { limiter, clock } = virtualLimiter();
    for (let i = 0; i < 90; i += 1) {
      await limiter.acquire();
      clock.t += 250;
    }
    const before = clock.t;
    await limiter.acquire();
    expect(clock.t - before).toBe(37_500);
  });

  it("keeps both sliding windows over 91 requests", async () => {
    const { limiter, clock } = virtualLimiter();
    const admitted: number[] = [];
    for (let i = 0; i < 91; i += 1) {
      await limiter.acquire();
      admitted.push(clock.t);
    }
    expect(admitted.slice(0, 5)).toEqual([0, 0, 0, 0, 0]);
    expect(admitted[5]).toBe(1_000);
    expect(admitted[90]).toBe(60_000);
    for (const time of admitted) {
      expect(admitted.filter((t) => t > time - 1_000 && t <= time).length).toBeLessThanOrEqual(5);
      expect(admitted.filter((t) => t > time - 60_000 && t <= time).length).toBeLessThanOrEqual(90);
    }
  });

  it("serves concurrent callers one after another", async () => {
    const { limiter, waits } = virtualLimiter();
    await Promise.all(Array.from({ length: 6 }, () => limiter.acquire()));
    expect(waits).toEqual([1_000]);
  });

  it("gives up a wait on abort and lets the next caller through", async () => {
    const clock = { t: 0 };
    let sleeping!: () => void;
    const asleep = new Promise<void>((resolve) => {
      sleeping = resolve;
    });
    const limiter = new RateLimiter({
      perSecond: 1,
      now: () => clock.t,
      sleep: () => {
        sleeping();
        return new Promise<void>(() => undefined);
      },
    });
    await limiter.acquire();
    const controller = new AbortController();
    const waiting = limiter.acquire(controller.signal);
    await asleep;
    controller.abort();
    await expect(waiting).rejects.toMatchObject({ name: "AbortError" });
    clock.t = 1_000;
    await expect(limiter.acquire()).resolves.toBeUndefined();
  });
});

describe("createShikimoriHttp", () => {
  it("asks shikimori.io for JSON as Kaeru, with the bearer only when there is one", async () => {
    const { fetch, calls } = fakeFetch(() => json({ id: 1 }));
    const http = createShikimoriHttp({ fetch, limiter: roomy() });

    await expect(http("api/animes/1")).resolves.toEqual({ id: 1 });
    await http("api/users/whoami", { token: "secret" });
    await http("api/users/whoami", { token: "   " });
    await http("api/users/whoami", { token: null });

    expect(calls.map((call) => call.url)).toEqual([
      "https://shikimori.io/api/animes/1",
      "https://shikimori.io/api/users/whoami",
      "https://shikimori.io/api/users/whoami",
      "https://shikimori.io/api/users/whoami",
    ]);
    expect(calls.map((call) => call.method)).toEqual(["GET", "GET", "GET", "GET"]);
    expect(calls.map((call) => call.headers.get("accept"))).toEqual(Array(4).fill("application/json"));
    expect(calls.map((call) => call.headers.get("x-requested-with"))).toEqual(Array(4).fill("Kaeru"));
    expect(calls.map((call) => call.headers.get("authorization"))).toEqual([null, "Bearer secret", null, null]);
    expect(calls[0]?.headers.has("content-type")).toBe(false);
    expect(calls[0]?.body).toBeNull();
  });

  it("sends a JSON body with its content type and method", async () => {
    const { fetch, calls } = fakeFetch(() => json({ id: 111 }));
    const http = createShikimoriHttp({ fetch, limiter: roomy() });

    await http("api/v2/user_rates/111", { method: "PATCH", token: "secret", json: { user_rate: { episodes: 21 } } });

    expect(calls[0]?.method).toBe("PATCH");
    expect(calls[0]?.body).toBe('{"user_rate":{"episodes":21}}');
    expect(calls[0]?.headers.get("content-type")).toBe("application/json");
    expect(calls[0]?.headers.get("authorization")).toBe("Bearer secret");
  });

  it("reads Shikimori's null for an anonymous whoami as null", async () => {
    const { fetch } = fakeFetch(() => json(null));
    const http = createShikimoriHttp({ fetch, limiter: roomy() });
    await expect(http("api/users/whoami")).resolves.toBeNull();
  });

  it("carries a refusal's status and JSON body", async () => {
    const answers = [new Response("nope", { status: 422 }), json({ error: "invalid_token" }, 401)];
    const { fetch } = fakeFetch((_call, index) => answers[index] ?? json(null, 500));
    const http = createShikimoriHttp({ fetch, limiter: roomy() });

    const plain = await http("api/animes/1").catch((error: unknown) => error);
    expect(plain).toBeInstanceOf(ApiError);
    expect(plain).toMatchObject({ status: 422, body: null });

    const parsed = await http("api/animes/1").catch((error: unknown) => error);
    expect(parsed).toBeInstanceOf(ApiError);
    expect(parsed).toMatchObject({ status: 401, body: { error: "invalid_token" } });
  });

  it("turns a fetch that throws into a network failure", async () => {
    const http = createShikimoriHttp({
      fetch: async () => {
        throw new TypeError("Failed to fetch");
      },
      limiter: roomy(),
    });
    await expect(http("api/animes/1")).rejects.toBeInstanceOf(NetworkError);
  });

  it("does not take an unreadable 200 for data", async () => {
    const { fetch } = fakeFetch(() => new Response("<html>", { status: 200 }));
    const http = createShikimoriHttp({ fetch, limiter: roomy() });
    const error = await http("api/animes/1").catch((caught: unknown) => caught);
    expect(error).toBeInstanceOf(Error);
    expect(error).not.toBeInstanceOf(ApiError);
    expect(error).not.toBeInstanceOf(NetworkError);
  });

  it("repeats a 429 once, a second later", async () => {
    const answers = [new Response("slow down", { status: 429 }), json([1])];
    const { fetch, calls } = fakeFetch((_call, index) => answers[index] ?? json(null, 500));
    const sleeps: number[] = [];
    const http = createShikimoriHttp({ fetch, limiter: roomy(), sleep: async (ms) => void sleeps.push(ms) });

    await expect(http("api/animes")).resolves.toEqual([1]);
    expect(calls).toHaveLength(2);
    expect(sleeps).toEqual([1_000]);
  });

  it("lets a second 429 stand", async () => {
    const { fetch, calls } = fakeFetch(() => new Response("slow down", { status: 429 }));
    const sleeps: number[] = [];
    const http = createShikimoriHttp({ fetch, limiter: roomy(), sleep: async (ms) => void sleeps.push(ms) });

    const error = await http("api/animes").catch((caught: unknown) => caught);
    expect(error).toBeInstanceOf(ApiError);
    expect(error).toMatchObject({ status: 429 });
    expect(calls).toHaveLength(2);
    expect(sleeps).toEqual([1_000]);
  });

  it("takes a limiter slot for every attempt", async () => {
    const { limiter, waits } = virtualLimiter({ perSecond: 1 });
    const { fetch } = fakeFetch(() => json([]));
    const http = createShikimoriHttp({ fetch, limiter });
    await http("api/animes");
    await http("api/animes");
    expect(waits).toEqual([1_000]);
  });

  it("gives up after the timeout as a network failure", async () => {
    const hanging: typeof globalThis.fetch = (_input, init) =>
      new Promise((_resolve, reject) => {
        init?.signal?.addEventListener("abort", () => reject(new DOMException("The operation was aborted.", "AbortError")));
      });
    const http = createShikimoriHttp({ fetch: hanging, limiter: roomy(), timeoutMs: 20 });
    await expect(http("api/animes/1")).rejects.toBeInstanceOf(NetworkError);
  });

  it("reports the caller's own abort as an abort, not as no network", async () => {
    const hanging: typeof globalThis.fetch = (_input, init) =>
      new Promise((_resolve, reject) => {
        init?.signal?.addEventListener("abort", () => reject(new DOMException("The operation was aborted.", "AbortError")));
      });
    const http = createShikimoriHttp({ fetch: hanging, limiter: roomy() });
    const controller = new AbortController();
    const pending = http("api/animes/1", { signal: controller.signal });
    controller.abort();
    const error = await pending.catch((caught: unknown) => caught);
    expect(error).toMatchObject({ name: "AbortError" });
    expect(error).not.toBeInstanceOf(NetworkError);
  });
});

describe("errorMessage", () => {
  it("names the connection when there was no answer", () => {
    expect(errorMessage(new NetworkError())).toBe("Нет соединения. Проверьте интернет");
  });

  it("asks to sign in again on 401 and 403", () => {
    expect(errorMessage(new ApiError(401))).toBe("Сессия истекла, войдите снова");
    expect(errorMessage(new ApiError(403))).toBe("Сессия истекла, войдите снова");
  });

  it("gives rate limiting and server errors their own copy", () => {
    expect(errorMessage(new ApiError(429))).toBe("Слишком много запросов, попробуйте позже");
    expect(errorMessage(new ApiError(500))).toBe("Shikimori недоступен, попробуйте позже");
    expect(errorMessage(new ApiError(503))).toBe("Shikimori недоступен, попробуйте позже");
  });

  it("never leaks the text of anything else", () => {
    expect(errorMessage(new ApiError(404))).toBe("Что-то пошло не так. Повторите попытку");
    expect(errorMessage(new Error("No anime 100 returned by Shikimori"))).toBe("Что-то пошло не так. Повторите попытку");
    expect(errorMessage("boom")).toBe("Что-то пошло не так. Повторите попытку");
  });
});
