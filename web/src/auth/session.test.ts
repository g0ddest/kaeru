// Vectors: android/src/test/java/app/kaeru/data/shikimori/ShikimoriSessionTest.kt (one refresh after a 401,
// one retry, compare-and-set, which failures keep the session); ios/Core/AppModel.swift:375-423 (refresh ahead
// under 60 s); ios/Core/Models.swift:52-61 (stored session shape)
import { act, renderHook } from "@testing-library/react";
import { beforeEach, describe, expect, it, vi } from "vitest";
import { ApiError, NetworkError } from "../api/http";
import type { TokenResult, Tokens } from "./relay";
import { SessionStore, authorized, sessionStore, useAccess } from "./session";
import type { Session } from "./session";

const account = { id: 42, nickname: "frog", avatar: null };
const tokens = (accessToken: string, refreshToken: string, createdAt = 1_000): Tokens => ({ accessToken, refreshToken, expiresIn: 86_400, createdAt });
const session = (access = "old", refresh = "refresh-1"): Session => ({ account, tokens: tokens(access, refresh) });
/** 1 000 s after the epoch: a day before the test tokens expire. */
const now = (): number => 1_000_000;

function storeWith(initial: Session | null = session()): SessionStore {
  localStorage.clear();
  const store = new SessionStore(localStorage);
  if (initial !== null) store.setSession(initial);
  return store;
}

function refresher(...answers: TokenResult[]) {
  const seen: string[] = [];
  const refresh = async (refreshToken: string): Promise<TokenResult> => {
    seen.push(refreshToken);
    return answers.shift() ?? { kind: "network" };
  };
  return { refresh, seen };
}

beforeEach(() => {
  localStorage.clear();
  sessionStore.signOut();
});

describe("SessionStore", () => {
  it("starts signed out and keeps a session in the iOS shape under kaeru.session", () => {
    const store = storeWith(null);
    expect(store.get()).toEqual({ kind: "signed_out", message: null });

    store.setSession(session());

    expect(store.get()).toEqual({ kind: "signed_in", session: session() });
    expect(JSON.parse(localStorage.getItem("kaeru.session") ?? "null")).toEqual({
      account: { id: 42, nickname: "frog", avatar: null },
      tokens: { access_token: "old", refresh_token: "refresh-1", expires_in: 86_400, created_at: 1_000 },
    });
    expect(new SessionStore(localStorage).get()).toEqual({ kind: "signed_in", session: session() });
  });

  it("returns the same object until something changes", () => {
    const store = storeWith();
    expect(store.get()).toBe(store.get());
  });

  it("remembers a closed account across reloads and forgets everything on sign-out", () => {
    const store = storeWith();
    store.setClosed("stranger");
    expect(store.get()).toEqual({ kind: "closed", nickname: "stranger" });
    expect(new SessionStore(localStorage).get()).toEqual({ kind: "closed", nickname: "stranger" });

    store.signOut("Сессия истекла, войдите снова");
    expect(store.get()).toEqual({ kind: "signed_out", message: "Сессия истекла, войдите снова" });
    expect(localStorage.getItem("kaeru.session")).toBeNull();
  });

  it("reads anything unreadable as signed out", () => {
    localStorage.setItem("kaeru.session", "{not json");
    expect(new SessionStore(localStorage).get()).toEqual({ kind: "signed_out", message: null });
    localStorage.setItem("kaeru.session", JSON.stringify({ account: { id: 42 }, tokens: {} }));
    expect(new SessionStore(localStorage).get()).toEqual({ kind: "signed_out", message: null });
  });

  it("keeps a change in this tab when storage refuses to save it", () => {
    const saved = new Map<string, string>();
    const full = {
      getItem: (key: string) => saved.get(key) ?? null,
      setItem: () => {
        throw new DOMException("The quota has been exceeded.", "QuotaExceededError");
      },
      removeItem: (key: string) => void saved.delete(key),
    } as unknown as Storage;
    const store = new SessionStore(full);

    store.setSession(session());
    expect(store.get()).toEqual({ kind: "signed_in", session: session() });

    store.signOut("Сессия истекла, войдите снова");
    expect(store.get()).toEqual({ kind: "signed_out", message: "Сессия истекла, войдите снова" });
  });

  it("tells subscribers about every change until they leave", () => {
    const store = storeWith(null);
    const heard = vi.fn();
    const leave = store.subscribe(heard);
    store.setSession(session());
    store.setClosed("stranger");
    leave();
    store.signOut();
    expect(heard).toHaveBeenCalledTimes(2);
  });

  it("follows a sign-in or sign-out made in another tab", () => {
    const store = storeWith(null);
    const heard = vi.fn();
    store.subscribe(heard);

    localStorage.setItem(
      "kaeru.session",
      JSON.stringify({ account, tokens: { access_token: "tab", refresh_token: "r", expires_in: 86_400, created_at: 1_000 } }),
    );
    window.dispatchEvent(new StorageEvent("storage", { key: "kaeru.session", storageArea: localStorage }));

    expect(heard).toHaveBeenCalledTimes(1);
    expect(store.get()).toEqual({ kind: "signed_in", session: session("tab", "r") });
  });
});

