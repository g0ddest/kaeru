import { render, screen, waitFor } from "@testing-library/react";
import { afterEach, beforeEach, describe, expect, it } from "vitest";
import { sessionStore, type Session } from "../auth/session";
import { App } from "./App";
import { createServices } from "./services";

const SESSION: Session = {
  account: { id: 42, nickname: "Vitaliy", avatar: null },
  tokens: { accessToken: "tok", refreshToken: "ref", expiresIn: 86400, createdAt: Math.floor(Date.now() / 1000) },
};

// Every Shikimori call answers with an empty list; nothing leaves the test.
const emptyShikimori: typeof fetch = async () =>
  new Response("[]", { status: 200, headers: { "Content-Type": "application/json" } });

function openAt(path: string) {
  window.history.replaceState(null, "", path);
  const services = createServices({ fetch: emptyShikimori });
  render(<App services={services} />);
  return services;
}

beforeEach(() => {
  sessionStore.signOut();
});

afterEach(() => {
  window.history.replaceState(null, "", "/");
});

describe("App", () => {
  it("shows nothing but the sign-in screen when signed out", () => {
    openAt("/search");
    expect(screen.getByRole("button", { name: "Войти через Shikimori" })).toBeInTheDocument();
    expect(screen.queryByRole("navigation")).toBeNull();
  });

  it.each(["/auth", "/auth/"])("serves %s outside the gate", async (path) => {
    openAt(path);
    expect(screen.getByText("Проверяем код…")).toBeInTheDocument();
    expect(await screen.findByRole("button", { name: "Войти ещё раз" })).toBeInTheDocument();
    expect(screen.queryByRole("button", { name: "Войти через Shikimori" })).toBeNull();
  });

  it("shows «Доступ закрыт» for an account off the list, on any page", () => {
    sessionStore.setClosed("friend");
    openAt("/anime/1");
    expect(screen.getByRole("heading", { name: "Доступ закрыт" })).toBeInTheDocument();
    expect(screen.getByText("friend")).toBeInTheDocument();
  });

  it("lets a signed-in viewer in and starts loading the list", async () => {
    sessionStore.setSession(SESSION);
    const services = openAt("/watch/7/2");
    expect(screen.getByRole("heading", { name: "Плеер появится в следующем обновлении" })).toBeInTheDocument();
    expect(screen.getByRole("link", { name: "Назад к тайтлу" })).toHaveAttribute("href", "/anime/7");
    await waitFor(() => expect(services.library.state().kind).toBe("ready"), { timeout: 3000 });
  });
});
