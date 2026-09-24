// The title screen over the real Library, ProgressStore and ToastProvider with a fake Shikimori.
// Copy and rules: web-map 4; android/src/main/java/app/kaeru/ui/mobile/details/DetailsScreen.kt,
// EpisodeSection.kt; ios/Features/DetailView.swift.
import { cleanup, render, screen, waitFor, within } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { MemoryRouter, Route, Routes, useParams } from "react-router-dom";
import { afterEach, describe, expect, it } from "vitest";
import { ApiError, NetworkError } from "../api/http";
import type { Shikimori } from "../api/shikimori";
import { ServicesContext, type Services } from "../app/services";
import type { authorized } from "../auth/session";
import type { Anime, EpisodeProgress, ListStatus, UserRate } from "../domain/models";
import { Library } from "../library/library";
import { ProgressStore } from "../library/progress";
import { ToastProvider } from "../ui/Toast";
import { TitleScreen } from "./TitleScreen";

function memoryStorage(): Storage {
  const data = new Map<string, string>();
  return {
    get length() {
      return data.size;
    },
    clear: () => data.clear(),
    getItem: (key: string) => data.get(key) ?? null,
    key: (index: number) => [...data.keys()][index] ?? null,
    removeItem: (key: string) => {
      data.delete(key);
    },
    setItem: (key: string, value: string) => {
      data.set(key, String(value));
    },
  };
}

/** A domain Anime: Task 5 has already cleaned the description. */
function deathNote(overrides: Partial<Anime> = {}): Anime {
  return {
    id: 1535,
    title: "Тетрадь смерти",
    originalTitle: "Death Note",
    posterUrl: "https://shikimori.io/uploads/poster/animes/1535/poster.jpeg",
    backdropUrl: "https://shikimori.io/system/screenshots/original/1535.jpg",
    status: "released",
    episodes: 37,
    episodesAired: 0,
    year: 2006,
    score: 8.62,
    kind: "tv",
    studios: ["Madhouse"],
    description: "Ягами Лайт находит Тетрадь смерти.",
    nextEpisodeAt: null,
    ...overrides,
  };
}

function stopped(episode: number, positionMs: number, durationMs = 1_400_000): EpisodeProgress {
  return { animeId: 1535, episode, positionMs, durationMs, updatedAt: 0 };
}

function fieldsText(fields: { status?: ListStatus; episodes?: number }): string {
  return Object.entries(fields)
    .map(([name, value]) => `${name}=${String(value)}`)
    .join(" ");
}

class FakeShikimori {
  readonly calls: string[] = [];
  rates: UserRate[] = [];
  cards: Anime[] = [];
  details: Anime = deathNote();
  detailsFailures = 0;
  /** How many list reads fail before one succeeds. */
  rateFailures = 0;
  /** While set, details wait for it. */
  detailsGate: Promise<void> | null = null;
  /** One entry per write, in order; a truthy entry is thrown by that write. */
  failures: unknown[] = [];
  private nextId = 900;

  writes(): string[] {
    return this.calls.filter((call) => call.startsWith("POST") || call.startsWith("PATCH"));
  }

  api(): Shikimori {
    const unused = async (): Promise<never> => {
      throw new Error("not used by the title screen");
    };
    return {
      whoami: unused,
      search: unused,
      popularNow: unused,
      popularInSeason: unused,
      details: async (id) => {
        this.calls.push(`details ${id}`);
        if (this.detailsGate) await this.detailsGate;
        if (this.detailsFailures > 0) {
          this.detailsFailures -= 1;
          throw new NetworkError("Failed to fetch");
        }
        return this.details;
      },
      byIds: async (ids) => this.cards.filter((card) => ids.includes(card.id)),
      userRates: async () => {
        if (this.rateFailures > 0) {
          this.rateFailures -= 1;
          throw new NetworkError("Failed to fetch");
        }
        return this.rates.map((rate) => ({ ...rate }));
      },
      createRate: async (_token, userId, animeId, fields) => {
        this.calls.push(`POST ${userId}/${animeId} ${fieldsText(fields)}`);
        const failure = this.failures.shift();
        if (failure) throw failure;
        const rate: UserRate = { id: this.nextId++, animeId, status: fields.status, episodes: fields.episodes ?? 0, updatedAt: 5 };
        this.rates.push(rate);
        return { ...rate };
      },
      updateRate: async (_token, rateId, fields) => {
        this.calls.push(`PATCH ${rateId} ${fieldsText(fields)}`);
        const failure = this.failures.shift();
        if (failure) throw failure;
        const index = this.rates.findIndex((rate) => rate.id === rateId);
        const current = this.rates[index];
        if (!current) throw new ApiError(404);
        const rate: UserRate = { ...current, ...fields, updatedAt: 5 };
        this.rates[index] = rate;
        return { ...rate };
      },
    };
  }
}

