// Behaviour follows android/src/main/java/app/kaeru/ui/mobile/library/LibraryScreen.kt and
// android/src/test/java/app/kaeru/ui/common/library/LibraryTabsTest.kt. The screen runs over the
// real Library and ProgressStore with a fake Shikimori, provided the way App.tsx provides them.
import { act, render, screen, within } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { MemoryRouter, Route, Routes } from "react-router-dom";
import { describe, expect, it, vi } from "vitest";
import { NetworkError } from "../api/http";
import type { Shikimori } from "../api/shikimori";
import { ServicesContext } from "../app/services";
import type { authorized } from "../auth/session";
import type { Anime, ListStatus, UserRate } from "../domain/models";
import { Library } from "../library/library";
import { ProgressStore } from "../library/progress";
import { LibraryScreen } from "./LibraryScreen";

function anime(id: number, title: string): Anime {
  return {
    id,
    title,
    originalTitle: title,
    posterUrl: null,
    backdropUrl: null,
    status: "released",
    episodes: 12,
    episodesAired: 12,
    year: 2026,
    score: null,
    kind: "tv",
    studios: [],
    description: null,
    nextEpisodeAt: null,
  };
}

function rate(animeId: number, status: ListStatus, episodes: number, updated: string): UserRate {
  return { id: 100 + animeId, animeId, status, episodes, updatedAt: Date.parse(updated) };
}

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

function fakeShikimori(over: Partial<Shikimori>): Shikimori {
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
    ...over,
  };
}

const signedIn: typeof authorized = (call) => call("token");

const TITLES = [anime(1, "Ёлка"), anime(2, "Ели"), anime(3, "Аист"), anime(4, "Берсерк"), anime(5, "Ящер")];
const RATES = [
  rate(1, "watching", 0, "2026-09-01T00:00:00Z"),
  rate(2, "watching", 5, "2026-09-10T00:00:00Z"),
  rate(3, "watching", 0, "2026-09-05T00:00:00Z"),
  rate(4, "planned", 0, "2026-09-02T00:00:00Z"),
  rate(5, "dropped", 3, "2026-09-03T00:00:00Z"),
];
const byIds: Shikimori["byIds"] = async (ids) => TITLES.filter((title) => ids.includes(title.id));

function renderAt(path: string, shikimori: Shikimori) {
  const progress = new ProgressStore(memoryStorage());
  const library = new Library({ shikimori, authorized: signedIn, accountId: () => 1, progress });
  render(
    <ServicesContext.Provider value={{ shikimori, library, progress }}>
      <MemoryRouter initialEntries={[path]}>
        <Routes>
          <Route path="/list" element={<LibraryScreen />} />
          <Route path="/search" element={<p>Экран поиска</p>} />
        </Routes>
      </MemoryRouter>
    </ServicesContext.Provider>,
  );
  return { progress };
}

// A card link reads its whole text (badge, title, caption), so a card is found by its title.
function card(title: string): HTMLElement {
  return screen.getByRole("link", { name: new RegExp(title) });
}

function panelTitles(): (string | null)[] {
  return within(screen.getByRole("tabpanel"))
    .getAllByRole("link")
    .map((link) => link.querySelector(".poster-card__title")?.textContent ?? null);
}

