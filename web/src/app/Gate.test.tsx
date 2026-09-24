import { act, render, screen } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { MemoryRouter } from "react-router-dom";
import { beforeEach, describe, expect, it } from "vitest";
import { sessionStore, type Session } from "../auth/session";
import { Gate } from "./Gate";

const SESSION: Session = {
  account: { id: 42, nickname: "Vitaliy", avatar: null },
  tokens: { accessToken: "tok", refreshToken: "ref", expiresIn: 86400, createdAt: Math.floor(Date.now() / 1000) },
};

function renderGate() {
  return render(
    <MemoryRouter>
      <Gate>
        <p>Содержимое приложения</p>
      </Gate>
    </MemoryRouter>,
  );
}

beforeEach(() => {
  sessionStore.signOut();
});

describe("Gate", () => {
  it("shows nothing but the sign-in screen when signed out", () => {
    renderGate();
    expect(screen.getByRole("heading", { name: "Kaeru" })).toBeInTheDocument();
    expect(
      screen.getByText("Войдите через Shikimori, чтобы синхронизировать список и просмотренные серии."),
    ).toBeInTheDocument();
    expect(screen.getByRole("button", { name: "Войти через Shikimori" })).toBeEnabled();
    expect(screen.queryByRole("alert")).toBeNull();
    expect(screen.queryByText("Содержимое приложения")).toBeNull();
  });

  it("says why the session ended", () => {
    sessionStore.signOut("Сессия истекла, войдите снова");
    renderGate();
    expect(screen.getByRole("alert")).toHaveTextContent("Сессия истекла, войдите снова");
  });

  it("shows «Доступ закрыт» with the nickname for an account off the list", () => {
    sessionStore.setClosed("friend");
    renderGate();
    expect(screen.getByRole("heading", { name: "Доступ закрыт" })).toBeInTheDocument();
    expect(screen.getByText("friend")).toBeInTheDocument();
    expect(screen.getByText("Kaeru для браузера открыт по приглашению")).toBeInTheDocument();
    expect(screen.getByText("Попросите владельца добавить ваш аккаунт Shikimori в список.")).toBeInTheDocument();
    expect(screen.queryByText("Содержимое приложения")).toBeNull();
  });

  it("signs a closed account out, back to the sign-in screen", async () => {
    sessionStore.setClosed("friend");
    renderGate();
    await userEvent.setup().click(screen.getByRole("button", { name: "Выйти" }));
    expect(sessionStore.get().kind).toBe("signed_out");
    expect(screen.getByRole("button", { name: "Войти через Shikimori" })).toBeInTheDocument();
  });

  it("lets a signed-in account through", () => {
    sessionStore.setSession(SESSION);
    renderGate();
    expect(screen.getByText("Содержимое приложения")).toBeInTheDocument();
  });

  it("follows the session live, e.g. a sign-out in another tab", () => {
    sessionStore.setSession(SESSION);
    renderGate();
    act(() => {
      sessionStore.signOut("Сессия истекла, войдите снова");
    });
    expect(screen.queryByText("Содержимое приложения")).toBeNull();
    expect(screen.getByRole("alert")).toHaveTextContent("Сессия истекла, войдите снова");
  });
});
