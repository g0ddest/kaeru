import { SELF } from "cloudflare:test";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";

/**
 * The token proxy: the one route that holds a credential.
 *
 * Shikimori is reached through the global `fetch`, which these tests replace — the worker under
 * test runs in this same isolate, so a stubbed global reaches it. Nothing here talks to the real
 * Shikimori, and the two OAuth values below are the ones vitest.config.ts binds, not the
 * deployment's.
 */
const CLIENT_ID = "test-client-id";
const CLIENT_SECRET = "test-client-secret";

const ORIGIN = "https://relay.test";
const TOKEN_URL = "https://shikimori.one/oauth/token";
const REDIRECT = "kaeru://oauth";

/** What the worker sent upstream, as the stub saw it. */
interface UpstreamCall {
  readonly url: string;
  readonly method: string;
  readonly headers: Record<string, string>;
  readonly body: string;
}

let calls: UpstreamCall[];
let reply: (call: UpstreamCall) => Response;

function json(status: number, body: unknown): Response {
  return new Response(JSON.stringify(body), {
    status,
    headers: { "content-type": "application/json" },
  });
}

/** The form the worker forwarded, as a plain object. */
function forwarded(call: UpstreamCall): Record<string, string> {
  return Object.fromEntries(new URLSearchParams(call.body));
}

let ipCounter = 0;

/** An address no other test used, so no two tests share a rate-limit bucket. */
function freshIp(): string {
  ipCounter += 1;
  return `198.51.100.${ipCounter}`;
}

interface PostOptions {
  readonly ip?: string;
  readonly method?: string;
}

function post(
  body: string | Record<string, string>,
  options: PostOptions = {},
): Promise<Response> {
  const form = typeof body === "string" ? body : new URLSearchParams(body).toString();
  return SELF.fetch(`${ORIGIN}/oauth/token`, {
    method: options.method ?? "POST",
    headers: {
      "content-type": "application/x-www-form-urlencoded",
      "CF-Connecting-IP": options.ip ?? freshIp(),
    },
    body: form,
  });
}

function codeExchange(overrides: Record<string, string> = {}): Record<string, string> {
  return {
    grant_type: "authorization_code",
    client_id: CLIENT_ID,
    code: "the-code",
    redirect_uri: REDIRECT,
    ...overrides,
  };
}

beforeEach(() => {
  calls = [];
  reply = () => json(200, { access_token: "acc", refresh_token: "ref", expires_in: 86400 });
  vi.stubGlobal("fetch", async (input: RequestInfo | URL, init?: RequestInit): Promise<Response> => {
    const request = new Request(input as RequestInfo, init);
    const call: UpstreamCall = {
      url: request.url,
      method: request.method,
      headers: Object.fromEntries(request.headers),
      // Bytes rather than `.text()`: the runtime warns about the latter on a form body.
      body: new TextDecoder().decode(await request.arrayBuffer()),
    };
    calls.push(call);
    return reply(call);
  });
});

afterEach(() => {
  vi.unstubAllGlobals();
});

describe("token exchange", () => {
  it("adds the secret the app no longer ships and returns Shikimori's answer", async () => {
    const response = await post(codeExchange());

    expect(response.status).toBe(200);
    expect(response.headers.get("content-type")).toBe("application/json");
    expect(await response.json()).toEqual({
      access_token: "acc",
      refresh_token: "ref",
      expires_in: 86400,
    });
    expect(calls).toHaveLength(1);
    expect(calls[0].url).toBe(TOKEN_URL);
    expect(calls[0].method).toBe("POST");
    expect(calls[0].headers["user-agent"]).toBe("Kaeru");
    expect(forwarded(calls[0])).toEqual({
      grant_type: "authorization_code",
      client_id: CLIENT_ID,
      code: "the-code",
      redirect_uri: REDIRECT,
      client_secret: CLIENT_SECRET,
    });
  });

  it("refreshes a token with the same secret", async () => {
    const response = await post({
      grant_type: "refresh_token",
      client_id: CLIENT_ID,
      refresh_token: "refresh-1",
    });

    expect(response.status).toBe(200);
    expect(forwarded(calls[0])).toEqual({
      grant_type: "refresh_token",
      client_id: CLIENT_ID,
      refresh_token: "refresh-1",
      client_secret: CLIENT_SECRET,
    });
  });

  it("passes an upstream 401 through verbatim", async () => {
    reply = () => json(401, { error: "invalid_grant", error_description: "bad code" });

    const response = await post(codeExchange());

    expect(response.status).toBe(401);
    expect(response.headers.get("content-type")).toBe("application/json");
    expect(await response.json()).toEqual({
      error: "invalid_grant",
      error_description: "bad code",
    });
  });

  it("passes an upstream 429 through so the app's own handling still fires", async () => {
    reply = () => json(429, { error: "too_many_requests" });

    expect((await post(codeExchange())).status).toBe(429);
  });

  it("answers 502 and says nothing more when Shikimori cannot be reached", async () => {
    reply = () => {
      throw new Error("connect ECONNREFUSED 1.2.3.4:443");
    };

    const response = await post(codeExchange());

    expect(response.status).toBe(502);
    expect(await response.text()).toBe("upstream unavailable");
  });
});

