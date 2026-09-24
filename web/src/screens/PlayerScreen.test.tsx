// The player screen over the real PlayerController, Library and ProgressStore. Kodik, AniSkip and the
// engine are fakes, and the <video> is jsdom's: its play/pause/load are spied (jsdom implements none of
// them), and every media event is one the test fires by hand. Vectors: Task 6 of
// docs/superpowers/plans/2026-09-24-kaeru-web-03-player.md; Android ui/mobile/player/PlayerScreen.kt,
// PlayerControls.kt, ui/common/player/PlayerFailure.kt.
import { act, fireEvent, render, screen, waitFor, within } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { useEffect } from "react";
import { MemoryRouter, Route, Routes, useLocation, useNavigate, useNavigationType } from "react-router-dom";
import { afterEach, beforeEach, describe, expect, it, vi, type MockInstance } from "vitest";
import { ApiError } from "../api/http";
import type { Shikimori } from "../api/shikimori";
import { ServicesContext, type Services } from "../app/services";
import type { authorized } from "../auth/session";
import type { Anime, ListStatus, UserRate } from "../domain/models";
import { Library } from "../library/library";
import { setSkipEnding } from "../library/prefs";
import { ProgressStore } from "../library/progress";
import type { AniSkip } from "../player/aniskip";
import type { Engine, EngineFactory } from "../player/engine";
import type { EngineFailureKind } from "../player/errors";
import { KodikError, type Kodik, type KodikStream, type Translation } from "../player/kodik";
import { CONTROLS_HIDE_MS, NO_MARKS, type SkipMarks } from "../player/rules";
import { memoryStorage } from "../test/fakes";
import { ToastProvider } from "../ui/Toast";
import { PlayerScreen } from "./PlayerScreen";

const ANIME = 52991;
/** 24 minutes: 90 % is 21:36, the last 10 s start at 23:50. */
const DUR = 1_440_000;
const TITLE = "Провожающая в последний путь Фрирен";

const ANILIBRIA: Translation = { id: 610, title: "AniLibria.TV", type: "voice", episodesCount: 12 };
/** Six episodes in: the seventh is not there yet. */
const ANIDUB: Translation = { id: 609, title: "AniDUB", type: "voice", episodesCount: 6 };
const CRUNCHYROLL: Translation = { id: 77, title: "Crunchyroll", type: "subtitles", episodesCount: 12 };

function frieren(overrides: Partial<Anime> = {}): Anime {
  return {
    id: ANIME,
    title: TITLE,
    originalTitle: "Sousou no Frieren",
    posterUrl: "https://shikimori.io/uploads/poster/animes/52991/poster.jpeg",
    backdropUrl: null,
    status: "ongoing",
    episodes: 28,
    episodesAired: 12,
    year: 2023,
    score: 9.1,
    kind: "tv",
    studios: ["Madhouse"],
    description: null,
    nextEpisodeAt: null,
    ...overrides,
  };
}

function link(track: number, episode: number, quality: number): string {
  return `https://cdn.test/${track}/${episode}/${quality}.m3u8`;
}

class FakeKodik implements Kodik {
  tracks: Translation[] = [ANILIBRIA, ANIDUB, CRUNCHYROLL];
  /** How many times the dubs were listed: once per open. */
  listed = 0;
  /** Every resolve, as "track:episode". */
  readonly asked: string[] = [];
  /** "track:episode" → what that resolve throws. */
  readonly failures = new Map<string, unknown>();

  translations = async (): Promise<Translation[]> => {
    this.listed += 1;
    return this.tracks.map((track) => ({ ...track }));
  };

  resolve = async (_animeId: number, translationId: number, episode: number): Promise<KodikStream> => {
    const key = `${translationId}:${episode}`;
    this.asked.push(key);
    const failure = this.failures.get(key);
    if (failure) throw failure;
    return { translationId, links: [720, 480, 360].map((quality) => ({ quality, url: link(translationId, episode, quality) })) };
  };

  missingEverywhere(episode: number): void {
    for (const track of this.tracks) this.failures.set(`${track.id}:${episode}`, new KodikError("episode"));
  }
}

class FakeAniSkip implements AniSkip {
  found: SkipMarks = NO_MARKS;
  marks = async (): Promise<SkipMarks> => this.found;
}

/** The engine factory the screen hands its <video> to; every element and load is kept. */
class FakeEngines {
  readonly videos: HTMLVideoElement[] = [];
  readonly loads: { url: string; startMs: number }[] = [];
  destroyed = 0;
  private report: (kind: EngineFailureKind) => void = () => undefined;

  readonly factory: EngineFactory = (video, onFailure): Engine => {
    this.videos.push(video);
    this.report = onFailure;
    return {
      load: async (url, startMs) => {
        this.loads.push({ url, startMs });
      },
      destroy: () => {
        this.destroyed += 1;
      },
    };
  };

