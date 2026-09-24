// The home screen over the real Library and ProgressStore with a fake Shikimori.
// Copy and state rules: android/src/main/java/app/kaeru/ui/mobile/home/HomeScreen.kt,
// android/src/main/java/app/kaeru/ui/mobile/home/HomeDiscover.kt,
// android/src/test/java/app/kaeru/ui/common/home/HomeContentTest.kt.
import { act, cleanup, render, screen, waitFor, within } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { MemoryRouter, Route, Routes, useLocation } from "react-router-dom";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { ApiError, NetworkError } from "../api/http";
import type { Shikimori } from "../api/shikimori";
import { ServicesContext } from "../app/services";
import type { authorized } from "../auth/session";
import type { Anime, ListStatus, UserRate } from "../domain/models";
import type { Season } from "../domain/season";
import { Library } from "../library/library";
import { ProgressStore } from "../library/progress";
import { noPlayback } from "../test/fakes";
import { ToastProvider } from "../ui/Toast";
import { CatalogueCache } from "./home";
import { HomeScreen } from "./HomeScreen";

// Local wall-clock time: September 2026 is the summer season in any zone.
const NOW = new Date(2026, 8, 13, 20, 0, 0).getTime();
const HOUR = 3_600_000;
const DAY = 24 * HOUR;

const ROWS = [
  "Новые серии",
  "Продолжить",
  "Дальше по списку",
  "Скоро",
  "В планах",
  "Популярно сейчас",
  "Популярное в сезоне",
];
const EMPTY_TITLE = "Здесь появятся тайтлы из списка «Смотрю»";
const EMPTY_TEXT =
  "Отметьте аниме как «Смотрю» на Shikimori или найдите его здесь. Kaeru продолжит с той серии, на которой вы остановились.";

function anime(id: number, title: string, spec: Partial<Anime> = {}): Anime {
  return {
    id,
    title,
    originalTitle: title,
    posterUrl: null,
    backdropUrl: null,
    status: "ongoing",
    episodes: 24,
    episodesAired: 7,
    year: 2026,
    score: 8.1,
    kind: "tv",
    studios: [],
    description: null,
    nextEpisodeAt: null,
    ...spec,
  };
}

function rate(animeId: number, status: ListStatus, episodes: number, updatedAt = NOW - DAY): UserRate {
  return { id: 100 + animeId, animeId, status, episodes, updatedAt };
}

const unused = () => Promise.reject(new Error("not used on the home screen"));
const fakeAuthorized: typeof authorized = (call) => call("token");

interface Setup {
  rates?: UserRate[];
  titles?: Anime[];
  popularNow?: Shikimori["popularNow"];
  popularInSeason?: Shikimori["popularInSeason"];
}

function setup(s: Setup = {}) {
  const titles = s.titles ?? [];
  const userRates: Shikimori["userRates"] = async () => s.rates ?? [];
  const popularNow: Shikimori["popularNow"] = s.popularNow ?? (async () => [anime(50, "Популярное аниме")]);
  const popularInSeason: Shikimori["popularInSeason"] =
    s.popularInSeason ?? (async () => [anime(51, "Сезонное аниме")]);
  const shikimori = {
    whoami: unused,
    details: unused,
    search: unused,
    createRate: unused,
    updateRate: unused,
    userRates: vi.fn(userRates),
    byIds: vi.fn(async (ids: readonly number[]) => titles.filter((a) => ids.includes(a.id))),
    popularNow: vi.fn(popularNow),
    popularInSeason: vi.fn(popularInSeason),
  } satisfies Shikimori;
  const progress = new ProgressStore(window.localStorage);
  const library = new Library({ shikimori, authorized: fakeAuthorized, accountId: () => 7, progress });
  return { shikimori, progress, library };
}

/** One title in every personal row; «Фрирен» is 6:40 into its 7th episode. */
function watchingFixture() {
  const services = setup({
    titles: [
      anime(1, "Фрирен", { episodes: 28, episodesAired: 24 }),
      anime(2, "Дандадан"),
      anime(3, "Монолог фармацевта", { status: "released", episodes: 12, episodesAired: 12 }),
      anime(4, "Магическая битва", { nextEpisodeAt: NOW + 2 * DAY }),
      anime(5, "Клинок", { status: "released", episodes: 12, episodesAired: 12 }),
    ],
    rates: [
      rate(1, "watching", 6),
      rate(2, "watching", 6),
      rate(3, "watching", 3),
      rate(4, "watching", 7),
      rate(5, "planned", 0),
    ],
  });
  services.progress.put({ animeId: 1, episode: 7, positionMs: 400_000, durationMs: 1_440_000, updatedAt: NOW - HOUR });
  return services;
}

function Where() {
  const location = useLocation();
  return <p data-testid="where">{location.pathname}</p>;
}

