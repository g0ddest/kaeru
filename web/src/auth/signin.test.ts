// Vectors: android/src/test/java/app/kaeru/data/auth/ShikimoriAuthRepositoryTest.kt (authorize URL, single-use
// state, no account switch, whoami before the session is saved), android/.../ui/common/auth/AuthViewModel.kt and
// android/src/main/java/app/kaeru/ui/common/ErrorMessages.kt (copy)
import { beforeEach, describe, expect, it } from "vitest";
import { ApiError, NetworkError } from "../api/http";
import type { Account } from "../api/shikimori";
import type { TokenResult, Tokens } from "./relay";
import { SessionStore } from "./session";
import { beginSignIn, completeSignIn, signInErrorMessage } from "./signin";

const ORIGIN = "http://localhost:5173";
/** base64url of the bytes 0 to 31 in order. */
const COUNTING_STATE = "AAECAwQFBgcICQoLDA0ODxAREhMUFRYXGBkaGxwdHh8";
const counting = (bytes: Uint8Array): Uint8Array => {
  bytes.forEach((_, index) => {
    bytes[index] = index;
  });
  return bytes;
};
const TOKENS: Tokens = { accessToken: "acc", refreshToken: "ref", expiresIn: 86_400, createdAt: 1_757_600_000 };
const FROG: Account = { id: 42, nickname: "frog", avatar: "https://shikimori.io/frog.png" };

function exchanger(result: TokenResult) {
  const calls: Array<[string, string]> = [];
  const exchange = async (code: string, redirect: string): Promise<TokenResult> => {
    calls.push([code, redirect]);
    return result;
  };
  return { exchange, calls };
}

function identity(answer: () => Promise<Account | null>) {
  const tokens: string[] = [];
  const shikimori = {
    whoami: (token: string): Promise<Account | null> => {
      tokens.push(token);
      return answer();
    },
  };
  return { shikimori, tokens };
}

function arm(returnTo = "/anime/1535"): void {
  beginSignIn(returnTo, { storage: sessionStorage, random: counting, origin: ORIGIN });
}

beforeEach(() => {
  sessionStorage.clear();
  localStorage.clear();
});

describe("beginSignIn", () => {
  it("builds Shikimori's authorize URL with the site's redirect and remembers the attempt", () => {
    const url = new URL(beginSignIn("/anime/1535", { storage: sessionStorage, random: counting, origin: ORIGIN }));

    expect(`${url.origin}${url.pathname}`).toBe("https://shikimori.io/oauth/authorize");
    const keys: string[] = [];
    url.searchParams.forEach((_value, key) => keys.push(key));
    expect(keys).toEqual(["client_id", "redirect_uri", "response_type", "scope", "state"]);
    expect(url.searchParams.get("client_id")).toBe("_MQPkUPZ7AUhCQBBnQhdipfXDTQpBmT5JtpRByuFXeg");
    expect(url.searchParams.get("redirect_uri")).toBe("http://localhost:5173/auth");
    expect(url.searchParams.get("response_type")).toBe("code");
    expect(url.searchParams.get("scope")).toBe("user_rates");
    expect(url.searchParams.get("state")).toBe(COUNTING_STATE);
    expect(url.search).toContain("redirect_uri=http%3A%2F%2Flocalhost%3A5173%2Fauth");
    expect(JSON.parse(sessionStorage.getItem("kaeru.signin") ?? "null")).toEqual({ state: COUNTING_STATE, returnTo: "/anime/1535" });
  });

  it("encodes the state as base64url without padding", () => {
    const url = new URL(beginSignIn("/", { storage: sessionStorage, random: (bytes) => bytes.fill(255), origin: ORIGIN }));
    expect(url.searchParams.get("state")).toBe(`${"_".repeat(42)}8`);
  });

  it("draws a fresh unguessable state per attempt", () => {
    const first = new URL(beginSignIn("/", { storage: sessionStorage, origin: ORIGIN })).searchParams.get("state") ?? "";
    const second = new URL(beginSignIn("/", { storage: sessionStorage, origin: ORIGIN })).searchParams.get("state") ?? "";
    expect(first).toMatch(/^[A-Za-z0-9_-]{43}$/);
    expect(second).not.toBe(first);
  });

  it("returns only to a path on this site, never to the callback", () => {
    const cases: Array<[string, string]> = [
      ["/w/AAAAAAAAAAA#key", "/w/AAAAAAAAAAA#key"],
      ["//evil.example/x", "/"],
      ["https://evil.example/x", "/"],
      ["/auth?code=1", "/"],
      ["", "/"],
    ];
    for (const [asked, kept] of cases) {
      beginSignIn(asked, { storage: sessionStorage, random: counting, origin: ORIGIN });
      expect(JSON.parse(sessionStorage.getItem("kaeru.signin") ?? "null")).toEqual({ state: COUNTING_STATE, returnTo: kept });
    }
  });
});