const fakeAuthorized: typeof authorized = (call) => call("tok");

let server: FakeShikimori;
let progress: ProgressStore;
let services: Services;

function start(details: Anime, rate?: { status: ListStatus; episodes: number }, rows: EpisodeProgress[] = []): void {
  server = new FakeShikimori();
  server.details = details;
  server.cards = [details];
  if (rate) server.rates = [{ id: 1, animeId: details.id, status: rate.status, episodes: rate.episodes, updatedAt: 1 }];
  progress = new ProgressStore(memoryStorage());
  for (const row of rows) progress.put(row);
  const shikimori = server.api();
  const library = new Library({ shikimori, authorized: fakeAuthorized, accountId: () => 42, progress });
  services = { shikimori, library, progress };
}

function WatchProbe() {
  const { id, episode } = useParams();
  return <p>{`watch ${id ?? ""}/${episode ?? ""}`}</p>;
}

function renderTitle(id = 1535): void {
  render(
    <ServicesContext.Provider value={services}>
      <ToastProvider>
        <MemoryRouter initialEntries={[`/anime/${id}`]}>
          <Routes>
            <Route path="/" element={<p>home</p>} />
            <Route path="/anime/:id" element={<TitleScreen />} />
            <Route path="/watch/:id/:episode" element={<WatchProbe />} />
          </Routes>
        </MemoryRouter>
      </ToastProvider>
    </ServicesContext.Provider>,
  );
}

type User = ReturnType<typeof userEvent.setup>;

async function pick(user: User, episode: number, action: string): Promise<void> {
  const more = await screen.findByRole("button", { name: `Что сделать с серией ${episode}` });
  await waitFor(() => expect(more).toBeEnabled());
  await user.click(more);
  await user.click(within(screen.getByRole("menu")).getByRole("menuitem", { name: action }));
}

afterEach(cleanup);

