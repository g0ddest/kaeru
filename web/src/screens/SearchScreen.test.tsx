// Rules: debounce 350 ms and 2 characters (ios/Features/SearchView.swift); screen states and the
// card action (android/src/test/java/app/kaeru/ui/common/search/SearchContentTest.kt).
// Fake timers drive the debounce. userEvent advances them itself, and Testing Library's async
// wrapper can drain them because src/test/setup.ts defines the `jest` shim it looks for.
import { act, cleanup, render, screen, within } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { MemoryRouter, Route, Routes, useNavigate } from "react-router-dom";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { NetworkError } from "../api/http";
import type { Shikimori } from "../api/shikimori";
import { ServicesContext } from "../app/services";
import type { authorized } from "../auth/session";
import type { Anime, ListStatus, UserRate } from "../domain/models";
import { Library } from "../library/library";
import { ProgressStore } from "../library/progress";
import { ToastProvider } from "../ui/Toast";
import { CatalogueCache } from "./home";
import { SearchScreen } from "./SearchScreen";

const OFFLINE = "Нет соединения. Проверьте интернет";
const DEBOUNCE = 350;

function anime(id: number, title: string, year: number): Anime {
  return {
    id,
    title,
    originalTitle: title,
    posterUrl: null,
    backdropUrl: null,
    status: "released",
    episodes: 12,
    episodesAired: 12,
    year,
    score: null,
    kind: "tv",
    studios: [],
    description: null,
    nextEpisodeAt: null,
  };
}