describe("useAccess", () => {
  it("follows the module's store", () => {
    const { result, unmount } = renderHook(() => useAccess());
    expect(result.current).toEqual({ kind: "signed_out", message: null });

    act(() => sessionStore.setSession(session()));
    expect(result.current).toEqual({ kind: "signed_in", session: session() });

    act(() => sessionStore.signOut("Сессия истекла, войдите снова"));
    expect(result.current).toEqual({ kind: "signed_out", message: "Сессия истекла, войдите снова" });
    unmount();
  });
});

describe("authorized", () => {
  it("refreshes once on a 401 and retries with the new bearer", async () => {
    const store = storeWith();
    const { refresh, seen } = refresher({ kind: "ok", tokens: tokens("new", "refresh-2", 1_757_600_000) });
    const bearers: string[] = [];

    const result = await authorized(
      async (token) => {
        bearers.push(token);
        if (token === "old") throw new ApiError(401);
        return 42;
      },
      { store, refresh, locks: null, now },
    );

    expect(result).toBe(42);
    expect(bearers).toEqual(["old", "new"]);
    expect(seen).toEqual(["refresh-1"]);
    expect(store.get()).toEqual({ kind: "signed_in", session: { account, tokens: tokens("new", "refresh-2", 1_757_600_000) } });
  });

  it("ends the session on invalid_grant or sign_in, and the 401 stands", async () => {
    for (const verdict of [{ kind: "invalid_grant" }, { kind: "sign_in" }] as const) {
      const store = storeWith();
      const { refresh } = refresher(verdict);
      let calls = 0;

      const error = await authorized(
        async () => {
          calls += 1;
          throw new ApiError(401);
        },
        { store, refresh, locks: null, now },
      ).catch((caught: unknown) => caught);

      expect(error).toBeInstanceOf(ApiError);
      expect(error).toMatchObject({ status: 401 });
      expect(calls).toBe(1);
      expect(store.get()).toEqual({ kind: "signed_out", message: "Сессия истекла, войдите снова" });
    }
  });

  it("closes access when the account was taken off the list", async () => {
    const store = storeWith();
    const { refresh } = refresher({ kind: "closed", nickname: "frog" });

    const error = await authorized(
      async () => {
        throw new ApiError(401);
      },
      { store, refresh, locks: null, now },
    ).catch((caught: unknown) => caught);

    expect(error).toMatchObject({ status: 401 });
    expect(store.get()).toEqual({ kind: "closed", nickname: "frog" });
  });

  it("keeps the session when the refresh fails without a verdict on it", async () => {
    const cases: Array<[TokenResult, (error: unknown) => void]> = [
      [{ kind: "network" }, (error) => expect(error).toBeInstanceOf(NetworkError)],
      [{ kind: "throttled" }, (error) => expect(error).toMatchObject({ status: 429 })],
      [{ kind: "unavailable" }, (error) => expect(error).toMatchObject({ status: 502 })],
      [{ kind: "not_configured" }, (error) => expect(error).toMatchObject({ status: 503 })],
      [{ kind: "rejected" }, (error) => expect(error).toMatchObject({ status: 401 })],
    ];
    for (const [answer, check] of cases) {
      const store = storeWith();
      const { refresh, seen } = refresher(answer);
      let calls = 0;

      const error = await authorized(
        async () => {
          calls += 1;
          throw new ApiError(401);
        },
        { store, refresh, locks: null, now },
      ).catch((caught: unknown) => caught);

      check(error);
      expect(seen).toEqual(["refresh-1"]);
      expect(calls).toBe(1);
      expect(store.get()).toEqual({ kind: "signed_in", session: session() });
    }
  });

  it("does not refresh a second time when the retry is refused too", async () => {
    const store = storeWith();
    const { refresh, seen } = refresher({ kind: "ok", tokens: tokens("new", "refresh-2") });
    let calls = 0;

    const error = await authorized(
      async () => {
        calls += 1;
        throw new ApiError(401);
      },
      { store, refresh, locks: null, now },
    ).catch((caught: unknown) => caught);

    expect(error).toMatchObject({ status: 401 });
    expect(calls).toBe(2);
    expect(seen).toHaveLength(1);
  });

  it("sends nothing without a session", async () => {
    const store = storeWith(null);
    const { refresh, seen } = refresher();
    const call = vi.fn(async () => 1);

    const error = await authorized(call, { store, refresh, locks: null, now }).catch((caught: unknown) => caught);

    expect(error).toMatchObject({ status: 401 });
    expect(call).not.toHaveBeenCalled();
    expect(seen).toEqual([]);
  });

  it("reuses a token another tab already rotated to, without refreshing", async () => {
    const store = storeWith();
    const { refresh, seen } = refresher();
    const bearers: string[] = [];

    const result = await authorized(
      async (token) => {
        bearers.push(token);
        if (token !== "old") return 42;
        store.setSession(session("rotated", "refresh-2"));
        throw new ApiError(401);
      },
      { store, refresh, locks: null, now },
    );

    expect(result).toBe(42);
    expect(bearers).toEqual(["old", "rotated"]);
    expect(seen).toEqual([]);
  });

  it("refreshes ahead when less than a minute is left, and not at exactly a minute", async () => {
    const expiry = (1_000 + 86_400) * 1000;

    const early = storeWith();
    const first = refresher({ kind: "ok", tokens: tokens("new", "refresh-2", 87_340) });
    const bearers: string[] = [];
    await authorized(async (token) => void bearers.push(token), { store: early, refresh: first.refresh, locks: null, now: () => expiry - 59_000 });
    expect(first.seen).toEqual(["refresh-1"]);
    expect(bearers).toEqual(["new"]);

    const onTime = storeWith();
    const second = refresher();
    await authorized(async (token) => void bearers.push(token), { store: onTime, refresh: second.refresh, locks: null, now: () => expiry - 60_000 });
    expect(second.seen).toEqual([]);
    expect(bearers).toEqual(["new", "old"]);
  });

  it("passes other failures through without a refresh", async () => {
    for (const failure of [new ApiError(500), new NetworkError(), new ApiError(403)]) {
      const store = storeWith();
      const { refresh, seen } = refresher();
      const error = await authorized(
        async () => {
          throw failure;
        },
        { store, refresh, locks: null, now },
      ).catch((caught: unknown) => caught);
      expect(error).toBe(failure);
      expect(seen).toEqual([]);
    }
  });

  it("refreshes once for concurrent 401s", async () => {
    const store = storeWith();
    const seen: string[] = [];
    let release!: (result: TokenResult) => void;
    const refresh = (refreshToken: string): Promise<TokenResult> => {
      seen.push(refreshToken);
      return new Promise<TokenResult>((resolve) => {
        release = resolve;
      });
    };
    const call = async (token: string): Promise<string> => {
      if (token === "old") throw new ApiError(401);
      return token;
    };

    const both = Promise.all([authorized(call, { store, refresh, locks: null, now }), authorized(call, { store, refresh, locks: null, now })]);
    await vi.waitFor(() => expect(seen).toHaveLength(1));
    release({ kind: "ok", tokens: tokens("new", "refresh-2") });

    await expect(both).resolves.toEqual(["new", "new"]);
    expect(seen).toEqual(["refresh-1"]);
  });

  it("refreshes under the kaeru-refresh Web Lock when there is one", async () => {
    const store = storeWith();
    const names: string[] = [];
    const locks = {
      request: (name: string, callback: () => Promise<unknown>) => {
        names.push(name);
        return callback();
      },
    } as unknown as LockManager;
    const { refresh } = refresher({ kind: "ok", tokens: tokens("new", "refresh-2") });

    await authorized(
      async (token) => {
        if (token === "old") throw new ApiError(401);
        return token;
      },
      { store, refresh, locks, now },
    );

    expect(names).toEqual(["kaeru-refresh"]);
  });

  it("never lets an in-flight refresh overwrite a sign-out or a newer sign-in", async () => {
    const replacements: Array<Session | null> = [
      null,
      { account: { id: 7, nickname: "other", avatar: null }, tokens: tokens("login", "login-refresh") },
      // Identical credentials still count as a new session.
      session(),
    ];
    for (const replacement of replacements) {
      for (const succeeds of [true, false]) {
        const store = storeWith();
        let started!: () => void;
        const refreshing = new Promise<void>((resolve) => {
          started = resolve;
        });
        let release!: (result: TokenResult) => void;
        const refresh = (): Promise<TokenResult> => {
          started();
          return new Promise<TokenResult>((resolve) => {
            release = resolve;
          });
        };
        let calls = 0;
        const pending = authorized(
          async () => {
            calls += 1;
            throw new ApiError(401);
          },
          { store, refresh, locks: null, now },
        ).catch((caught: unknown) => caught);

        await refreshing;
        if (replacement === null) store.signOut();
        else store.setSession(replacement);
        const expected = store.get();
        release(succeeds ? { kind: "ok", tokens: tokens("stale-refresh", "stale-refresh-token") } : { kind: "invalid_grant" });

        const error = await pending;
        expect(error).toBeInstanceOf(ApiError);
        expect(error).toMatchObject({ status: 401 });
        expect(store.get()).toBe(expected);
        expect(calls).toBe(1);
      }
    }
  });
});
