import { act, render, screen } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { StrictMode } from "react";
import { MemoryRouter, Route, Routes, useLocation } from "react-router-dom";
import { beforeEach, describe, expect, it, vi } from "vitest";
import { Gate } from "../app/Gate";
import { sessionStore, type Session } from "../auth/session";
import type { SignInResult } from "../auth/signin";
import { AuthCallbackScreen } from "./AuthCallbackScreen";

const SESSION: Session = {
  account: { id: 42, nickname: "Vitaliy", avatar: null },
  tokens: { accessToken: "tok", refreshToken: "ref", expiresIn: 86400, createdAt: Math.floor(Date.now() / 1000) },
};

function deferred() {
  let resolve: (result: SignInResult) => void = () => undefined;
  const promise = new Promise<SignInResult>((done) => {
    resolve = done;
  });
  return { promise, resolve };
}

function Address() {
  const location = useLocation();
  return <p data-testid="address">{location.pathname + location.search}</p>;
}

function renderCallback(
  complete: (search: string) => Promise<SignInResult>,
  navigateTo: (url: string) => void = () => undefined,
) {
  return render(
    <StrictMode>
      <MemoryRouter initialEntries={["/auth?code=abc&state=xyz"]}>
        <Routes>
          <Route path="/auth" element={<AuthCallbackScreen complete={complete} navigateTo={navigateTo} />} />
          <Route path="/anime/:id" element={<p>Страница тайтла</p>} />
          <Route
            path="/"
            element={
              <Gate>
                <p>Главная страница</p>
              </Gate>
            }
          />
        </Routes>
        <Address />
      </MemoryRouter>
    </StrictMode>,
  );
}

beforeEach(() => {
  sessionStore.signOut();
});

describe("AuthCallbackScreen", () => {
  it("checks the code once, even under StrictMode, and drops it from the address", () => {
    const pending = deferred();
    const complete = vi.fn((_search: string) => pending.promise);
    renderCallback(complete);
    expect(screen.getByText("Проверяем код…")).toBeInTheDocument();
    expect(complete).toHaveBeenCalledTimes(1);
    expect(complete).toHaveBeenCalledWith("?code=abc&state=xyz");
    expect(screen.getByTestId("address")).toHaveTextContent(/^\/auth$/);
  });

  it("returns to the page that started the sign-in", async () => {
    const pending = deferred();
    renderCallback(() => pending.promise);
    await act(async () => {
      pending.resolve({ kind: "done", returnTo: "/anime/5" });
    });
    expect(screen.getByText("Страница тайтла")).toBeInTheDocument();
    expect(screen.getByTestId("address")).toHaveTextContent(/^\/anime\/5$/);
  });

  it("hands an account off the list to the gate's closed screen", async () => {
    const pending = deferred();
    renderCallback(() => pending.promise);
    await act(async () => {
      pending.resolve({ kind: "closed", nickname: "friend" });
    });
    expect(screen.getByRole("heading", { name: "Доступ закрыт" })).toBeInTheDocument();
    expect(screen.getByText("friend")).toBeInTheDocument();
    expect(sessionStore.get()).toEqual({ kind: "closed", nickname: "friend" });
  });

  it("explains a failure and starts a fresh sign-in", async () => {
    const pending = deferred();
    const navigateTo = vi.fn<(url: string) => void>();
    renderCallback(() => pending.promise, navigateTo);
    await act(async () => {
      pending.resolve({ kind: "error", message: "Shikimori не вернул код. Попробуйте войти ещё раз" });
    });
    expect(screen.getByRole("alert")).toHaveTextContent("Shikimori не вернул код. Попробуйте войти ещё раз");
    await userEvent.setup().click(screen.getByRole("button", { name: "Войти ещё раз" }));
    expect(navigateTo).toHaveBeenCalledTimes(1);
    const url = new URL(navigateTo.mock.calls[0][0]);
    expect(url.origin + url.pathname).toBe("https://shikimori.io/oauth/authorize");
  });

  it("sends an already signed-in viewer home instead of signing in again", async () => {
    sessionStore.setSession(SESSION);
    const pending = deferred();
    const navigateTo = vi.fn<(url: string) => void>();
    renderCallback(() => pending.promise, navigateTo);
    await act(async () => {
      pending.resolve({ kind: "error", message: "Вход уже выполнен. Запрос авторизации отклонён" });
    });
    await userEvent.setup().click(screen.getByRole("button", { name: "Войти ещё раз" }));
    expect(navigateTo).not.toHaveBeenCalled();
    expect(screen.getByText("Главная страница")).toBeInTheDocument();
  });

  it("treats a thrown failure as an error, not a hang", async () => {
    renderCallback(() => Promise.reject(new Error("boom")));
    expect(await screen.findByRole("alert")).toHaveTextContent("Что-то пошло не так. Повторите попытку");
  });
});
