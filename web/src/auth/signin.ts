import { CLIENT_ID, SHIKIMORI_URL, redirectUri } from "../config";
import { createShikimoriHttp, errorMessage } from "../api/http";
import { createShikimori } from "../api/shikimori";
import type { Account, Shikimori } from "../api/shikimori";
import { exchangeCode } from "./relay";
import type { TokenResult } from "./relay";
import { sessionStore } from "./session";
import type { SessionStore } from "./session";

const PENDING_KEY = "kaeru.signin";
const STATE_BYTES = 32;

const REJECTED = "Не удалось подтвердить вход. Войдите заново";
const NO_CODE = "Shikimori не вернул код. Попробуйте войти ещё раз";
const ALREADY_SIGNED_IN = "Вход уже выполнен. Запрос авторизации отклонён";
const UNKNOWN = "Что-то пошло не так. Повторите попытку";

export type SignInResult = { kind: "done"; returnTo: string } | { kind: "closed"; nickname: string } | { kind: "error"; message: string };

interface Pending {
  state: string;
  returnTo: string;
}

function base64Url(bytes: Uint8Array): string {
  let binary = "";
  for (const byte of bytes) binary += String.fromCharCode(byte);
  return btoa(binary).replace(/\+/g, "-").replace(/\//g, "_").replace(/=+$/, "");
}

/** Only a path on this site, and never the callback itself: anything else is an open redirect or a loop. */
function safeReturnTo(path: string): string {
  if (!path.startsWith("/") || path.startsWith("//") || path.startsWith("/\\")) return "/";
  return /^\/auth(?:[/?#]|$)/.test(path) ? "/" : path;
}

function readPending(raw: string | null): Pending | null {
  try {
    const value: unknown = raw === null ? null : JSON.parse(raw);
    if (typeof value !== "object" || value === null) return null;
    const { state, returnTo } = value as { state?: unknown; returnTo?: unknown };
    if (typeof state !== "string" || state === "" || typeof returnTo !== "string") return null;
    return { state, returnTo: safeReturnTo(returnTo) };
  } catch {
    return null;
  }
}

/** No early exit on the first differing character, like the apps' MessageDigest.isEqual. */
function sameText(actual: string, expected: string): boolean {
  let diff = actual.length ^ expected.length;
  for (let i = 0; i < expected.length; i += 1) diff |= (actual.charCodeAt(i) || 0) ^ expected.charCodeAt(i);
  return diff === 0;
}

/**
 * Arms a sign-in and returns Shikimori's authorize page. The state lives in sessionStorage because
 * the page reloads on the way back, and it names where to return, a pending invitation included.
 */
export function beginSignIn(
  returnTo: string,
  deps: { storage?: Storage; random?: (bytes: Uint8Array) => Uint8Array; origin?: string } = {},
): string {
  const storage = deps.storage ?? window.sessionStorage;
  const random = deps.random ?? ((bytes: Uint8Array) => crypto.getRandomValues(bytes));
  const origin = deps.origin ?? window.location.origin;
  const state = base64Url(random(new Uint8Array(STATE_BYTES)));
  const pending: Pending = { state, returnTo: safeReturnTo(returnTo) };
  storage.setItem(PENDING_KEY, JSON.stringify(pending));
  return (
    `${SHIKIMORI_URL}/oauth/authorize?client_id=${encodeURIComponent(CLIENT_ID)}` +
    `&redirect_uri=${encodeURIComponent(redirectUri(origin))}` +
    `&response_type=code&scope=user_rates&state=${encodeURIComponent(state)}`
  );
}

export function signInErrorMessage(result: TokenResult): string {
  switch (result.kind) {
    case "unavailable":
      return "Shikimori недоступен, попробуйте позже";
    case "throttled":
      return "Слишком много запросов, попробуйте позже";
    case "not_configured":
      return "Вход временно недоступен, попробуйте позже";
    case "network":
      return "Нет соединения. Проверьте интернет";
    default:
      return UNKNOWN;
  }
}

/**
 * The /auth callback: checks the state, exchanges the code through the worker, asks whoami with
 * the new token and only then saves the session (Android's order). The pending attempt is
 * consumed first, so a reload or a replayed link never spends a code twice.
 */
export async function completeSignIn(
  search: string,
  deps: {
    storage?: Storage;
    store?: SessionStore;
    exchange?: typeof exchangeCode;
    shikimori?: Pick<Shikimori, "whoami">;
    origin?: string;
  } = {},
): Promise<SignInResult> {
  const storage = deps.storage ?? window.sessionStorage;
  const store = deps.store ?? sessionStore;
  const exchange = deps.exchange ?? exchangeCode;
  const origin = deps.origin ?? window.location.origin;

  const pending = readPending(storage.getItem(PENDING_KEY));
  storage.removeItem(PENDING_KEY);

  // A silent account switch would replace the signed-in viewer's list.
  if (store.get().kind === "signed_in") return { kind: "error", message: ALREADY_SIGNED_IN };
  const params = new URLSearchParams(search);
  const codes = params.getAll("code");
  const states = params.getAll("state");
  const code = codes[0] ?? "";
  if (code.trim() === "") return { kind: "error", message: NO_CODE };
  const state = states[0];
  if (pending === null || codes.length !== 1 || states.length !== 1 || state === undefined || !sameText(state, pending.state)) {
    return { kind: "error", message: REJECTED };
  }

  const result = await exchange(code, redirectUri(origin));
  if (result.kind === "closed") {
    store.setClosed(result.nickname);
    return { kind: "closed", nickname: result.nickname };
  }
  if (result.kind !== "ok") return { kind: "error", message: signInErrorMessage(result) };

  const shikimori = deps.shikimori ?? createShikimori(createShikimoriHttp());
  let account: Account | null;
  try {
    account = await shikimori.whoami(result.tokens.accessToken);
  } catch (error) {
    return { kind: "error", message: errorMessage(error) };
  }
  if (account === null) return { kind: "error", message: UNKNOWN };
  store.setSession({ account, tokens: result.tokens });
  return { kind: "done", returnTo: pending.returnTo };
}
