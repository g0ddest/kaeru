// Vectors: shared/src/commonTest/kotlin/app/kaeru/shared/data/shikimori/ShikimoriClientTest.kt (token form),
// android/src/test/java/app/kaeru/data/shikimori/ShikimoriSessionTest.kt (which answers end a session),
// infra/relay/test/oauth.test.ts and infra/relay/src/whitelist.ts (the site's 403/401/502 bodies)
import { describe, expect, it } from "vitest";
import { exchangeCode, refreshTokens } from "./relay";
import type { TokenResult } from "./relay";

interface Sent {
  url: string;
  init: RequestInit | undefined;
}

function worker(answer: () => Response) {
  const sent: Sent[] = [];
  const fetch: typeof globalThis.fetch = async (input, init) => {
    sent.push({ url: String(input), init });
    return answer();
  };
  return { fetch, sent };
}

const json = (body: unknown, status = 200): Response =>
  new Response(JSON.stringify(body), { status, headers: { "Content-Type": "application/json" } });
const plain = (body: string, status: number): Response =>
  new Response(body, { status, headers: { "Content-Type": "text/plain; charset=utf-8" } });

const TOKENS = { access_token: "acc", token_type: "Bearer", expires_in: 86400, refresh_token: "ref", scope: "user_rates", created_at: 1757600000 };

describe("exchangeCode", () => {
  it("posts only the grant's fields as a form to the worker, with no headers of its own", async () => {
    const { fetch, sent } = worker(() => json(TOKENS));

    const result = await exchangeCode("abc", "http://localhost:5173/auth", { fetch });

    expect(result).toEqual({ kind: "ok", tokens: { accessToken: "acc", refreshToken: "ref", expiresIn: 86400, createdAt: 1757600000 } });
    expect(sent).toHaveLength(1);
    expect(sent[0]?.url).toBe("https://kaeru-relay.vitaliy-velikodniy.workers.dev/oauth/token");
    expect(sent[0]?.init?.method).toBe("POST");
    expect(sent[0]?.init?.body).toBeInstanceOf(URLSearchParams);
    expect(String(sent[0]?.init?.body)).toBe(
      "grant_type=authorization_code&client_id=_MQPkUPZ7AUhCQBBnQhdipfXDTQpBmT5JtpRByuFXeg&code=abc&redirect_uri=http%3A%2F%2Flocalhost%3A5173%2Fauth",
    );
    // Any custom header (X-Requested-With included) fails the worker's preflight.
    expect(sent[0]?.init?.headers).toBeUndefined();
  });

  it("fills a missing created_at with this device's clock and a missing expires_in with a day", async () => {
    const { fetch } = worker(() => json({ access_token: "acc", refresh_token: "ref" }));
    const before = Math.floor(Date.now() / 1000);
    const result = await exchangeCode("abc", "http://localhost:5173/auth", { fetch });
    const after = Math.ceil(Date.now() / 1000);

    expect(result.kind).toBe("ok");
    if (result.kind !== "ok") return;
    expect(result.tokens.expiresIn).toBe(86400);
    expect(result.tokens.createdAt).toBeGreaterThanOrEqual(before);
    expect(result.tokens.createdAt).toBeLessThanOrEqual(after);
  });

  it("reads each of the worker's answers by its JSON error, not the status alone", async () => {
    const cases: Array<[() => Response, TokenResult]> = [
      [() => json({ error: "not_allowed", nickname: "stranger" }, 403), { kind: "closed", nickname: "stranger" }],
      [() => json({ error: "sign_in" }, 401), { kind: "sign_in" }],
      [() => json({ error: "invalid_grant", error_description: "expired" }, 400), { kind: "invalid_grant" }],
      [() => json({ error: "invalid_grant", error_description: "expired" }, 401), { kind: "invalid_grant" }],
      [() => json({ error: "unavailable" }, 502), { kind: "unavailable" }],
      [() => plain("upstream unavailable", 502), { kind: "unavailable" }],
      [() => plain("upstream answer unreadable", 502), { kind: "unavailable" }],
      [() => plain("too many requests", 429), { kind: "throttled" }],
      [() => plain("not configured", 503), { kind: "not_configured" }],
      [() => plain("unknown client", 400), { kind: "rejected" }],
      [() => plain("unexpected redirect_uri", 400), { kind: "rejected" }],
      [() => json({ error: "invalid_client" }, 400), { kind: "rejected" }],
      [() => new Response(null, { status: 401 }), { kind: "rejected" }],
      [() => plain("method not allowed", 405), { kind: "rejected" }],
      [() => json({ access_token: "acc" }), { kind: "unavailable" }],
      [() => json({ access_token: " ", refresh_token: "ref" }), { kind: "unavailable" }],
    ];
    for (const [answer, expected] of cases) {
      const { fetch } = worker(answer);
      await expect(exchangeCode("abc", "http://localhost:5173/auth", { fetch })).resolves.toEqual(expected);
    }
  });

  it("reports a fetch that throws as no network", async () => {
    const fetch: typeof globalThis.fetch = async () => {
      throw new TypeError("Failed to fetch");
    };
    await expect(exchangeCode("abc", "http://localhost:5173/auth", { fetch })).resolves.toEqual({ kind: "network" });
  });
});

describe("refreshTokens", () => {
  it("posts grant_type, client_id and refresh_token only", async () => {
    const { fetch, sent } = worker(() =>
      json({ access_token: "new", token_type: "Bearer", expires_in: 86400, refresh_token: "refresh-2", scope: "user_rates", created_at: 1757600000 }),
    );

    const result = await refreshTokens("refresh-1", { fetch });

    expect(result).toEqual({ kind: "ok", tokens: { accessToken: "new", refreshToken: "refresh-2", expiresIn: 86400, createdAt: 1757600000 } });
    expect(String(sent[0]?.init?.body)).toBe("grant_type=refresh_token&client_id=_MQPkUPZ7AUhCQBBnQhdipfXDTQpBmT5JtpRByuFXeg&refresh_token=refresh-1");
    expect(sent[0]?.init?.headers).toBeUndefined();
  });

  it("hears a refresh for an account taken off the list as closed", async () => {
    const { fetch } = worker(() => json({ error: "not_allowed", nickname: "frog" }, 403));
    await expect(refreshTokens("refresh-1", { fetch })).resolves.toEqual({ kind: "closed", nickname: "frog" });
  });
});
