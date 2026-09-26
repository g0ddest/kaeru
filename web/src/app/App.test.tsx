import { render, screen, waitFor } from "@testing-library/react";
import { afterEach, beforeEach, describe, expect, it } from "vitest";
import { sessionStore, type Session } from "../auth/session";
import { ProgressStore } from "../library/progress";
import { memoryStorage } from "../test/fakes";
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
    await waitFor(() => expect(services.library.state().kind).toBe("ready"), { timeout: 3000 });
  });

  it("reads the synced positions once signed in, and stops following them when signed out", async () => {
    const synced: string[] = [];
    const fetch: typeof globalThis.fetch = async (input, init) => {
      const url = String(input);
      if (url.endsWith("/sync")) {
        synced.push(init?.method ?? "GET");
        return new Response(JSON.stringify({ titles: {} }), { status: 200, headers: { "Content-Type": "application/json" } });
      }
      return emptyShikimori(input, init);
    };
    sessionStore.setSession(SESSION);
    window.history.replaceState(null, "", "/");
    const services = createServices({ fetch, progress: new ProgressStore(memoryStorage()) });
    const view = render(<App services={services} />);
    await waitFor(() => expect(synced).toEqual(["GET"]));

    view.unmount();
    services.progress.put({ animeId: 7, episode: 1, positionMs: 1_000, durationMs: 2_000, updatedAt: Date.now() });
    await new Promise((resolve) => setTimeout(resolve, 20));
    expect(synced).toEqual(["GET"]);
  });

  it("opens the player full window at /watch/:id/:episode", async () => {
    sessionStore.setSession(SESSION);
    openAt("/watch/7/3");

    expect(document.querySelector("video")).not.toBeNull();
    expect(screen.getByRole("button", { name: "Назад" })).toBeInTheDocument();
    // No shell around it: no sections sidebar, no tab bar.
    expect(screen.queryByRole("navigation")).toBeNull();
    expect(screen.queryByRole("heading", { name: "Плеер появится в следующем обновлении" })).toBeNull();
    // Every Shikimori answer here is an empty list, so the title cannot load: the player says so.
    expect(await screen.findByRole("alert")).toHaveTextContent("Не удалось загрузить аниме. Проверьте соединение и повторите");
  });
});