describe("TitleScreen", () => {
  it("shows the artwork header: title, original title and the facts line", async () => {
    start(deathNote());
    renderTitle();

    expect(await screen.findByRole("heading", { level: 1, name: "Тетрадь смерти" })).toBeInTheDocument();
    expect(screen.getByText("Death Note")).toBeInTheDocument();
    expect(screen.getByText("Вышло · 2006 · 37 эп. · ★ 8.62 · Сериал · Madhouse")).toBeInTheDocument();
    expect(server.calls).toContain("details 1535");
  });

  it("says why the list controls are shut when the list could not be read, and reads it again", async () => {
    const user = userEvent.setup();
    start(deathNote());
    server.rateFailures = 1;
    renderTitle();

    const notice = await screen.findByRole("alert");
    expect(notice).toHaveTextContent("Не удалось загрузить ваш список — без него отметки недоступны");
    expect(screen.getByRole("button", { name: "Добавить в планы" })).toBeDisabled();

    await user.click(within(notice).getByRole("button", { name: "Повторить" }));
    await waitFor(() => expect(screen.getByRole("button", { name: "Добавить в планы" })).toBeEnabled());
    expect(screen.queryByRole("alert")).not.toBeInTheDocument();
  });

  it("says it is loading while the title comes", async () => {
    start(deathNote());
    server.detailsGate = new Promise(() => undefined);
    renderTitle();

    const label = await screen.findByText("Обновляем информацию…");
    expect(label.closest("[role='status']")).toHaveAttribute("aria-busy", "true");
    expect(screen.queryByRole("heading", { level: 1 })).not.toBeInTheDocument();
  });

  it("starts a title that is in no list from its first episode", async () => {
    const user = userEvent.setup();
    start(deathNote());
    renderTitle();

    await user.click(await screen.findByRole("link", { name: "Смотреть 1 серию" }));

    expect(await screen.findByText("watch 1535/1")).toBeInTheDocument();
    expect(server.writes()).toEqual([]);
  });

  it("continues where this browser stopped", async () => {
    const user = userEvent.setup();
    start(deathNote(), { status: "watching", episodes: 3 }, [stopped(4, 754_000)]);
    renderTitle();

    await user.click(await screen.findByRole("link", { name: "Продолжить с 12:34" }));

    expect(await screen.findByText("watch 1535/4")).toBeInTheDocument();
  });

  it("goes home from «Назад» when the page was opened directly", async () => {
    const user = userEvent.setup();
    start(deathNote());
    renderTitle();

    await user.click(await screen.findByRole("button", { name: "Назад" }));

    expect(await screen.findByText("home")).toBeInTheDocument();
  });

  it("adds a title that is in no list to the plans and keeps focus on the status", async () => {
    const user = userEvent.setup();
    start(deathNote());
    renderTitle();
    const add = await screen.findByRole("button", { name: "Добавить в планы" });
    await waitFor(() => expect(add).toBeEnabled());

    await user.click(add);

    await waitFor(() => expect(screen.getByRole("button", { name: "В планах" })).toHaveFocus());
    await waitFor(() => expect(server.writes()).toEqual(["POST 42/1535 status=planned"]));
  });

  it("changes the status from a menu in the Android order with the current one ticked", async () => {
    const user = userEvent.setup();
    start(deathNote(), { status: "watching", episodes: 3 });
    renderTitle();
    const status = await screen.findByRole("button", { name: "Смотрю" });
    await waitFor(() => expect(status).toBeEnabled());

    await user.click(status);

    const menu = screen.getByRole("menu");
    expect(within(menu).getAllByRole("menuitemradio").map((item) => item.textContent)).toEqual([
      "Смотрю",
      "В планах",
      "Завершено",
      "Отложено",
      "Брошено",
      "Пересматриваю",
    ]);
    expect(within(menu).getByRole("menuitemradio", { name: "Смотрю" })).toHaveAttribute("aria-checked", "true");
    await user.keyboard("{ArrowDown}{ArrowDown}{ArrowDown}{Enter}");
    expect(await screen.findByRole("button", { name: "Отложено" })).toHaveFocus();
    await waitFor(() => expect(server.writes()).toEqual(["PATCH 1 status=on_hold"]));
    expect(screen.queryByRole("menu")).not.toBeInTheDocument();
  });

  it("lists the episodes as rows with what each one says", async () => {
    start(deathNote(), { status: "watching", episodes: 3 }, [stopped(4, 754_000)]);
    renderTitle();

    expect(await screen.findByText("Просмотрено 3 из 37")).toBeInTheDocument();
    const list = screen.getByRole("list", { name: "Серии" });
    expect(within(list).getAllByRole("listitem")).toHaveLength(37);
    expect(within(list).getByRole("link", { name: "3 серия, просмотрено" })).toHaveAttribute("href", "/watch/1535/3");
    expect(within(list).getByRole("link", { name: "4 серия, остановились на 12:34" })).toHaveAttribute(
      "href",
      "/watch/1535/4",
    );
    expect(within(list).getByRole("link", { name: "5 серия" })).toHaveAttribute("href", "/watch/1535/5");
    expect(within(list).getByText("12:34")).toBeInTheDocument();
    expect(list.querySelectorAll(".title-episode-progress")).toHaveLength(1);
  });

  it("plays an episode from its row", async () => {
    const user = userEvent.setup();
    start(deathNote(), { status: "watching", episodes: 3 });
    renderTitle();

    await user.click(await screen.findByRole("link", { name: "7 серия" }));

    expect(await screen.findByText("watch 1535/7")).toBeInTheDocument();
  });

  it("pages the rows sixty at a time", async () => {
    const user = userEvent.setup();
    start(deathNote({ episodes: 130 }));
    renderTitle();
    const list = await screen.findByRole("list", { name: "Серии" });
    expect(within(list).getAllByRole("listitem")).toHaveLength(60);

    await user.click(screen.getByRole("button", { name: "Показать ещё 60 серий" }));
    expect(within(list).getAllByRole("listitem")).toHaveLength(120);

    await user.click(screen.getByRole("button", { name: "Показать ещё 10 серий" }));
    expect(within(list).getAllByRole("listitem")).toHaveLength(130);

    await user.click(screen.getByRole("button", { name: "Свернуть серии" }));
    expect(within(list).getAllByRole("listitem")).toHaveLength(60);
  });

  it("shows episodes that have not aired as plain rows without a link or a menu", async () => {
    start(deathNote({ status: "ongoing", episodes: 12, episodesAired: 5 }), { status: "watching", episodes: 3 });
    renderTitle();

    const list = await screen.findByRole("list", { name: "Серии" });
    expect(within(list).getByText("6 серия, не вышла")).toBeInTheDocument();
    expect(within(list).queryByRole("link", { name: /^6 серия/ })).not.toBeInTheDocument();
    expect(screen.queryByRole("button", { name: "Что сделать с серией 6" })).not.toBeInTheDocument();
    expect(within(list).getByRole("link", { name: "5 серия" })).toBeInTheDocument();
    expect(screen.getByRole("button", { name: "Что сделать с серией 5" })).toBeInTheDocument();
  });

  it("waits for a title that has not aired yet with a disabled button", async () => {
    start(deathNote({ status: "anons", episodes: 12, episodesAired: 0 }));
    renderTitle();

    expect(await screen.findByRole("button", { name: "Ещё не вышло" })).toBeDisabled();
    expect(screen.queryByRole("link", { name: /Смотреть/ })).not.toBeInTheDocument();
  });

  it("marks an episode watched from its row menu", async () => {
    const user = userEvent.setup();
    start(deathNote(), { status: "watching", episodes: 3 });
    renderTitle();
    await screen.findByText("Просмотрено 3 из 37");

    await pick(user, 5, "Отметить просмотренной");

    expect(await screen.findByText("Просмотрено 5 из 37")).toBeInTheDocument();
    await waitFor(() => expect(server.writes()).toEqual(["PATCH 1 episodes=5"]));
    expect(screen.queryByRole("dialog")).not.toBeInTheDocument();
  });

  it("offers to complete the title after its last episode", async () => {
    const user = userEvent.setup();
    start(deathNote({ episodes: 12 }), { status: "watching", episodes: 11 });
    renderTitle();
    await screen.findByText("Просмотрено 11 из 12");

    await pick(user, 12, "Отметить просмотренной");

    const dialog = await screen.findByRole("dialog", { name: "Перевести аниме в завершённые?" });
    expect(within(dialog).getByText("Тетрадь смерти")).toBeInTheDocument();
    expect(within(dialog).getByRole("button", { name: "Позже" })).toHaveFocus();
    await user.click(within(dialog).getByRole("button", { name: "Завершить просмотр" }));
    expect(await screen.findByRole("button", { name: "Завершено" })).toBeInTheDocument();
    await waitFor(() => expect(server.writes()).toEqual(["PATCH 1 episodes=12", "PATCH 1 status=completed"]));
    expect(screen.queryByRole("dialog")).not.toBeInTheDocument();
  });

  it("lets the finale wait for later without changing the status", async () => {
    const user = userEvent.setup();
    start(deathNote({ episodes: 12 }), { status: "watching", episodes: 11 });
    renderTitle();
    await screen.findByText("Просмотрено 11 из 12");

    await pick(user, 12, "Отметить просмотренной");
    const dialog = await screen.findByRole("dialog", { name: "Перевести аниме в завершённые?" });
    await user.click(within(dialog).getByRole("button", { name: "Позже" }));

    expect(screen.queryByRole("dialog")).not.toBeInTheDocument();
    expect(server.writes()).toEqual(["PATCH 1 episodes=12"]);
    expect(screen.getByRole("button", { name: "Смотрю" })).toBeInTheDocument();
  });

  it("does not ask to complete a title that is already completed", async () => {
    const user = userEvent.setup();
    start(deathNote({ episodes: 12 }), { status: "completed", episodes: 10 });
    renderTitle();
    await screen.findByText("Просмотрено 10 из 12");

    await pick(user, 12, "Отметить просмотренной");

    expect(await screen.findByText("Просмотрено 12 из 12")).toBeInTheDocument();
    await waitFor(() => expect(server.writes()).toEqual(["PATCH 1 episodes=12"]));
    expect(screen.queryByRole("dialog")).not.toBeInTheDocument();
  });

  it("unmarks without asking and undoes it from the toast", async () => {
    const user = userEvent.setup();
    start(deathNote({ episodes: 12 }), { status: "watching", episodes: 7 }, [stopped(5, 600_000), stopped(6, 300_000)]);
    renderTitle();
    await screen.findByText("Просмотрено 7 из 12");

    await pick(user, 5, "Отметить непросмотренной");

    expect(await screen.findByText("Серия 5 отмечена непросмотренной")).toBeInTheDocument();
    expect(screen.queryByRole("dialog")).not.toBeInTheDocument();
    expect(server.writes()).toEqual(["PATCH 1 episodes=4"]);
    expect(screen.getByText("Просмотрено 4 из 12")).toBeInTheDocument();
    expect(progress.of(1535)).toEqual([]);

    await user.click(screen.getByRole("button", { name: "Отменить" }));

    await waitFor(() => expect(server.writes()).toEqual(["PATCH 1 episodes=4", "PATCH 1 episodes=7"]));
    await waitFor(() => expect(progress.of(1535).map((row) => row.episode)).toEqual([5, 6]));
    expect(await screen.findByText("Просмотрено 7 из 12")).toBeInTheDocument();
    expect(screen.queryByText("Серия 5 отмечена непросмотренной")).not.toBeInTheDocument();
  });

  it("puts a mark back and says why when Shikimori refuses it", async () => {
    const user = userEvent.setup();
    start(deathNote(), { status: "watching", episodes: 3 });
    server.failures = [new ApiError(500)];
    renderTitle();
    await screen.findByText("Просмотрено 3 из 37");

    await pick(user, 5, "Отметить просмотренной");

    expect(await screen.findByText("Shikimori недоступен, попробуйте позже")).toBeInTheDocument();
    expect(await screen.findByText("Просмотрено 3 из 37")).toBeInTheDocument();
    expect(server.writes()).toEqual(["PATCH 1 episodes=5"]);
  });

  it("offers a retry when the title cannot be loaded", async () => {
    const user = userEvent.setup();
    start(deathNote());
    server.detailsFailures = 1;
    renderTitle();

    expect(await screen.findByText("Не удалось загрузить аниме. Проверьте соединение и повторите")).toBeInTheDocument();
    await user.click(screen.getByRole("button", { name: "Повторить" }));

    expect(await screen.findByRole("heading", { level: 1, name: "Тетрадь смерти" })).toBeInTheDocument();
  });

  describe("about", () => {
    afterEach(() => {
      Reflect.deleteProperty(HTMLElement.prototype, "scrollHeight");
      Reflect.deleteProperty(HTMLElement.prototype, "clientHeight");
    });

    it("clamps a long description to four lines until asked for the rest", async () => {
      // jsdom has no layout: pretend the clamped paragraph hides text.
      Object.defineProperty(HTMLElement.prototype, "scrollHeight", {
        configurable: true,
        get(this: HTMLElement) {
          return this.dataset.clamp === "about" ? 400 : 0;
        },
      });
      Object.defineProperty(HTMLElement.prototype, "clientHeight", {
        configurable: true,
        get(this: HTMLElement) {
          return this.dataset.clamp === "about" ? 88 : 0;
        },
      });
      const user = userEvent.setup();
      start(deathNote());
      renderTitle();

      expect(await screen.findByRole("heading", { level: 2, name: "Об аниме" })).toBeInTheDocument();
      expect(screen.getByText("Ягами Лайт находит Тетрадь смерти.")).toBeInTheDocument();
      const more = await screen.findByRole("button", { name: "Читать полностью" });
      expect(more).toHaveAttribute("aria-expanded", "false");

      await user.click(more);
      const less = screen.getByRole("button", { name: "Свернуть" });
      expect(less).toHaveAttribute("aria-expanded", "true");

      await user.click(less);
      expect(await screen.findByRole("button", { name: "Читать полностью" })).toBeInTheDocument();
    });

    it("offers nothing to expand when the description fits", async () => {
      start(deathNote());
      renderTitle();

      expect(await screen.findByRole("heading", { level: 2, name: "Об аниме" })).toBeInTheDocument();
      expect(screen.queryByRole("button", { name: "Читать полностью" })).not.toBeInTheDocument();
    });

    it("shows the description exactly as the API client left it", async () => {
      // Task 5 already cleaned it; a second pass would eat these brackets and decode &lt; again.
      start(deathNote({ description: "Формула: a &lt; b, [сноска] и [[ссылка]]" }));
      renderTitle();

      expect(await screen.findByText("Формула: a &lt; b, [сноска] и [[ссылка]]")).toBeInTheDocument();
    });

    it("leaves the section out when there is no description", async () => {
      start(deathNote({ description: null }));
      renderTitle();

      expect(await screen.findByRole("heading", { level: 1, name: "Тетрадь смерти" })).toBeInTheDocument();
      expect(screen.queryByRole("heading", { level: 2, name: "Об аниме" })).not.toBeInTheDocument();
    });
  });
});
