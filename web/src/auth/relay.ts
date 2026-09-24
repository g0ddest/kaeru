import { CLIENT_ID, RELAY_URL } from "../config";

/** `createdAt` is in epoch SECONDS, as Shikimori sends it. */
export interface Tokens {
  accessToken: string;
  refreshToken: string;
  expiresIn: number;
  createdAt: number;
}

export type TokenResult =
  | { kind: "ok"; tokens: Tokens }
  | { kind: "closed"; nickname: string }
  | { kind: "sign_in" }
  | { kind: "invalid_grant" }
  | { kind: "unavailable" }
  | { kind: "throttled" }
  | { kind: "not_configured" }
  | { kind: "rejected" }
  | { kind: "network" };

const TOKEN_TIMEOUT_MS = 30_000;
const DEFAULT_EXPIRES_IN = 86_400;

export function exchangeCode(code: string, redirectUri: string, deps: { fetch?: typeof fetch } = {}): Promise<TokenResult> {
  return tokenCall(
    new URLSearchParams({ grant_type: "authorization_code", client_id: CLIENT_ID, code, redirect_uri: redirectUri }),
    deps.fetch,
  );
}

export function refreshTokens(refreshToken: string, deps: { fetch?: typeof fetch } = {}): Promise<TokenResult> {
  return tokenCall(new URLSearchParams({ grant_type: "refresh_token", client_id: CLIENT_ID, refresh_token: refreshToken }), deps.fetch);
}

async function tokenCall(form: URLSearchParams, send: typeof fetch = (input, init) => fetch(input, init)): Promise<TokenResult> {
  const controller = new AbortController();
  const timer = setTimeout(() => controller.abort(), TOKEN_TIMEOUT_MS);
  let status: number;
  let text: string;
  try {
    // No headers at all: the worker's preflight allows only authorization and content-type, and a
    // URLSearchParams body gets the form content type and the Content-Length the worker requires.
    const response = await send(`${RELAY_URL}/oauth/token`, { method: "POST", body: form, signal: controller.signal });
    status = response.status;
    text = await response.text();
  } catch {
    return { kind: "network" };
  } finally {
    clearTimeout(timer);
  }
  return interpret(status, parseObject(text));
}

function parseObject(text: string): Record<string, unknown> | null {
  try {
    const value: unknown = JSON.parse(text);
    return typeof value === "object" && value !== null && !Array.isArray(value) ? (value as Record<string, unknown>) : null;
  } catch {
    // The worker's own refusals are plain text.
    return null;
  }
}

/** Told apart by the JSON `error` field, not by the status alone. */
function interpret(status: number, body: Record<string, unknown> | null): TokenResult {
  const error = body?.["error"];
  if (status >= 200 && status <= 299) {
    const tokens = readTokens(body);
    // A 200 without both tokens is an answer nobody could read, like the worker's own 502.
    return tokens === null ? { kind: "unavailable" } : { kind: "ok", tokens };
  }
  if ((status === 400 || status === 401) && error === "invalid_grant") return { kind: "invalid_grant" };
  if (status === 401 && error === "sign_in") return { kind: "sign_in" };
  if (status === 403 && error === "not_allowed") {
    const nickname = body?.["nickname"];
    return { kind: "closed", nickname: typeof nickname === "string" ? nickname : "" };
  }
  if (status === 429) return { kind: "throttled" };
  if (status === 503) return { kind: "not_configured" };
  if (status >= 500) return { kind: "unavailable" };
  return { kind: "rejected" };
}

function readTokens(body: Record<string, unknown> | null): Tokens | null {
  const accessToken = body?.["access_token"];
  const refreshToken = body?.["refresh_token"];
  if (typeof accessToken !== "string" || accessToken.trim() === "") return null;
  if (typeof refreshToken !== "string" || refreshToken.trim() === "") return null;
  const expiresIn = Number(body?.["expires_in"] ?? DEFAULT_EXPIRES_IN);
  const createdAt = Number(body?.["created_at"] ?? 0);
  return {
    accessToken,
    refreshToken,
    expiresIn: Number.isFinite(expiresIn) && expiresIn > 0 ? expiresIn : DEFAULT_EXPIRES_IN,
    // Shikimori's own clock when it says; this device's otherwise (iOS does the same).
    createdAt: Number.isFinite(createdAt) && createdAt > 0 ? createdAt : Math.floor(Date.now() / 1000),
  };
}