describe("what the proxy refuses", () => {
  it("refuses a method other than POST", async () => {
    const response = await SELF.fetch(`${ORIGIN}/oauth/token`, {
      headers: { "CF-Connecting-IP": freshIp() },
    });

    expect(response.status).toBe(405);
    expect(calls).toEqual([]);
  });

  it("refuses a client_id that is not this app's", async () => {
    const response = await post(codeExchange({ client_id: "someone-elses-app" }));

    expect(response.status).toBe(400);
    expect(calls).toEqual([]);
  });

  it("refuses a missing or unknown grant_type", async () => {
    const missing = await post({ client_id: CLIENT_ID, code: "the-code" });
    const unknown = await post({
      grant_type: "client_credentials",
      client_id: CLIENT_ID,
      code: "the-code",
    });

    expect(missing.status).toBe(400);
    expect(unknown.status).toBe(400);
    expect(calls).toEqual([]);
  });

  it("refuses an authorization_code grant with no code", async () => {
    const absent = await post({ grant_type: "authorization_code", client_id: CLIENT_ID });
    const empty = await post(codeExchange({ code: "" }));

    expect(absent.status).toBe(400);
    expect(empty.status).toBe(400);
    expect(calls).toEqual([]);
  });

  it("refuses a refresh_token grant with no refresh_token", async () => {
    const response = await post({ grant_type: "refresh_token", client_id: CLIENT_ID });

    expect(response.status).toBe(400);
    expect(calls).toEqual([]);
  });

  it("refuses a field the grant does not name, a client_secret above all", async () => {
    const stranger = await post(codeExchange({ scope: "user_rates" }));
    const ownSecret = await post(codeExchange({ client_secret: "attacker-secret" }));
    const wrongGrant = await post(codeExchange({ refresh_token: "refresh-1" }));

    expect(stranger.status).toBe(400);
    expect(ownSecret.status).toBe(400);
    expect(wrongGrant.status).toBe(400);
    expect(calls).toEqual([]);
  });

  it("refuses a body over 8 KB without reading it upstream", async () => {
    const response = await post(`${new URLSearchParams(codeExchange())}&x=${"a".repeat(9000)}`);

    expect(response.status).toBe(400);
    expect(calls).toEqual([]);
  });

  it("names a reason in plain text and never echoes what was sent", async () => {
    const response = await post(codeExchange({ client_id: "someone-elses-app" }));

    expect(response.headers.get("content-type")).toBe("text/plain; charset=utf-8");
    const body = await response.text();
    expect(body.length).toBeGreaterThan(0);
    expect(body).not.toContain("someone-elses-app");
    expect(body).not.toContain("the-code");
  });
});

describe("the secret stays in the worker", () => {
  it("never appears in any answer the proxy gives", async () => {
    reply = () => json(400, { error: "invalid_grant" });
    const refused = await post(codeExchange());
    reply = () => {
      throw new Error("no route to host");
    };
    const broken = await post(codeExchange());
    const foreign = await post(codeExchange({ client_id: "someone-elses-app" }));
    const method = await SELF.fetch(`${ORIGIN}/oauth/token`, {
      headers: { "CF-Connecting-IP": freshIp() },
    });

    for (const response of [refused, broken, foreign, method]) {
      expect(await response.text()).not.toContain(CLIENT_SECRET);
    }
  });
});

describe("rate limit", () => {
  it("lets thirty requests through and turns the thirty-first away", async () => {
    const ip = freshIp();

    for (let i = 0; i < 30; i += 1) {
      expect((await post(codeExchange(), { ip })).status, `request ${i + 1}`).toBe(200);
    }
    const refused = await post(codeExchange(), { ip });

    expect(refused.status).toBe(429);
    const retryAfter = Number(refused.headers.get("retry-after"));
    expect(retryAfter).toBeGreaterThanOrEqual(1);
    expect(retryAfter).toBeLessThanOrEqual(60);
    expect(calls).toHaveLength(30);
  });

  it("counts each address on its own", async () => {
    const ip = freshIp();
    for (let i = 0; i < 30; i += 1) await post(codeExchange(), { ip });

    expect((await post(codeExchange(), { ip })).status).toBe(429);
    expect((await post(codeExchange(), { ip: freshIp() })).status).toBe(200);
  });

  it("counts every request, refused ones included", async () => {
    const ip = freshIp();
    for (let i = 0; i < 30; i += 1) {
      expect((await post({ grant_type: "nonsense", client_id: CLIENT_ID }, { ip })).status).toBe(400);
    }

    expect((await post(codeExchange(), { ip })).status).toBe(429);
  });

  it("puts requests with no address into one shared bucket", async () => {
    const anonymous = (): Promise<Response> =>
      SELF.fetch(`${ORIGIN}/oauth/token`, {
        method: "POST",
        headers: { "content-type": "application/x-www-form-urlencoded" },
        body: new URLSearchParams(codeExchange()).toString(),
      });

    for (let i = 0; i < 30; i += 1) expect((await anonymous()).status).toBe(200);

    expect((await anonymous()).status).toBe(429);
  });
});