describe("LibraryScreen", () => {
  it("shows every status as a tab with its count, in Android's order, «Смотрю» open", async () => {
    renderAt("/list", fakeShikimori({ userRates: async () => RATES, byIds }));
    await screen.findByRole("tab", { name: "Смотрю 3" });
    expect(screen.getAllByRole("tab").map((tab) => tab.textContent)).toEqual([
      "Смотрю 3",
      "В планах 1",
      "Завершено 0",
      "Пересматриваю 0",
      "Отложено 0",
      "Брошено 1",
    ]);
    expect(screen.getByRole("tab", { name: "Смотрю 3" })).toHaveAttribute("aria-selected", "true");
    expect(screen.getByRole("tablist", { name: "Мой список" })).toBeInTheDocument();
  });

  it("lists the open tab by last update, and «Название» sorts ё right after е", async () => {
    const user = userEvent.setup();
    renderAt("/list", fakeShikimori({ userRates: async () => RATES, byIds }));
    await screen.findByRole("tab", { name: "Смотрю 3" });
    expect(panelTitles()).toEqual(["Ели", "Аист", "Ёлка"]);
    expect(screen.getByRole("button", { name: "Обновление" })).toHaveAttribute("aria-pressed", "true");
    await user.click(screen.getByRole("button", { name: "Название" }));
    expect(screen.getByRole("button", { name: "Название" })).toHaveAttribute("aria-pressed", "true");
    expect(panelTitles()).toEqual(["Аист", "Ели", "Ёлка"]);
  });

  it("says how far through a title is, or how long it runs when untouched", async () => {
    renderAt("/list", fakeShikimori({ userRates: async () => RATES, byIds }));
    await screen.findByRole("tab", { name: "Смотрю 3" });
    const started = card("Ели");
    expect(within(started).getByText("5 из 12")).toBeInTheDocument();
    expect(started).toHaveAttribute("href", "/anime/2");
    expect(within(card("Аист")).getByText("12 серий")).toBeInTheDocument();
  });

  it("fills the strip of the episode being continued when this browser saves a position", async () => {
    const { progress } = renderAt("/list", fakeShikimori({ userRates: async () => RATES, byIds }));
    await screen.findByRole("tab", { name: "Смотрю 3" });
    expect(card("Ели").querySelector(".progress-strip")).toBeNull();
    act(() => {
      progress.put({ animeId: 2, episode: 6, positionMs: 600_000, durationMs: 1_440_000, updatedAt: 1 });
    });
    expect(card("Ели").querySelector<HTMLElement>(".progress-strip__fill")?.style.width).toBe("41.7%");
  });

  it("opens a tab from the address, by click and by arrow keys", async () => {
    const user = userEvent.setup();
    renderAt("/list?tab=dropped", fakeShikimori({ userRates: async () => RATES, byIds }));
    expect(await screen.findByRole("tab", { name: "Брошено 1" })).toHaveAttribute("aria-selected", "true");
    expect(panelTitles()).toEqual(["Ящер"]);
    await user.click(screen.getByRole("tab", { name: "В планах 1" }));
    expect(panelTitles()).toEqual(["Берсерк"]);
    expect(screen.getByRole("tabpanel")).toHaveAccessibleName("В планах 1");
    await user.keyboard("{ArrowLeft}");
    const watching = screen.getByRole("tab", { name: "Смотрю 3" });
    expect(watching).toHaveAttribute("aria-selected", "true");
    expect(watching).toHaveFocus();
    await user.keyboard("{End}");
    expect(screen.getByRole("tab", { name: "Брошено 1" })).toHaveFocus();
  });

  it("explains an empty tab without offering search where the viewer does not fill it", async () => {
    renderAt("/list?tab=completed", fakeShikimori({ userRates: async () => RATES, byIds }));
    expect(await screen.findByRole("heading", { name: "Завершённых тайтлов пока нет" })).toBeInTheDocument();
    expect(screen.getByText("Здесь соберётся всё, что вы досмотрели до конца.")).toBeInTheDocument();
    expect(screen.queryByRole("link", { name: "Найти аниме" })).not.toBeInTheDocument();
  });

  it("leads an empty «Смотрю» to search", async () => {
    const user = userEvent.setup();
    renderAt("/list", fakeShikimori({ userRates: async () => [], byIds: async () => [] }));
    expect(await screen.findByRole("heading", { name: "Вы ничего не смотрите" })).toBeInTheDocument();
    await user.click(screen.getByRole("link", { name: "Найти аниме" }));
    expect(screen.getByText("Экран поиска")).toBeInTheDocument();
  });

  it("says why the list did not load and loads it again on «Повторить»", async () => {
    const user = userEvent.setup();
    const userRates = vi
      .fn<Shikimori["userRates"]>()
      .mockRejectedValueOnce(new NetworkError("offline"))
      .mockResolvedValue(RATES);
    renderAt("/list", fakeShikimori({ userRates, byIds }));
    expect(await screen.findByText("Нет соединения. Проверьте интернет")).toBeInTheDocument();
    await user.click(screen.getByRole("button", { name: "Повторить" }));
    expect(await screen.findByRole("tab", { name: "Смотрю 3" })).toBeInTheDocument();
  });
});