describe("completeSignIn", () => {
  it("exchanges the code with the same redirect, asks whoami with the new token, then saves the session", async () => {
    arm();
    const store = new SessionStore(localStorage);
    const { exchange, calls } = exchanger({ kind: "ok", tokens: TOKENS });
    const { shikimori, tokens } = identity(async () => FROG);

    const result = await completeSignIn(`?code=abc&state=${COUNTING_STATE}`, { storage: sessionStorage, store, exchange, shikimori, origin: ORIGIN });

    expect(result).toEqual({ kind: "done", returnTo: "/anime/1535" });
    expect(calls).toEqual([["abc", "http://localhost:5173/auth"]]);
    expect(tokens).toEqual(["acc"]);
    expect(store.get()).toEqual({ kind: "signed_in", session: { account: FROG, tokens: TOKENS } });
    expect(sessionStorage.getItem("kaeru.signin")).toBeNull();
  });

  it("uses a state once, so a replayed callback is refused", async () => {
    arm();
    const store = new SessionStore(localStorage);
    const { exchange, calls } = exchanger({ kind: "ok", tokens: TOKENS });
    const { shikimori } = identity(async () => FROG);
    const search = `?code=abc&state=${COUNTING_STATE}`;

    await completeSignIn(search, { storage: sessionStorage, store, exchange, shikimori, origin: ORIGIN });
    store.signOut();
    const replay = await completeSignIn(search, { storage: sessionStorage, store, exchange, shikimori, origin: ORIGIN });

    expect(replay).toEqual({ kind: "error", message: "Не удалось подтвердить вход. Войдите заново" });
    expect(calls).toHaveLength(1);
  });

  it("refuses a callback nobody started, a wrong state, or duplicated parameters without exchanging", async () => {
    const searches = [
      { armed: false, search: `?code=abc&state=${COUNTING_STATE}` },
      { armed: true, search: "?code=attacker&state=not-the-state" },
      { armed: true, search: "?code=attacker" },
      { armed: true, search: `?code=abc&state=${COUNTING_STATE}&state=${COUNTING_STATE}` },
      { armed: true, search: `?code=abc&code=def&state=${COUNTING_STATE}` },
    ];
    for (const { armed, search } of searches) {
      sessionStorage.clear();
      if (armed) arm();
      const store = new SessionStore(localStorage);
      const { exchange, calls } = exchanger({ kind: "ok", tokens: TOKENS });
      const { shikimori } = identity(async () => FROG);

      const result = await completeSignIn(search, { storage: sessionStorage, store, exchange, shikimori, origin: ORIGIN });

      expect(result).toEqual({ kind: "error", message: "Не удалось подтвердить вход. Войдите заново" });
      expect(calls).toEqual([]);
      expect(store.get().kind).toBe("signed_out");
    }
  });

  it("says Shikimori sent no code when there is none", async () => {
    for (const search of [`?error=access_denied&state=${COUNTING_STATE}`, `?code=&state=${COUNTING_STATE}`, ""]) {
      arm();
      const { exchange, calls } = exchanger({ kind: "ok", tokens: TOKENS });
      const result = await completeSignIn(search, {
        storage: sessionStorage,
        store: new SessionStore(localStorage),
        exchange,
        shikimori: identity(async () => FROG).shikimori,
        origin: ORIGIN,
      });
      expect(result).toEqual({ kind: "error", message: "Shikimori не вернул код. Попробуйте войти ещё раз" });
      expect(calls).toEqual([]);
    }
  });

  it("never switches accounts under a signed-in viewer", async () => {
    arm();
    const store = new SessionStore(localStorage);
    store.setSession({ account: { id: 7, nickname: "owner", avatar: null }, tokens: { ...TOKENS, accessToken: "owner" } });
    const before = store.get();
    const { exchange, calls } = exchanger({ kind: "ok", tokens: TOKENS });

    const result = await completeSignIn(`?code=abc&state=${COUNTING_STATE}`, {
      storage: sessionStorage,
      store,
      exchange,
      shikimori: identity(async () => FROG).shikimori,
      origin: ORIGIN,
    });

    expect(result).toEqual({ kind: "error", message: "Вход уже выполнен. Запрос авторизации отклонён" });
    expect(calls).toEqual([]);
    expect(store.get()).toBe(before);
  });

  it("closes access for an account that is not on the list, keeping no tokens", async () => {
    arm();
    const store = new SessionStore(localStorage);
    const { exchange } = exchanger({ kind: "closed", nickname: "stranger" });
    const { shikimori, tokens } = identity(async () => FROG);

    const result = await completeSignIn(`?code=abc&state=${COUNTING_STATE}`, { storage: sessionStorage, store, exchange, shikimori, origin: ORIGIN });

    expect(result).toEqual({ kind: "closed", nickname: "stranger" });
    expect(store.get()).toEqual({ kind: "closed", nickname: "stranger" });
    expect(tokens).toEqual([]);
  });

  it("reports a failed exchange in words and saves nothing", async () => {
    arm();
    const store = new SessionStore(localStorage);
    const { exchange } = exchanger({ kind: "unavailable" });

    const result = await completeSignIn(`?code=abc&state=${COUNTING_STATE}`, {
      storage: sessionStorage,
      store,
      exchange,
      shikimori: identity(async () => FROG).shikimori,
      origin: ORIGIN,
    });

    expect(result).toEqual({ kind: "error", message: "Shikimori недоступен, попробуйте позже" });
    expect(store.get().kind).toBe("signed_out");
  });

  it("fails the sign-in when whoami fails or knows nobody", async () => {
    const answers: Array<[() => Promise<Account | null>, string]> = [
      [() => Promise.reject(new NetworkError()), "Нет соединения. Проверьте интернет"],
      [() => Promise.reject(new ApiError(503)), "Shikimori недоступен, попробуйте позже"],
      [() => Promise.resolve(null), "Что-то пошло не так. Повторите попытку"],
    ];
    for (const [answer, message] of answers) {
      arm();
      const store = new SessionStore(localStorage);
      const { exchange } = exchanger({ kind: "ok", tokens: TOKENS });

      const result = await completeSignIn(`?code=abc&state=${COUNTING_STATE}`, {
        storage: sessionStorage,
        store,
        exchange,
        shikimori: identity(answer).shikimori,
        origin: ORIGIN,
      });

      expect(result).toEqual({ kind: "error", message });
      expect(store.get().kind).toBe("signed_out");
    }
  });
});

describe("signInErrorMessage", () => {
  it("names what went wrong in the apps' words", () => {
    expect(signInErrorMessage({ kind: "unavailable" })).toBe("Shikimori недоступен, попробуйте позже");
    expect(signInErrorMessage({ kind: "throttled" })).toBe("Слишком много запросов, попробуйте позже");
    expect(signInErrorMessage({ kind: "not_configured" })).toBe("Вход временно недоступен, попробуйте позже");
    expect(signInErrorMessage({ kind: "network" })).toBe("Нет соединения. Проверьте интернет");
    expect(signInErrorMessage({ kind: "invalid_grant" })).toBe("Что-то пошло не так. Повторите попытку");
    expect(signInErrorMessage({ kind: "rejected" })).toBe("Что-то пошло не так. Повторите попытку");
    expect(signInErrorMessage({ kind: "sign_in" })).toBe("Что-то пошло не так. Повторите попытку");
  });
});