  fail(kind: EngineFailureKind): void {
    act(() => this.report(kind));
  }
}

function fieldsText(fields: { status?: ListStatus; episodes?: number }): string {
  return Object.entries(fields)
    .map(([name, value]) => `${name}=${String(value)}`)
    .join(" ");
}

class FakeShikimori {
  readonly calls: string[] = [];
  rates: UserRate[] = [{ id: 1, animeId: ANIME, status: "watching", episodes: 6, updatedAt: 1 }];
  details: Anime = frieren();
  private nextId = 900;

  writes(): string[] {
    return this.calls.filter((call) => call.startsWith("POST") || call.startsWith("PATCH"));
  }

  api(): Shikimori {
    const unused = async (): Promise<never> => {
      throw new Error("not used by the player");
    };
    return {
      whoami: unused,
      search: unused,
      popularNow: unused,
      popularInSeason: unused,
      details: async () => this.details,
      byIds: async (ids) => (ids.includes(this.details.id) ? [this.details] : []),
      userRates: async () => this.rates.map((rate) => ({ ...rate })),
      createRate: async (_token, userId, animeId, fields) => {
        this.calls.push(`POST ${userId}/${animeId} ${fieldsText(fields)}`);
        const rate: UserRate = { id: this.nextId++, animeId, status: fields.status, episodes: fields.episodes ?? 0, updatedAt: 5 };
        this.rates.push(rate);
        return { ...rate };
      },
      updateRate: async (_token, rateId, fields) => {
        this.calls.push(`PATCH ${rateId} ${fieldsText(fields)}`);
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

let kodik: FakeKodik;
let aniskip: FakeAniSkip;
let engines: FakeEngines;
let server: FakeShikimori;
let progress: ProgressStore;
let play: MockInstance<() => Promise<void>>;
let pause: MockInstance<() => void>;

beforeEach(() => {
  kodik = new FakeKodik();
  aniskip = new FakeAniSkip();
  engines = new FakeEngines();
  server = new FakeShikimori();
  progress = new ProgressStore(memoryStorage());
  play = vi.spyOn(HTMLMediaElement.prototype, "play").mockResolvedValue(undefined);
  pause = vi.spyOn(HTMLMediaElement.prototype, "pause").mockImplementation(() => undefined);
  vi.spyOn(HTMLMediaElement.prototype, "load").mockImplementation(() => undefined);
});

afterEach(() => {
  vi.useRealTimers();
  vi.restoreAllMocks();
  vi.unstubAllGlobals();
  delete (navigator as unknown as { mediaSession?: unknown }).mediaSession;
  for (const name of ["fullscreenEnabled", "fullscreenElement", "exitFullscreen"]) {
    delete (document as unknown as Record<string, unknown>)[name];
  }
  delete (HTMLElement.prototype as unknown as Record<string, unknown>).requestFullscreen;
  window.history.replaceState(null, "");
});

/**
 * Element fullscreen as a desktop browser has it (jsdom has none): the element asked becomes
 * document.fullscreenElement, and the document says so with fullscreenchange. afterEach removes it.
 */
function stubFullscreen() {
  const request = vi.fn(function (this: Element) {
    Object.defineProperty(document, "fullscreenElement", { configurable: true, value: this });
    document.dispatchEvent(new Event("fullscreenchange"));
    return Promise.resolve();
  });
  const exit = vi.fn(() => {
    Object.defineProperty(document, "fullscreenElement", { configurable: true, value: null });
    document.dispatchEvent(new Event("fullscreenchange"));
    return Promise.resolve();
  });
  Object.defineProperty(document, "fullscreenEnabled", { configurable: true, value: true });
  Object.defineProperty(document, "exitFullscreen", { configurable: true, value: exit });
  Object.defineProperty(HTMLElement.prototype, "requestFullscreen", { configurable: true, value: request });
  return { request, exit };
}

function pressF(): void {
  act(() => {
    fireEvent.keyDown(document.body, { code: "KeyF", key: "а" });
  });
}

function services(): Services {
  const shikimori = server.api();
  const library = new Library({ shikimori, authorized: fakeAuthorized, accountId: () => 42, progress });
  return { shikimori, library, progress, kodik, aniskip, engine: engines.factory };
}

/** AuthCallbackScreen once Shikimori has signed the viewer in: the address they came for, replaced. */
function SignedIn() {
  const navigate = useNavigate();
  useEffect(() => {
    void navigate(`/watch/${ANIME}/7`, { replace: true });
  }, [navigate]);
  return null;
}

/** Where the router is, and how it got there: a replaced episode leaves no history entry behind. */
function Where() {
  const location = useLocation();
  const type = useNavigationType();
  return <p data-testid="where">{`${location.pathname} ${type}`}</p>;
}

function renderPlayer(entries: string[] = [`/watch/${ANIME}/7`]) {
  return render(
    <ServicesContext.Provider value={services()}>
      <ToastProvider>
        <MemoryRouter initialEntries={entries} initialIndex={entries.length - 1}>
          <Routes>
            <Route path="/watch/:id/:episode" element={<PlayerScreen />} />
            <Route path="/anime/:id" element={<p>Страница тайтла</p>} />
            <Route path="/auth" element={<SignedIn />} />
          </Routes>
          <Where />
        </MemoryRouter>
      </ToastProvider>
    </ServicesContext.Provider>,
  );
}

function video(): HTMLVideoElement {
  const element = document.querySelector("video");
  if (element === null) throw new Error("no <video>");
  return element;
}

/** The element reporting `ms` into an episode of `duration`, as a timeupdate does. */
function at(ms: number, duration = DUR): void {
  const element = video();
  Object.defineProperty(element, "duration", { configurable: true, value: duration / 1_000 });
  element.currentTime = ms / 1_000;
  fireEvent.timeUpdate(element);
}

/** Opened: the dubs listed, the stream handed to the engine and the first frame on screen. */
async function playing(entries?: string[]) {
  const view = renderPlayer(entries);
  await waitFor(() => expect(engines.loads).toHaveLength(1));
  await waitFor(() => expect(play).toHaveBeenCalled());
  fireEvent.playing(video());
  return view;
}

function savedAt(episode: number): number | undefined {
  return progress.of(ANIME).find((row) => row.episode === episode)?.positionMs;
}

describe("PlayerScreen", () => {
  it("names the episode and offers the dubs and qualities once it has loaded", async () => {
    const user = userEvent.setup();
    await playing();

    expect(screen.getByRole("heading", { name: TITLE })).toBeInTheDocument();
    expect(screen.getByText("7 серия")).toBeInTheDocument();
    expect(screen.getByRole("button", { name: "Качество: 720p" })).toBeInTheDocument();

    await user.click(screen.getByRole("button", { name: "Озвучка: AniLibria.TV" }));
    const menu = screen.getByRole("menu");
    expect(within(menu).getAllByRole("menuitemradio").map((item) => item.getAttribute("aria-checked"))).toEqual([
      "true",
      "false",
      "false",
    ]);
    expect(within(menu).getByRole("menuitemradio", { name: "AniLibria.TV" })).toBeEnabled();
    const lacking = within(menu).getByRole("menuitemradio", { name: "AniDUB" });
    expect(lacking).toBeDisabled();
    expect(lacking).toHaveAccessibleDescription("нет серии 7");
    expect(within(menu).getByRole("menuitemradio", { name: "Crunchyroll" })).toHaveAccessibleDescription("Субтитры");
  });

  it("plays another dub at the same position when one is picked", async () => {
    const user = userEvent.setup();
    await playing();
    at(600_000);

    await user.click(screen.getByRole("button", { name: "Озвучка: AniLibria.TV" }));
    await user.click(screen.getByRole("menuitemradio", { name: "Crunchyroll" }));

    await waitFor(() => expect(engines.loads.at(-1)).toEqual({ url: link(77, 7, 720), startMs: 600_000 }));
    expect(screen.getByRole("button", { name: "Озвучка: Crunchyroll" })).toBeInTheDocument();
  });

  it("pauses on «Пауза» and plays on «Продолжить»", async () => {
    const user = userEvent.setup();
    await playing();
    const calls = play.mock.calls.length;

    await user.click(screen.getByRole("button", { name: "Пауза" }));
    expect(pause).toHaveBeenCalled();
    fireEvent.pause(video());

    await user.click(screen.getByRole("button", { name: "Продолжить" }));
    expect(play).toHaveBeenCalledTimes(calls + 1);
  });

  it("asks for a press when the browser refuses to autoplay, and that press plays (Review Focus 4)", async () => {
    const user = userEvent.setup();
    play.mockRejectedValueOnce(new DOMException("play() failed because the user didn't interact", "NotAllowedError"));
    renderPlayer();

    const watch = await screen.findByRole("button", { name: "Смотреть" });
    expect(screen.queryByRole("alert")).toBeNull();

    await user.click(watch);
    expect(play).toHaveBeenCalledTimes(2);
    fireEvent.playing(video());
    await waitFor(() => expect(screen.queryByRole("button", { name: "Смотреть" })).toBeNull());
    expect(screen.getByRole("button", { name: "Пауза" })).toBeInTheDocument();
  });

  it("says nothing when a newer source interrupts play (Review Focus 4)", async () => {
    play.mockRejectedValueOnce(new DOMException("The play() request was interrupted by a new load request", "AbortError"));
    renderPlayer();
    await waitFor(() => expect(play).toHaveBeenCalled());
    await act(async () => undefined);

    expect(screen.queryByRole("button", { name: "Смотреть" })).toBeNull();
    expect(screen.queryByRole("alert")).toBeNull();
  });

  it("reloads the other quality at the current position", async () => {
    const user = userEvent.setup();
    await playing();
    at(600_000);

    await user.click(screen.getByRole("button", { name: "Качество: 720p" }));
    await user.click(screen.getByRole("menuitemradio", { name: "480p" }));

    await waitFor(() => expect(engines.loads.at(-1)).toEqual({ url: link(610, 7, 480), startMs: 600_000 }));
    expect(screen.getByRole("button", { name: "Качество: 480p" })).toBeInTheDocument();
  });

  it("counts down the last 10 s to the next episode, and «Отмена» stops it", async () => {
    const user = userEvent.setup();
    await playing();

    at(DUR - 10_000);

    expect(screen.getByText("Следующая серия через 10")).toBeInTheDocument();
    expect(screen.getByText("8 серия")).toBeInTheDocument();
    expect(screen.getByRole("button", { name: "Смотреть сейчас" })).toHaveFocus();

    at(DUR - 9_200);
    expect(screen.getByText("Следующая серия через 10")).toBeInTheDocument();
    at(DUR - 3_000);
    expect(screen.getByText("Следующая серия через 3")).toBeInTheDocument();

    await user.click(screen.getByRole("button", { name: "Отмена" }));

    expect(screen.queryByText(/Следующая серия через/)).toBeNull();
    expect(screen.getByRole("button", { name: "Следующая серия" })).toBeInTheDocument();
    at(DUR - 1_000);
    expect(screen.queryByText(/Следующая серия через/)).toBeNull();
    expect(engines.loads).toHaveLength(1);
  });

  it("says when no dub has the episode, with «Повторить» and a way back to the episodes", async () => {
    kodik.missingEverywhere(7);
    renderPlayer();

    const alert = await screen.findByRole("alert");
    expect(alert).toHaveTextContent("Серия 7 пока не вышла ни в одной озвучке");
    expect(within(alert).getByRole("button", { name: "Повторить" })).toBeInTheDocument();
    expect(within(alert).getByRole("link", { name: "К списку серий" })).toHaveAttribute("href", `/anime/${ANIME}`);
    expect(within(alert).queryByRole("button", { name: "Сменить озвучку" })).toBeNull();
    // The top bar stays; the bottom bar goes.
    expect(screen.getByRole("button", { name: "Назад" })).toBeInTheDocument();
    expect(screen.queryByRole("slider", { name: "Перемотка" })).toBeNull();
    expect(engines.loads).toHaveLength(0);
  });

  it("offers another dub when the source failed, and plays again on «Повторить»", async () => {
    const user = userEvent.setup();
    kodik.failures.set("610:7", new KodikError("upstream"));
    renderPlayer();

    const alert = await screen.findByRole("alert");
    expect(alert).toHaveTextContent("Kodik временно недоступен, попробуйте позже");
    await user.click(within(alert).getByRole("button", { name: "Сменить озвучку" }));
    expect(screen.getByRole("menu", { name: "Озвучка" })).toBeInTheDocument();
    expect(screen.getByRole("menuitemradio", { name: "AniLibria.TV" })).toBeInTheDocument();
    await user.keyboard("{Escape}");

    kodik.failures.clear();
    await user.click(within(screen.getByRole("alert")).getByRole("button", { name: "Повторить" }));

    await waitFor(() => expect(engines.loads).toEqual([{ url: link(610, 7, 720), startMs: 0 }]));
    expect(screen.queryByRole("alert")).toBeNull();
    expect(screen.getByRole("slider", { name: "Перемотка" })).toBeInTheDocument();
  });

  it("hands engine failures to the controller", async () => {
    await playing();
    at(600_000);

    // The first refused link is resolved again behind the viewer's back (Review Focus 3).
    engines.fail("network");
    await waitFor(() => expect(kodik.asked).toEqual(["610:7", "610:7"]));
    expect(screen.queryByRole("alert")).toBeNull();

    engines.fail("offline");
    expect(await screen.findByRole("alert")).toHaveTextContent("Нет соединения. Проверьте интернет");
  });

  it("offers to complete the title after its last episode, and completes it on «Да»", async () => {
    const user = userEvent.setup();
    server.details = frieren({ status: "released", episodes: 12, episodesAired: 12 });
    server.rates = [{ id: 1, animeId: ANIME, status: "watching", episodes: 11, updatedAt: 1 }];
    await playing([`/watch/${ANIME}/12`]);

    at(1_300_000);

    const dialog = await screen.findByRole("dialog", { name: `Перевести «${TITLE}» в завершённые?` });
    expect(dialog).toHaveAccessibleDescription("Серия была последней из вышедших.");
    await user.click(within(dialog).getByRole("button", { name: "Да" }));

    await waitFor(() => expect(server.writes()).toEqual(["PATCH 1 episodes=12", "PATCH 1 status=completed"]));
    expect(screen.queryByRole("dialog")).toBeNull();
  });

  it("goes back to the title when the ending of the last aired episode skips itself", async () => {
    setSkipEnding(true);
    server.rates = [{ id: 1, animeId: ANIME, status: "watching", episodes: 11, updatedAt: 1 }];
    aniskip.found = { opening: null, ending: { startMs: 1_290_000, endMs: 1_380_000 } };
    await playing([`/anime/${ANIME}`, `/watch/${ANIME}/12`]);

    at(1_295_000);
    await act(async () => undefined);
    at(1_300_000);

    expect(await screen.findByText("Страница тайтла")).toBeInTheDocument();
    expect(screen.getByTestId("where")).toHaveTextContent(`/anime/${ANIME} REPLACE`);
    expect(savedAt(12)).toBe(DUR);
  });

  it("asks about completing the title before leaving when the ending of its last episode skips itself", async () => {
    const user = userEvent.setup();
    setSkipEnding(true);
    server.details = frieren({ status: "released", episodes: 12, episodesAired: 12 });
    server.rates = [{ id: 1, animeId: ANIME, status: "watching", episodes: 11, updatedAt: 1 }];
    aniskip.found = { opening: null, ending: { startMs: 1_290_000, endMs: 1_380_000 } };
    await playing([`/anime/${ANIME}`, `/watch/${ANIME}/12`]);

    at(1_295_000);
    await act(async () => undefined);
    at(1_300_000);

    const dialog = await screen.findByRole("dialog", { name: `Перевести «${TITLE}» в завершённые?` });
    await user.click(within(dialog).getByRole("button", { name: "Позже" }));

    expect(await screen.findByText("Страница тайтла")).toBeInTheDocument();
    expect(server.writes()).toEqual(["PATCH 1 episodes=12"]);
  });

  it("skips the opening to its end", async () => {
    const user = userEvent.setup();
    aniskip.found = { opening: { startMs: 60_000, endMs: 150_000 }, ending: null };
    await playing();
    at(59_000);
    await act(async () => undefined);

    at(61_000);
    await user.click(screen.getByRole("button", { name: "Пропустить опенинг" }));

    expect(video().currentTime).toBe(150);
  });

  it("seeks 10 s with ArrowRight and opens the next episode with N in any layout", async () => {
    await playing();
    at(600_000);

    fireEvent.keyDown(document.body, { code: "ArrowRight", key: "ArrowRight" });
    expect(video().currentTime).toBe(610);

    fireEvent.keyDown(document.body, { code: "KeyN", key: "т" });
    await waitFor(() => expect(screen.getByTestId("where")).toHaveTextContent(`/watch/${ANIME}/8 REPLACE`));
    expect(engines.loads.at(-1)).toEqual({ url: link(610, 8, 720), startMs: 0 });
  });

  it("previews a drag on the seek slider and seeks when it is let go", async () => {
    await playing();
    at(600_000);
    const slider = screen.getByRole("slider", { name: "Перемотка" });
    expect(slider).toHaveAttribute("aria-valuetext", "10:00 из 24:00");

    fireEvent.input(slider, { target: { value: "700" } });
    expect(screen.getByText("11:40 / 24:00")).toBeInTheDocument();
    expect(video().currentTime).toBe(600);

    fireEvent.change(slider, { target: { value: "700" } });
    expect(video().currentTime).toBe(700);
    fireEvent.seeked(video());
    at(701_000);
    expect(screen.getByText("11:41 / 24:00")).toBeInTheDocument();
  });

  it("drops a drag on the seek slider that the next episode cut short", async () => {
    await playing();
    at(600_000);
    fireEvent.input(screen.getByRole("slider", { name: "Перемотка" }), { target: { value: "700" } });
    expect(screen.getByText("11:40 / 24:00")).toBeInTheDocument();

    fireEvent.click(screen.getByRole("button", { name: "Следующая серия" }));
    await waitFor(() => expect(engines.loads).toHaveLength(2));
    fireEvent.playing(video());
    at(5_000);

    expect(screen.getByText("0:05 / 24:00")).toBeInTheDocument();
  });

  it("drops a drag on the seek slider that a failure cut short", async () => {
    const user = userEvent.setup();
    await playing();
    at(600_000);
    fireEvent.input(screen.getByRole("slider", { name: "Перемотка" }), { target: { value: "700" } });

    engines.fail("offline");
    await user.click(within(await screen.findByRole("alert")).getByRole("button", { name: "Повторить" }));
    await waitFor(() => expect(engines.loads).toHaveLength(2));

    expect(screen.getByText("10:00 / 24:00")).toBeInTheDocument();
  });

  it("keeps the keyboard on «Пауза» while the picture buffers", async () => {
    await playing();
    const toggle = screen.getByRole("button", { name: "Пауза" });
    toggle.focus();

    fireEvent.waiting(video());

    expect(screen.getByText("Загружаем")).toBeInTheDocument();
    expect(toggle).toHaveFocus();
  });

  it("hands the keyboard to «Пауза» when «Отмена» closes the countdown", async () => {
    const user = userEvent.setup();
    await playing();
    at(DUR - 10_000);

    await user.click(screen.getByRole("button", { name: "Отмена" }));

    expect(screen.getByRole("button", { name: "Пауза" })).toHaveFocus();
  });

  it("hands the keyboard to «Пауза» once «Повторить» has brought the episode back", async () => {
    const user = userEvent.setup();
    kodik.failures.set("610:7", new KodikError("upstream"));
    renderPlayer();
    const alert = await screen.findByRole("alert");
    kodik.failures.clear();

    await user.click(within(alert).getByRole("button", { name: "Повторить" }));
    await waitFor(() => expect(engines.loads).toHaveLength(1));
    fireEvent.playing(video());

    expect(await screen.findByRole("button", { name: "Пауза" })).toHaveFocus();
  });

  it("leaves the keyboard in an open menu when the countdown comes up", async () => {
    const user = userEvent.setup();
    await playing();
    await user.click(screen.getByRole("button", { name: "Качество: 720p" }));
    const item = screen.getByRole("menuitemradio", { name: "720p" });
    expect(item).toHaveFocus();

    at(DUR - 10_000);

    expect(screen.getByText("Следующая серия через 10")).toBeInTheDocument();
    expect(item).toHaveFocus();
    await user.keyboard("{Escape}");
    expect(screen.queryByRole("menu")).toBeNull();
  });

  it("mutes and unmutes with M in any layout and with its button", async () => {
    const user = userEvent.setup();
    await playing();

    fireEvent.keyDown(document.body, { code: "KeyM", key: "ь" });
    expect(video().muted).toBe(true);
    await user.click(screen.getByRole("button", { name: "Включить звук" }));
    expect(video().muted).toBe(false);
    expect(screen.getByRole("button", { name: "Выключить звук" })).toBeInTheDocument();
  });

  it("says the next episode has not aired yet at the end of the last aired one", async () => {
    await playing([`/watch/${ANIME}/12`]);

    at(DUR - 20_000);

    expect(screen.getByText("Ждём 13 серию")).toBeInTheDocument();
    expect(screen.getByText("Пока это последняя вышедшая серия")).toBeInTheDocument();
    expect(screen.queryByRole("button", { name: "Следующая серия" })).toBeNull();
  });

  it("puts the page in fullscreen with F, and follows the browser out of it", async () => {
    const { request } = stubFullscreen();
    await playing();
    expect(screen.getByRole("button", { name: "Во весь экран" })).toBeInTheDocument();

    pressF();

    expect(request).toHaveBeenCalledTimes(1);
    // The page, not <main>: the completion question and the notices are drawn outside the player.
    expect(request.mock.contexts[0]).toBe(document.documentElement);
    expect(screen.getByRole("button", { name: "Выйти из полноэкранного режима" })).toBeInTheDocument();

    // Esc is the browser's: the player only hears that fullscreen ended.
    Object.defineProperty(document, "fullscreenElement", { configurable: true, value: null });
    fireEvent(document, new Event("fullscreenchange"));
    expect(screen.getByRole("button", { name: "Во весь экран" })).toBeInTheDocument();
  });

  it("asks about completing the title where fullscreen shows it", async () => {
    stubFullscreen();
    server.details = frieren({ status: "released", episodes: 12, episodesAired: 12 });
    server.rates = [{ id: 1, animeId: ANIME, status: "watching", episodes: 11, updatedAt: 1 }];
    await playing([`/watch/${ANIME}/12`]);
    pressF();

    at(1_300_000);

    const dialog = await screen.findByRole("dialog", { name: `Перевести «${TITLE}» в завершённые?` });
    expect(document.fullscreenElement).not.toBeNull();
    expect(document.fullscreenElement?.contains(dialog)).toBe(true);
  });

  it("shows its notices where fullscreen shows them", async () => {
    stubFullscreen();
    kodik.missingEverywhere(8);
    await playing();
    pressF();

    fireEvent.keyDown(document.body, { code: "KeyN", key: "т" });

    const notice = await screen.findByText("Серия 8 пока не вышла ни в одной озвучке");
    expect(document.fullscreenElement).not.toBeNull();
    expect(document.fullscreenElement?.contains(notice)).toBe(true);
  });

  it("takes the page out of fullscreen when the player closes", async () => {
    const { exit } = stubFullscreen();
    const view = await playing();
    pressF();

    view.unmount();

    expect(exit).toHaveBeenCalledTimes(1);
    expect(document.fullscreenElement).toBeNull();
  });

  it("leaves a page that is not in fullscreen alone when the player closes", async () => {
    const { exit } = stubFullscreen();
    const view = await playing();

    view.unmount();

    expect(exit).not.toHaveBeenCalled();
  });

  it("offers picture-in-picture where the browser has it", async () => {
    const user = userEvent.setup();
    const request = vi.fn(() => Promise.resolve({} as PictureInPictureWindow));
    Object.defineProperty(document, "pictureInPictureEnabled", { configurable: true, value: true });
    Object.defineProperty(HTMLVideoElement.prototype, "requestPictureInPicture", { configurable: true, value: request });
    try {
      await playing();

      await user.click(screen.getByRole("button", { name: "Картинка в картинке" }));

      expect(request).toHaveBeenCalledTimes(1);
      fireEvent(video(), new Event("enterpictureinpicture"));
      expect(screen.getByRole("button", { name: "Картинка в картинке" })).toHaveAttribute("aria-pressed", "true");
    } finally {
      delete (document as unknown as Record<string, unknown>).pictureInPictureEnabled;
      delete (HTMLVideoElement.prototype as unknown as Record<string, unknown>).requestPictureInPicture;
    }
  });

  it("keeps the same <video> and replaces the address for the next episode", async () => {
    const user = userEvent.setup();
    await playing();
    const element = video();
    at(600_000);

    await user.click(screen.getByRole("button", { name: "Следующая серия" }));

    await waitFor(() => expect(screen.getByTestId("where")).toHaveTextContent(`/watch/${ANIME}/8 REPLACE`));
    expect(video()).toBe(element);
    expect(engines.videos).toEqual([element]);
    expect(engines.loads.at(-1)).toEqual({ url: link(610, 8, 720), startMs: 0 });
    // The address followed the controller; nothing opened the episode a second time.
    expect(kodik.listed).toBe(1);
    expect(kodik.asked).toEqual(["610:7", "610:8"]);
    expect(screen.getByText("8 серия")).toBeInTheDocument();
  });

  it("goes back to the title from a deep link", async () => {
    const user = userEvent.setup();
    await playing();

    await user.click(screen.getByRole("button", { name: "Назад" }));

    expect(screen.getByText("Страница тайтла")).toBeInTheDocument();
  });

  it("goes to the title, not back out of the site, from a deep link that went through sign-in", async () => {
    const user = userEvent.setup();
    await playing(["/auth"]);

    await user.click(screen.getByRole("button", { name: "Назад" }));

    expect(screen.getByText("Страница тайтла")).toBeInTheDocument();
  });

  it("goes back through history when a page of the site opened it", async () => {
    const user = userEvent.setup();
    // What BrowserRouter keeps in history.state: the player is the second entry of this visit.
    window.history.replaceState({ idx: 1 }, "");
    await playing([`/anime/${ANIME}`, `/watch/${ANIME}/7`]);

    await user.click(screen.getByRole("button", { name: "Назад" }));

    expect(screen.getByTestId("where")).toHaveTextContent(`/anime/${ANIME} POP`);
  });

  it("saves the position when the page is hidden and when the player closes", async () => {
    const view = await playing();
    at(600_000);
    at(603_000);
    expect(savedAt(7)).toBe(600_000);

    fireEvent(window, new Event("pagehide"));
    expect(savedAt(7)).toBe(603_000);

    at(604_000);
    fireEvent(document, new Event("visibilitychange"));
    expect(savedAt(7)).toBe(603_000);
    Object.defineProperty(document, "visibilityState", { configurable: true, get: () => "hidden" });
    try {
      fireEvent(document, new Event("visibilitychange"));
    } finally {
      delete (document as unknown as { visibilityState?: unknown }).visibilityState;
    }
    expect(savedAt(7)).toBe(604_000);

    at(606_000);
    view.unmount();
    expect(savedAt(7)).toBe(606_000);
    expect(engines.destroyed).toBe(1);
  });

  it("marks the page as the player and names the episode in the tab while it is open", async () => {
    document.title = "Kaeru";
    const view = await playing();

    expect(document.body).toHaveClass("is-player");
    expect(document.title).toBe(`7 серия — ${TITLE}`);

    view.unmount();
    expect(document.body).not.toHaveClass("is-player");
    expect(document.title).toBe("Kaeru");
  });

  it("plays and pauses on a click on the picture, but a click that only closes a menu does neither", async () => {
    const user = userEvent.setup();
    await playing();
    const surface = document.querySelector(".player-surface") as HTMLElement;

    await user.click(screen.getByRole("button", { name: "Качество: 720p" }));
    await user.click(surface);
    expect(screen.queryByRole("menu")).toBeNull();
    expect(pause).not.toHaveBeenCalled();

    await user.click(surface);
    expect(pause).toHaveBeenCalledTimes(1);
    expect(screen.getByRole("button", { name: "Продолжить" })).toBeInTheDocument();
  });

  it("shows and hides the controls on a tap, as the phone app does, without pausing", async () => {
    const user = userEvent.setup();
    await playing();
    const player = screen.getByRole("main");
    const surface = document.querySelector(".player-surface") as HTMLElement;

    await user.pointer({ keys: "[TouchA]", target: surface });
    expect(player).toHaveAttribute("data-idle", "true");
    await user.pointer({ keys: "[TouchA]", target: surface });
    expect(player).toHaveAttribute("data-idle", "false");
    expect(pause).not.toHaveBeenCalled();
  });

  it("hides the controls after 3 s of playback and shows them on a pointer move, never while paused", async () => {
    vi.useFakeTimers();
    const user = userEvent.setup({ advanceTimers: (ms) => vi.advanceTimersByTime(ms) });
    await playing();
    const player = screen.getByRole("main");
    fireEvent.pointerMove(player);

    act(() => vi.advanceTimersByTime(CONTROLS_HIDE_MS - 1));
    expect(player).toHaveAttribute("data-idle", "false");
    act(() => vi.advanceTimersByTime(1));
    expect(player).toHaveAttribute("data-idle", "true");

    fireEvent.pointerMove(player);
    expect(player).toHaveAttribute("data-idle", "false");
    act(() => vi.advanceTimersByTime(CONTROLS_HIDE_MS));
    expect(player).toHaveAttribute("data-idle", "true");

    fireEvent.keyDown(document.body, { code: "KeyQ", key: "й" });
    expect(player).toHaveAttribute("data-idle", "false");
    await user.click(screen.getByRole("button", { name: "Пауза" }));
    act(() => vi.advanceTimersByTime(CONTROLS_HIDE_MS * 2));
    expect(player).toHaveAttribute("data-idle", "false");
  });

  it("keeps the controls up while taps land on them, though a touch screen does not focus a tapped button", async () => {
    vi.useFakeTimers();
    const user = userEvent.setup({ advanceTimers: (ms) => vi.advanceTimersByTime(ms) });
    await playing();
    const player = screen.getByRole("main");
    const surface = document.querySelector(".player-surface") as HTMLElement;
    act(() => vi.advanceTimersByTime(CONTROLS_HIDE_MS));
    expect(player).toHaveAttribute("data-idle", "true");

    await user.pointer({ keys: "[TouchA]", target: surface });
    expect(player).toHaveAttribute("data-idle", "false");
    const forward = screen.getByRole("button", { name: "Вперёд на 10 секунд" });
    act(() => vi.advanceTimersByTime(2_000));
    await user.pointer({ keys: "[TouchA]", target: forward });
    act(() => vi.advanceTimersByTime(2_000));
    await user.pointer({ keys: "[TouchA]", target: forward });
    act(() => vi.advanceTimersByTime(2_000));

    expect(player).toHaveAttribute("data-idle", "false");
  });

  it("hands the system media controls the episode, and takes them back on close", async () => {
    const handlers = new Map<string, ((details: MediaSessionActionDetails) => void) | null>();
    const session = {
      metadata: null as unknown,
      setActionHandler: (action: string, handler: ((details: MediaSessionActionDetails) => void) | null) => {
        handlers.set(action, handler);
      },
      setPositionState: () => undefined,
    };
    Object.defineProperty(navigator, "mediaSession", { configurable: true, value: session });
    vi.stubGlobal(
      "MediaMetadata",
      class {
        readonly init: MediaMetadataInit;
        constructor(init: MediaMetadataInit) {
          this.init = init;
        }
      },
    );
    const view = await playing();
    at(600_000);

    expect((session.metadata as { init: MediaMetadataInit }).init).toMatchObject({
      title: "7 серия",
      artist: TITLE,
      album: "AniLibria.TV",
    });
    act(() => handlers.get("seekforward")?.({ action: "seekforward" }));
    expect(video().currentTime).toBe(610);
    expect(handlers.get("nexttrack")).toBeTypeOf("function");

    view.unmount();
    expect(session.metadata).toBeNull();
    expect(handlers.get("play")).toBeNull();
  });
});