function renderHome(services: ReturnType<typeof setup>, cache = new CatalogueCache({ now: () => NOW })) {
  const { shikimori, library, progress } = services;
  return render(
    <ServicesContext.Provider value={{ shikimori, library, progress, ...noPlayback() }}>
      <ToastProvider>
        <MemoryRouter initialEntries={["/"]}>
          <Routes>
            <Route path="/" element={<HomeScreen now={() => NOW} catalogue={cache} />} />
            <Route path="*" element={<Where />} />
          </Routes>
        </MemoryRouter>
      </ToastProvider>
    </ServicesContext.Provider>,
  );
}

let online = true;

beforeEach(() => {
  window.localStorage.clear();
  online = true;
  Object.defineProperty(navigator, "onLine", { configurable: true, get: () => online });
});

afterEach(() => {
  cleanup();
  Reflect.deleteProperty(navigator, "onLine");
});

describe("HomeScreen", () => {
  it("puts the continued title in the hero and the rows in Android's order", async () => {
    renderHome(watchingFixture());

    const hero = await screen.findByRole("region", { name: "Фрирен" });
    expect(within(hero).getByText("7 серия, осталось 17 мин")).toBeInTheDocument();
    expect(within(hero).getByRole("link", { name: "Продолжить с 6:40" })).toHaveAttribute("href", "/watch/1/7");
    expect(within(hero).getByRole("link", { name: "Подробнее" })).toHaveAttribute("href", "/anime/1");

    await screen.findByText("Популярное аниме");
    await screen.findByText("Сезонное аниме");
    const headings = screen
      .getAllByRole("heading", { level: 2 })
      .map((heading) => heading.textContent ?? "")
      .filter((text) => ROWS.includes(text));
    expect(headings).toEqual(ROWS);
  });

  it("opens the player route for the episode the hero offers", async () => {
    const user = userEvent.setup();
    renderHome(watchingFixture());

    const hero = await screen.findByRole("region", { name: "Фрирен" });
    await user.click(within(hero).getByRole("link", { name: "Продолжить с 6:40" }));

    expect(screen.getByTestId("where")).toHaveTextContent("/watch/1/7");
  });

  it("opens the title page from «Подробнее»", async () => {
    const user = userEvent.setup();
    renderHome(watchingFixture());

    const hero = await screen.findByRole("region", { name: "Фрирен" });
    await user.click(within(hero).getByRole("link", { name: "Подробнее" }));

    expect(screen.getByTestId("where")).toHaveTextContent("/anime/1");
  });

  it("names the page for screen readers without a visible title", async () => {
    renderHome(watchingFixture());

    const title = screen.getByRole("heading", { level: 1, name: "Главная" });
    expect(title).toHaveClass("visually-hidden");
    await screen.findByRole("region", { name: "Фрирен" });
  });

  it("does not call a list that is still coming down the wire empty", async () => {
    const services = setup();
    services.shikimori.userRates.mockReturnValue(new Promise<UserRate[]>(() => {}));
    renderHome(services);

    expect(await screen.findByText("Синхронизируем список с Shikimori…")).toBeInTheDocument();
    expect(screen.queryByText(EMPTY_TITLE)).not.toBeInTheDocument();
  });

  it("invites a search from an empty list and keeps the catalogue under it", async () => {
    const user = userEvent.setup();
    renderHome(setup());

    expect(await screen.findByText(EMPTY_TITLE)).toBeInTheDocument();
    expect(screen.getByText(EMPTY_TEXT)).toBeInTheDocument();
    expect(await screen.findByRole("heading", { name: "Популярно сейчас" })).toBeInTheDocument();

    const find = screen.getByRole("link", { name: "Найти аниме" });
    expect(find).toHaveAttribute("href", "/search");
    await user.click(find);
    expect(screen.getByTestId("where")).toHaveTextContent("/search");
  });

  it("explains a failed first sync and retries it", async () => {
    const user = userEvent.setup();
    const services = setup();
    services.shikimori.userRates.mockRejectedValueOnce(new NetworkError("offline"));
    renderHome(services);

    expect(await screen.findByText("Нет соединения. Проверьте интернет")).toBeInTheDocument();
    await user.click(screen.getByRole("button", { name: "Повторить" }));

    expect(await screen.findByText(EMPTY_TITLE)).toBeInTheDocument();
    expect(services.shikimori.userRates).toHaveBeenCalledTimes(2);
  });

  it("reports a failed refresh over a feed as a toast with «Повторить»", async () => {
    const services = watchingFixture();
    await services.library.load().catch(() => undefined);
    renderHome(services);
    await screen.findByRole("region", { name: "Фрирен" });

    services.shikimori.userRates.mockRejectedValueOnce(new ApiError(503));
    await act(async () => {
      await services.library.load().catch(() => undefined);
    });

    expect(await screen.findByText("Shikimori недоступен, попробуйте позже")).toBeInTheDocument();
    expect(screen.getByRole("button", { name: "Повторить" })).toBeInTheDocument();
    expect(screen.getByRole("region", { name: "Фрирен" })).toBeInTheDocument();
  });

  it("hides the catalogue offline, says «Нет сети» and brings the rows back online", async () => {
    online = false;
    const services = watchingFixture();
    renderHome(services);

    expect(await screen.findByText("Нет сети")).toBeInTheDocument();
    await screen.findByRole("region", { name: "Фрирен" });
    expect(screen.queryByRole("heading", { name: "Популярно сейчас" })).not.toBeInTheDocument();
    expect(services.shikimori.popularNow).not.toHaveBeenCalled();

    online = true;
    act(() => {
      window.dispatchEvent(new Event("online"));
    });

    expect(await screen.findByText("Популярное аниме")).toBeInTheDocument();
    expect(screen.queryByText("Нет сети")).not.toBeInTheDocument();
  });

  it("leaves out «Популярно сейчас» when it could not be read", async () => {
    const services = setup({
      popularNow: async () => {
        throw new ApiError(503);
      },
    });
    renderHome(services);

    expect(await screen.findByText("Сезонное аниме")).toBeInTheDocument();
    await waitFor(() =>
      expect(screen.queryByRole("heading", { name: "Популярно сейчас" })).not.toBeInTheDocument(),
    );
    expect(services.shikimori.popularNow).toHaveBeenCalledTimes(1);
  });

  it("keeps the season heading and switcher over a skeleton while a season loads", async () => {
    renderHome(setup({ popularInSeason: () => new Promise<Anime[]>(() => {}) }));

    const block = await screen.findByRole("region", { name: "Популярное в сезоне" });
    expect(within(block).getByRole("radiogroup", { name: "Сезон" })).toBeInTheDocument();
    expect(within(block).getByRole("status")).toHaveTextContent("Загрузка…");
    expect(within(block).queryByRole("link")).not.toBeInTheDocument();
  });

  it("takes the season block away when the very first season fails", async () => {
    renderHome(
      setup({
        popularInSeason: async () => {
          throw new ApiError(503);
        },
      }),
    );

    expect(await screen.findByText("Популярное аниме")).toBeInTheDocument();
    await waitFor(() =>
      expect(screen.queryByRole("radiogroup", { name: "Сезон" })).not.toBeInTheDocument(),
    );
    expect(screen.queryByText("Не удалось загрузить сезон")).not.toBeInTheDocument();
  });

  it("switches seasons from a radio group, retries a failed one and remembers the rest", async () => {
    const user = userEvent.setup();
    const services = setup({
      popularInSeason: async (season: Season) => {
        if (season.kind === "fall") throw new ApiError(503);
        return [anime(51, "Сезонное аниме")];
      },
    });
    const callsFor = (kind: Season["kind"]) =>
      services.shikimori.popularInSeason.mock.calls.filter(([season]) => season.kind === kind).length;
    renderHome(services);

    const group = await screen.findByRole("radiogroup", { name: "Сезон" });
    expect(within(group).getAllByRole("radio").map((radio) => radio.textContent)).toEqual([
      "Весна 2026",
      "Лето 2026",
      "Осень 2026",
    ]);
    expect(within(group).getByRole("radio", { name: "Лето 2026" })).toBeChecked();
    expect(await screen.findByText("Сезонное аниме")).toBeInTheDocument();

    await user.click(within(group).getByRole("radio", { name: "Осень 2026" }));
    expect(within(group).getByRole("radio", { name: "Осень 2026" })).toBeChecked();
    expect(await screen.findByText("Не удалось загрузить сезон")).toBeInTheDocument();

    await user.click(screen.getByRole("button", { name: "Повторить" }));
    await waitFor(() => expect(callsFor("fall")).toBe(2));

    await user.click(within(group).getByRole("radio", { name: "Лето 2026" }));
    expect(screen.getByText("Сезонное аниме")).toBeInTheDocument();
    expect(callsFor("summer")).toBe(1);

    within(group).getByRole("radio", { name: "Лето 2026" }).focus();
    await user.keyboard("{ArrowLeft}");
    const spring = within(group).getByRole("radio", { name: "Весна 2026" });
    expect(spring).toBeChecked();
    expect(spring).toHaveFocus();
  });

  it("says a season with nothing in it is empty and keeps the switcher", async () => {
    renderHome(setup({ popularInSeason: async () => [] }));

    expect(await screen.findByText("В этом сезоне пока ничего нет")).toBeInTheDocument();
    expect(screen.getByRole("radiogroup", { name: "Сезон" })).toBeInTheDocument();
  });
});
