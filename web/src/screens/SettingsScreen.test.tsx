// Copy and threshold labels from android/src/main/java/app/kaeru/ui/mobile/settings/SettingsScreen.kt
// and android/src/test/java/app/kaeru/ui/common/settings/SettingsOptionsTest.kt.
import { render, screen, within } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { MemoryRouter, Route, Routes } from "react-router-dom";
import { describe, expect, it } from "vitest";
import type { Account, Shikimori } from "../api/shikimori";
import { ServicesContext } from "../app/services";
import type { Tokens } from "../auth/relay";
import { sessionStore } from "../auth/session";
import type { authorized } from "../auth/session";
import { Library } from "../library/library";
import { setWatchedThreshold, watchedThreshold } from "../library/prefs";
import { ProgressStore } from "../library/progress";
import { SettingsScreen } from "./SettingsScreen";

const ACCOUNT: Account = { id: 1, nickname: "Лягушка", avatar: "https://shikimori.io/system/users/x160/1.png" };
const TOKENS: Tokens = { accessToken: "access", refreshToken: "refresh", expiresIn: 86_400, createdAt: 1_790_000_000 };
const DIALOG_TEXT = "Список и прогресс останутся на Shikimori, локальный кэш будет очищен";

function memoryStorage(): Storage {
  const data = new Map<string, string>();
  return {
    get length() {
      return data.size;
    },
    clear: () => data.clear(),
    getItem: (name: string) => data.get(name) ?? null,
    key: (index: number) => [...data.keys()][index] ?? null,
    removeItem: (name: string) => {
      data.delete(name);
    },
    setItem: (name: string, value: string) => {
      data.set(name, value);
    },
  };
}

function fakeShikimori(): Shikimori {
  const unused = () => Promise.reject(new Error("not used in this test"));
  return {
    whoami: unused,
    details: unused,
    search: unused,
    popularNow: unused,
    popularInSeason: unused,
    byIds: unused,
    userRates: unused,
    createRate: unused,
    updateRate: unused,
  };
}

const signedIn: typeof authorized = (call) => call("token");

function setup() {
  const shikimori = fakeShikimori();
  const storage = memoryStorage();
  const progress = new ProgressStore(storage);
  const library = new Library({ shikimori, authorized: signedIn, accountId: () => ACCOUNT.id, progress });
  // One title from the list and one that was only ever opened from search.
  progress.put({ animeId: 5, episode: 3, positionMs: 600_000, durationMs: 1_440_000, updatedAt: 1 });
  progress.put({ animeId: 99, episode: 1, positionMs: 90_000, durationMs: 1_440_000, updatedAt: 2 });
  // setup.ts clears localStorage after every test, so the next test starts signed out again.
  sessionStore.setSession({ account: ACCOUNT, tokens: TOKENS });
  const view = render(
    <ServicesContext.Provider value={{ shikimori, library, progress }}>
      <MemoryRouter initialEntries={["/settings"]}>
        <Routes>
          <Route path="/settings" element={<SettingsScreen />} />
          <Route path="/" element={<p>Главная</p>} />
        </Routes>
      </MemoryRouter>
    </ServicesContext.Provider>,
  );
  return { progress, storage, user: userEvent.setup(), container: view.container };
}

function thresholds(): HTMLElement {
  return screen.getByRole("radiogroup", { name: "Порог просмотра" });
}

describe("SettingsScreen", () => {
  it("shows who is signed in", () => {
    const { container } = setup();
    expect(screen.getByRole("heading", { name: "Аккаунт" })).toBeInTheDocument();
    expect(screen.getByText("Лягушка")).toBeInTheDocument();
    expect(screen.getByText("Shikimori")).toBeInTheDocument();
    expect(container.querySelector("img")?.getAttribute("src")).toBe(ACCOUNT.avatar);
  });

  it("offers the four thresholds as percentages and keeps the choice", async () => {
    const { user } = setup();
    expect(within(thresholds()).getAllByRole("radio").map((radio) => radio.textContent)).toEqual([
      "80\u00A0%",
      "85\u00A0%",
      "90\u00A0%",
      "95\u00A0%",
    ]);
    expect(within(thresholds()).getByRole("radio", { name: /^90\s%$/ })).toBeChecked();
    await user.click(within(thresholds()).getByRole("radio", { name: /^95\s%$/ }));
    expect(within(thresholds()).getByRole("radio", { name: /^95\s%$/ })).toBeChecked();
    expect(within(thresholds()).getByRole("radio", { name: /^90\s%$/ })).not.toBeChecked();
    expect(watchedThreshold()).toBe(0.95);
  });

  it("puts a stored threshold the row does not offer into the row, in its place", () => {
    setWatchedThreshold(0.7);
    setup();
    expect(within(thresholds()).getAllByRole("radio").map((radio) => radio.textContent)).toEqual([
      "70\u00A0%",
      "80\u00A0%",
      "85\u00A0%",
      "90\u00A0%",
      "95\u00A0%",
    ]);
    expect(within(thresholds()).getByRole("radio", { name: /^70\s%$/ })).toBeChecked();
  });

  it("asks before signing out, and «Отмена» keeps everything", async () => {
    const { user, progress } = setup();
    await user.click(screen.getByRole("button", { name: "Выйти из аккаунта" }));
    const dialog = screen.getByRole("dialog", { name: "Выйти из аккаунта?" });
    expect(dialog).toHaveAccessibleDescription(DIALOG_TEXT);
    expect(within(dialog).getByRole("button", { name: "Выйти" })).toHaveClass("btn--destructive");
    await user.click(within(dialog).getByRole("button", { name: "Отмена" }));
    expect(screen.queryByRole("dialog")).not.toBeInTheDocument();
    expect(sessionStore.get().kind).toBe("signed_in");
    expect(progress.of(5)).toHaveLength(1);
    expect(progress.of(99)).toHaveLength(1);
  });

  it("«Выйти» ends the session, forgets every position in this browser and goes home", async () => {
    const { user, progress, storage } = setup();
    await user.click(screen.getByRole("button", { name: "Выйти из аккаунта" }));
    const dialog = screen.getByRole("dialog", { name: "Выйти из аккаунта?" });
    await user.click(within(dialog).getByRole("button", { name: "Выйти" }));
    expect(sessionStore.get().kind).toBe("signed_out");
    expect(progress.of(5)).toEqual([]);
    // Not only the titles in the loaded list: a title played from search goes too.
    expect(progress.of(99)).toEqual([]);
    // Gone from storage as well, so a reload does not bring it back.
    expect(new ProgressStore(storage).of(99)).toEqual([]);
    expect(screen.getByText("Главная")).toBeInTheDocument();
  });
});
