import { useSyncExternalStore } from "react";
import { ApiError, NetworkError } from "../api/http";
import type { Account } from "../api/shikimori";
import { refreshTokens } from "./relay";
import type { TokenResult, Tokens } from "./relay";

export interface Session {
  account: Account;
  tokens: Tokens;
}

export type AccessState =
  | { kind: "signed_out"; message: string | null }
  | { kind: "closed"; nickname: string }
  | { kind: "signed_in"; session: Session };

const SESSION_KEY = "kaeru.session";
const EXPIRED = "Сессия истекла, войдите снова";
const AHEAD_MS = 60_000;
const LOCK_NAME = "kaeru-refresh";

function record(value: unknown): Record<string, unknown> | null {
  return typeof value === "object" && value !== null && !Array.isArray(value) ? (value as Record<string, unknown>) : null;
}

/** The iOS Keychain shape: the endpoint's own snake_case token keys. */
function encodeSession(session: Session): string {
  const { account, tokens } = session;
  return JSON.stringify({
    account: { id: account.id, nickname: account.nickname, avatar: account.avatar },
    tokens: {
      access_token: tokens.accessToken,
      refresh_token: tokens.refreshToken,
      expires_in: tokens.expiresIn,
      created_at: tokens.createdAt,
    },
  });
}

function decode(raw: string | null): AccessState {
  let root: Record<string, unknown> | null = null;
  try {
    root = raw === null ? null : record(JSON.parse(raw));
  } catch {
    root = null;
  }
  const closed = record(root?.["closed"]);
  const closedNickname = closed?.["nickname"];
  if (typeof closedNickname === "string") return { kind: "closed", nickname: closedNickname };
  const account = record(root?.["account"]);
  const tokens = record(root?.["tokens"]);
  const id = account?.["id"];
  const nickname = account?.["nickname"];
  const avatar = account?.["avatar"];
  const accessToken = tokens?.["access_token"];
  const refreshToken = tokens?.["refresh_token"];
  const expiresIn = tokens?.["expires_in"];
  const createdAt = tokens?.["created_at"];
  if (
    typeof id !== "number" || id <= 0 ||
    typeof nickname !== "string" ||
    (avatar !== null && typeof avatar !== "string") ||
    typeof accessToken !== "string" || accessToken === "" ||
    typeof refreshToken !== "string" || refreshToken === "" ||
    typeof expiresIn !== "number" || typeof createdAt !== "number"
  ) {
    return { kind: "signed_out", message: null };
  }
  return {
    kind: "signed_in",
    session: { account: { id, nickname, avatar }, tokens: { accessToken, refreshToken, expiresIn, createdAt } },
  };
}

function defaultStorage(): Storage | null {
  try {
    return typeof window === "undefined" ? null : window.localStorage;
  } catch {
    // Blocked storage (some private modes) throws on access; the tab keeps state in memory.
    return null;
  }
}

/**
 * Who is signed in, kept in localStorage so every tab shares it. `get()` re-reads storage, so a
 * write from another tab is seen at once, and returns the same object while nothing changed, as
 * `useSyncExternalStore` needs.
 */
export class SessionStore {
  private readonly storage: Storage | null;
  private readonly listeners = new Set<() => void>();
  private raw: string | null = null;
  private state: AccessState;
  /** The last write did not reach storage: this tab keeps its own copy until a write succeeds. */
  private detached = false;

  private readonly onStorage = (event: StorageEvent): void => {
    if (event.storageArea !== this.storage) return;
    if (event.key !== null && event.key !== SESSION_KEY) return;
    const before = this.state;
    if (this.get() !== before) this.emit();
  };

  constructor(storage?: Storage) {
    this.storage = storage ?? defaultStorage();
    this.raw = this.read();
    this.state = decode(this.raw);
    if (typeof window !== "undefined") window.addEventListener("storage", this.onStorage);
  }

  get(): AccessState {
    const raw = this.read();
    if (raw !== this.raw) {
      this.raw = raw;
      this.state = decode(raw);
    }
    return this.state;
  }

  subscribe(listener: () => void): () => void {
    this.listeners.add(listener);
    return () => {
      this.listeners.delete(listener);
    };
  }

  setSession(session: Session): void {
    this.write(encodeSession(session), null);
  }

  setClosed(nickname: string): void {
    this.write(JSON.stringify({ closed: { nickname } }), null);
  }

  /** Local only: neither app revokes the token on the server. */
  signOut(message: string | null = null): void {
    this.write(null, message);
  }

  private read(): string | null {
    if (this.storage === null || this.detached) return this.raw;
    try {
      return this.storage.getItem(SESSION_KEY);
    } catch {
      return this.raw;
    }
  }