function rate(animeId: number, status: ListStatus, episodes: number): UserRate {
  return { id: 100 + animeId, animeId, status, episodes, updatedAt: Date.parse("2026-09-10T00:00:00Z") };
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
const FRIEREN = anime(7, "Фрирен", 2023);
const DANDADAN = anime(8, "Дандадан", 2024);
const created: Shikimori["createRate"] = async (_token, _userId, animeId, fields) => ({
  id: 900,
  animeId,
  status: fields.status,
  episodes: 0,
  updatedAt: Date.now(),
});

// The title page, reduced to the way back.
function TitleStub() {
  const navigate = useNavigate();
  return (
    <button type="button" onClick={() => navigate(-1)}>
      Назад
    </button>
  );
}

function setup(
  over: Partial<Shikimori>,
  options: { path?: string; catalogue?: CatalogueCache; searches?: CatalogueCache } = {},
) {
  const shikimori = fakeShikimori({
    popularNow: async () => [FRIEREN],
    userRates: async () => [],
    byIds: async () => [],
    ...over,
  });
  const progress = new ProgressStore(memoryStorage());
  const library = new Library({ shikimori, authorized: signedIn, accountId: () => 1, progress });
  // A fresh cache per test: the module-wide one would carry titles from one test to the next.
  const catalogue = options.catalogue ?? new CatalogueCache();
  const searches = options.searches ?? new CatalogueCache();
  const user = userEvent.setup({
    advanceTimers: (ms) => {
      vi.advanceTimersByTime(ms);
    },
  });
  render(
    <ServicesContext.Provider value={{ shikimori, library, progress }}>
      <ToastProvider>
        <MemoryRouter initialEntries={[options.path ?? "/search"]}>
          <Routes>
            <Route path="/search" element={<SearchScreen catalogue={catalogue} searches={searches} />} />
            <Route path="/anime/:id" element={<TitleStub />} />
          </Routes>
        </MemoryRouter>
      </ToastProvider>
    </ServicesContext.Provider>,
  );
  return { user, library };
}

async function elapse(ms: number) {
  await act(async () => {
    await vi.advanceTimersByTimeAsync(ms);
  });
}

function field(): HTMLElement {
  return screen.getByRole("searchbox", { name: "Название аниме" });
}

// A card link reads its whole text (year badge and title), so a card is found by its title.
function cardLink(title: string): HTMLElement {
  return screen.getByRole("link", { name: new RegExp(title) });
}

function cardOf(title: string): HTMLElement {
  const item = cardLink(title).closest("li");
  if (item === null) throw new Error(`no card for ${title}`);
  return item;
}

beforeEach(() => {
  vi.useFakeTimers();
});

afterEach(() => {
  // Unmount while the fake clock still owns the screen's timers, then hand the real clock back.
  cleanup();
  vi.useRealTimers();
});

describe("SearchScreen", () => {
  it("before a search shows what is popular now, each with a way into «В планах»", async () => {
    setup({
      popularNow: async () => [FRIEREN, DANDADAN],
      userRates: async () => [rate(8, "watching", 3)],
      byIds: async () => [DANDADAN],
    });
    await elapse(0);
    const section = screen.getByRole("region", { name: "Популярно сейчас" });
    const frieren = within(section).getByRole("link", { name: /Фрирен/ });
    expect(within(frieren).getByText("2023")).toBeInTheDocument();
    expect(frieren).toHaveAttribute("href", "/anime/7");
    expect(within(section).getByRole("button", { name: "Добавить Фрирен в планы" })).toBeEnabled();
    expect(within(cardOf("Дандадан")).getByRole("button", { name: "В списке" })).toBeDisabled();
  });

  it("takes «Популярно сейчас» from the catalogue cache Home fills", async () => {
    const catalogue = new CatalogueCache();
    await catalogue.read("now", async () => [DANDADAN]);
    const popularNow = vi.fn<Shikimori["popularNow"]>().mockResolvedValue([FRIEREN]);
    setup({ popularNow }, { catalogue });
    await elapse(0);
    expect(popularNow).not.toHaveBeenCalled();
    const section = screen.getByRole("region", { name: "Популярно сейчас" });
    expect(within(section).getByRole("link", { name: /Дандадан/ })).toBeInTheDocument();
  });

  it("falls back to the invitation when nothing popular came back", async () => {
    setup({ popularNow: () => Promise.reject(new NetworkError("offline")) });
    await elapse(0);
    expect(screen.getByText("Что посмотреть сегодня?")).toBeInTheDocument();
    expect(
      screen.getByText("Введите название — Kaeru поищет его на Shikimori и положит найденное в ваш список."),
    ).toBeInTheDocument();
  });

  it("never searches one letter and waits 350 ms after the last keystroke", async () => {
    const search = vi.fn<Shikimori["search"]>().mockResolvedValue([DANDADAN]);
    const { user } = setup({ search });
    await elapse(0);
    await user.type(field(), "ф");
    await elapse(1000);
    expect(search).not.toHaveBeenCalled();
    await user.keyboard("р");
    await elapse(349);
    expect(search).not.toHaveBeenCalled();
    await elapse(1);
    expect(search).toHaveBeenCalledTimes(1);
    expect(search).toHaveBeenCalledWith("фр");
    expect(within(cardLink("Дандадан")).getByText("2024")).toBeInTheDocument();
  });

  it("sends only the last query when typing fast", async () => {
    const search = vi.fn<Shikimori["search"]>().mockResolvedValue([DANDADAN]);
    const { user } = setup({ search });
    await elapse(0);
    await user.type(field(), "дандадан");
    await elapse(350);
    expect(search).toHaveBeenCalledTimes(1);
    expect(search).toHaveBeenCalledWith("дандадан");
  });

  it("searches the trimmed query at once on Enter and not again after the pause", async () => {
    const search = vi.fn<Shikimori["search"]>().mockResolvedValue([DANDADAN]);
    const { user } = setup({ search });
    await elapse(0);
    await user.type(field(), "  дандадан {Enter}");
    await elapse(0);
    expect(search).toHaveBeenCalledWith("дандадан");
    await elapse(1000);
    expect(search).toHaveBeenCalledTimes(1);
    expect(field()).not.toHaveFocus();
  });

  it("tells a search that found nothing from one that failed, and retries the failed query", async () => {
    const search = vi
      .fn<Shikimori["search"]>()
      .mockResolvedValueOnce([])
      .mockRejectedValueOnce(new NetworkError("offline"))
      .mockResolvedValue([DANDADAN]);
    const { user } = setup({ search });
    await elapse(0);
    await user.type(field(), "ыыы");
    await elapse(350);
    expect(screen.getByText("Ничего не найдено")).toBeInTheDocument();
    expect(screen.getByText("Попробуйте оригинальное название или короче.")).toBeInTheDocument();
    await user.clear(field());
    await user.type(field(), "дандадан");
    await elapse(350);
    expect(screen.getByText(OFFLINE)).toBeInTheDocument();
    await user.click(screen.getByRole("button", { name: "Повторить" }));
    await elapse(0);
    expect(search).toHaveBeenLastCalledWith("дандадан");
    expect(cardLink("Дандадан")).toBeInTheDocument();
  });

  it("never lets a late answer to an older query replace the newer one", async () => {
    let answerOld: (titles: Anime[]) => void = () => undefined;
    const search = vi
      .fn<Shikimori["search"]>()
      .mockImplementationOnce(
        () =>
          new Promise<Anime[]>((resolve) => {
            answerOld = resolve;
          }),
      )
      .mockResolvedValueOnce([DANDADAN]);
    const { user } = setup({ search });
    await elapse(0);
    await user.type(field(), "да");
    await elapse(350);
    const loading = screen.getByText("Ищем аниме…");
    expect(loading.closest('[role="status"]')).toHaveAttribute("aria-busy", "true");
    await user.keyboard("н");
    await elapse(350);
    expect(cardLink("Дандадан")).toBeInTheDocument();
    answerOld([FRIEREN]);
    await elapse(0);
    expect(screen.queryByRole("link", { name: /Фрирен/ })).not.toBeInTheDocument();
    expect(cardLink("Дандадан")).toBeInTheDocument();
  });

  it("«Очистить» empties the field, keeps the focus there and brings the popular titles back", async () => {
    const search = vi.fn<Shikimori["search"]>().mockResolvedValue([DANDADAN]);
    const { user } = setup({ search });
    await elapse(0);
    await user.type(field(), "дандадан");
    await elapse(350);
    expect(screen.queryByRole("region", { name: "Популярно сейчас" })).not.toBeInTheDocument();
    await user.click(screen.getByRole("button", { name: "Очистить" }));
    expect(field()).toHaveValue("");
    expect(field()).toHaveFocus();
    expect(screen.getByRole("region", { name: "Популярно сейчас" })).toBeInTheDocument();
    expect(screen.queryByRole("button", { name: "Очистить" })).not.toBeInTheDocument();
  });

  it("runs a query that came in with the address at once", async () => {
    const search = vi.fn<Shikimori["search"]>().mockResolvedValue([DANDADAN]);
    setup({ search }, { path: "/search?q=дандадан" });
    await elapse(0);
    expect(search).toHaveBeenCalledWith("дандадан");
    expect(field()).toHaveValue("дандадан");
    expect(cardLink("Дандадан")).toBeInTheDocument();
  });

  it("coming back from a title shows the same results at once, without searching again", async () => {
    const search = vi.fn<Shikimori["search"]>().mockResolvedValue([DANDADAN]);
    const { user } = setup({ search });
    await user.type(field(), "дандадан");
    await elapse(350);
    await user.click(cardLink("Дандадан"));
    await user.click(screen.getByRole("button", { name: "Назад" }));

    expect(field()).toHaveValue("дандадан");
    expect(cardLink("Дандадан")).toBeInTheDocument();
    await elapse(DEBOUNCE);
    expect(search).toHaveBeenCalledTimes(1);
  });

  it("«В планы» puts the title in «В планах» and the card says so", async () => {
    const createRate = vi.fn<Shikimori["createRate"]>().mockImplementation(created);
    const { user, library } = setup({ createRate });
    await elapse(0);
    await user.click(screen.getByRole("button", { name: "Добавить Фрирен в планы" }));
    await elapse(0);
    expect(library.entry(7)?.rate.status).toBe("planned");
    expect(within(cardOf("Фрирен")).getByRole("button", { name: "В списке" })).toBeDisabled();
  });

  it("keeps «В планы» shut until the list is read, so it never creates a rate Shikimori already holds", async () => {
    let answerRates: (rates: UserRate[]) => void = () => undefined;
    const userRates = vi.fn<Shikimori["userRates"]>().mockImplementation(
      () =>
        new Promise<UserRate[]>((resolve) => {
          answerRates = resolve;
        }),
    );
    const createRate = vi.fn<Shikimori["createRate"]>().mockImplementation(created);
    const { user } = setup({ userRates, createRate });
    await elapse(0);
    const add = screen.getByRole("button", { name: "Добавить Фрирен в планы" });
    expect(add).toBeDisabled();
    await user.click(add);
    await elapse(0);
    expect(createRate).not.toHaveBeenCalled();
    answerRates([]);
    await elapse(0);
    expect(screen.getByRole("button", { name: "Добавить Фрирен в планы" })).toBeEnabled();
  });

  it("keeps «В планы» shut when the list could not be read", async () => {
    const createRate = vi.fn<Shikimori["createRate"]>().mockImplementation(created);
    const { user } = setup({ userRates: () => Promise.reject(new NetworkError("offline")), createRate });
    await elapse(0);
    const add = screen.getByRole("button", { name: "Добавить Фрирен в планы" });
    expect(add).toBeDisabled();
    await user.click(add);
    await elapse(0);
    expect(createRate).not.toHaveBeenCalled();
  });

  it("a failed add puts «В планы» back and offers «Повторить» in a toast", async () => {
    const createRate = vi
      .fn<Shikimori["createRate"]>()
      .mockRejectedValueOnce(new NetworkError("offline"))
      .mockImplementation(created);
    const { user, library } = setup({ createRate });
    await elapse(0);
    await user.click(screen.getByRole("button", { name: "Добавить Фрирен в планы" }));
    await elapse(0);
    expect(library.entry(7)).toBeUndefined();
    expect(screen.getByRole("button", { name: "Добавить Фрирен в планы" })).toBeEnabled();
    const toast = screen.getByText(OFFLINE).parentElement as HTMLElement;
    await user.click(within(toast).getByRole("button", { name: "Повторить" }));
    await elapse(0);
    expect(createRate).toHaveBeenCalledTimes(2);
    expect(library.entry(7)?.rate.status).toBe("planned");
    expect(screen.queryByText(OFFLINE)).not.toBeInTheDocument();
  });
});