  private write(raw: string | null, message: string | null): void {
    try {
      if (raw === null) this.storage?.removeItem(SESSION_KEY);
      else this.storage?.setItem(SESSION_KEY, raw);
      this.detached = false;
    } catch {
      // A full or blocked storage: this tab still follows the change, rather than re-reading the
      // stale value and losing a sign-in or a refresh token Shikimori has already rotated.
      this.detached = true;
    }
    this.raw = raw;
    // Always a new object, even for identical data: an in-flight refresh compares by identity.
    this.state = raw === null ? { kind: "signed_out", message } : decode(raw);
    this.emit();
  }

  private emit(): void {
    for (const listener of [...this.listeners]) listener();
  }
}

export const sessionStore = new SessionStore();

const subscribeAccess = (listener: () => void): (() => void) => sessionStore.subscribe(listener);
const readAccess = (): AccessState => sessionStore.get();

export function useAccess(): AccessState {
  return useSyncExternalStore(subscribeAccess, readAccess);
}

type Rotation = { kind: "token"; token: string } | { kind: "ended" } | { kind: "kept"; error: Error | null };

/** Without Web Locks, refreshes in this tab still run one at a time. */
let serial: Promise<unknown> = Promise.resolve();

async function withLock<T>(locks: LockManager | null, task: () => Promise<T>): Promise<T> {
  if (locks !== null) {
    const result: unknown = await locks.request(LOCK_NAME, () => task());
    return result as T;
  }
  const run = serial.then(() => task());
  serial = run.catch(() => undefined);
  return run;
}

function browserLocks(): LockManager | null {
  return typeof navigator !== "undefined" && navigator.locks ? navigator.locks : null;
}

function expiresAt(tokens: Tokens): number {
  return (tokens.createdAt + tokens.expiresIn) * 1000;
}

/** A refresh that failed without a verdict on the session, as the error the caller understands. */
function refreshFailure(result: TokenResult): Error | null {
  switch (result.kind) {
    case "network":
      return new NetworkError();
    case "throttled":
      return new ApiError(429);
    case "unavailable":
      return new ApiError(502);
    case "not_configured":
      return new ApiError(503);
    default:
      // The worker's own plain refusals say nothing about the request; the 401 stands.
      return null;
  }
}

/**
 * A token to replace `stale`: one another request or tab already rotated to, or a refreshed one.
 * Runs under the lock and re-reads the store inside it, because tabs share one rotating refresh
 * token and two of them spending it would earn `invalid_grant`.
 */
function rotate(store: SessionStore, refresh: typeof refreshTokens, locks: LockManager | null, stale: string, allowRefresh: boolean): Promise<Rotation> {
  return withLock(locks, async (): Promise<Rotation> => {
    const before = store.get();
    if (before.kind !== "signed_in") return { kind: "ended" };
    const current = before.session.tokens.accessToken;
    if (current !== stale) return { kind: "token", token: current };
    if (!allowRefresh) return { kind: "kept", error: null };
    const result = await refresh(before.session.tokens.refreshToken);
    // Compare-and-set: a sign-out or a new sign-in that landed meanwhile wins over this answer.
    if (store.get() !== before) return { kind: "kept", error: null };
    switch (result.kind) {
      case "ok":
        store.setSession({ account: before.session.account, tokens: result.tokens });
        return { kind: "token", token: result.tokens.accessToken };
      case "invalid_grant":
      case "sign_in":
        store.signOut(EXPIRED);
        return { kind: "ended" };
      case "closed":
        store.setClosed(result.nickname);
        return { kind: "ended" };
      default:
        return { kind: "kept", error: refreshFailure(result) };
    }
  });
}

/**
 * `call` with the session's bearer: refreshed ahead when under a minute is left, and refreshed
 * once more (at most one refresh per call) and retried once after a 401. Only `invalid_grant`,
 * `sign_in` and `closed` end the session.
 */
export async function authorized<T>(
  call: (token: string) => Promise<T>,
  deps: { store?: SessionStore; refresh?: typeof refreshTokens; locks?: LockManager | null; now?: () => number } = {},
): Promise<T> {
  const store = deps.store ?? sessionStore;
  const refresh = deps.refresh ?? refreshTokens;
  const locks = deps.locks === undefined ? browserLocks() : deps.locks;
  const now = deps.now ?? Date.now;

  const state = store.get();
  // Nothing to authorize with: the gate shows sign-in; nothing is sent.
  if (state.kind !== "signed_in") throw new ApiError(401);
  let token = state.session.tokens.accessToken;
  let refreshed = false;
  if (expiresAt(state.session.tokens) - now() < AHEAD_MS) {
    refreshed = true;
    const ahead = await rotate(store, refresh, locks, token, true);
    if (ahead.kind === "token") token = ahead.token;
    else if (ahead.kind === "ended") throw new ApiError(401);
    // Kept: the old token may still work, and a wrong clock must not block every call.
  }
  try {
    return await call(token);
  } catch (error) {
    if (!(error instanceof ApiError) || error.status !== 401) throw error;
    const rotation = await rotate(store, refresh, locks, token, !refreshed);
    if (rotation.kind === "token") return call(rotation.token);
    if (rotation.kind === "kept" && rotation.error !== null) throw rotation.error;
    throw error;
  }
}
