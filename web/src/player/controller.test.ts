// Vectors: Task 5 of docs/superpowers/plans/2026-09-24-kaeru-web-03-player.md and Android
// player/PlaybackController.kt, domain/playback/ResolveEpisodeStream.kt, MarkEpisodeWatched.kt and
// AddStartedTitleToList.kt. The controller runs over a real Library and ProgressStore; Kodik, AniSkip,
// the engine and the <video> are fakes, so every media event is one the test fires by hand.
import { beforeEach, describe, expect, it, vi } from "vitest";
import { ApiError, NetworkError } from "../api/http";
import type { Shikimori } from "../api/shikimori";
import type { authorized } from "../auth/session";
import type { Anime, EpisodeProgress, ListStatus, UserRate } from "../domain/models";
import { Library } from "../library/library";
import { setAutoplayNext, setDefaultQuality, setSkipEnding, setWatchedThreshold } from "../library/prefs";
import { ProgressStore } from "../library/progress";
import type { AniSkip } from "./aniskip";
import { PlayerController, type MediaPort } from "./controller";
import type { Engine } from "./engine";
import { KodikError, type Kodik, type KodikStream, type Translation } from "./kodik";
import { rememberDub, rememberedDub } from "./memory";
import { NO_MARKS, type SkipMarks } from "./rules";

const ANIME = 52991;
/** 24 minutes: 90 % is 21:36, the last 10 s start at 23:50. */
const DUR = 1_440_000;
const NOW = 1_758_700_000_000;

const ANILIBRIA: Translation = { id: 610, title: "AniLibria.TV", type: "voice", episodesCount: 12 };
const ANIDUB: Translation = { id: 609, title: "AniDUB", type: "voice", episodesCount: 12 };
const UNKNOWN_STUDIO: Translation = { id: 1001, title: "Студия Икс", type: "voice", episodesCount: 12 };

function frieren(overrides: Partial<Anime> = {}): Anime {
  return {
    id: ANIME,
    title: "Провожающая в последний путь Фрирен",
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

function deferred(): { promise: Promise<void>; resolve: () => void } {
  let resolve: () => void = () => undefined;
  const promise = new Promise<void>((done) => {
    resolve = done;
  });
  return { promise, resolve };
}

/** Lets every queued promise callback run. */
function settle(): Promise<void> {
  return new Promise((resolve) => setTimeout(resolve, 0));
}

function link(track: number, episode: number, quality: number): string {
  return `https://cdn.test/${track}/${episode}/${quality}.m3u8`;
}

function streamOf(track: number, episode: number): KodikStream {
  return { translationId: track, links: [720, 480, 360].map((quality) => ({ quality, url: link(track, episode, quality) })) };
}

class FakeKodik implements Kodik {
  tracks: Translation[] = [ANILIBRIA, ANIDUB, UNKNOWN_STUDIO];
  tracksFailure: unknown = null;
  /** Every resolve, as "track:episode". */
  readonly asked: string[] = [];
  /** "track:episode" → what that resolve throws. */
  readonly failures = new Map<string, unknown>();
  /** "track:episode" → what that resolve waits for. */
  readonly holds = new Map<string, Promise<void>>();

  translations = async (): Promise<Translation[]> => {
    if (this.tracksFailure) throw this.tracksFailure;
    return this.tracks.map((track) => ({ ...track }));
  };

  resolve = async (_animeId: number, translationId: number, episode: number): Promise<KodikStream> => {
    const key = `${translationId}:${episode}`;
    this.asked.push(key);
    const hold = this.holds.get(key);
    if (hold) await hold;
    const failure = this.failures.get(key);
    if (failure) throw failure;
    return streamOf(translationId, episode);
  };

  /** No dub has this episode: every resolve says so. */
  missingEverywhere(episode: number): void {
    for (const track of this.tracks) this.failures.set(`${track.id}:${episode}`, new KodikError("episode"));
  }
}

class FakeEngine implements Engine {
  readonly loads: { url: string; startMs: number }[] = [];
  destroyed = 0;
  /** While set, a load waits for it, as hls.js arriving over the network would. */
  hold: Promise<void> | null = null;

  load = async (url: string, startMs: number): Promise<void> => {
    this.loads.push({ url, startMs });
    if (this.hold) await this.hold;
  };

  destroy = (): void => {
    this.destroyed += 1;
  };
}

class FakeAniSkip implements AniSkip {
  found: SkipMarks = NO_MARKS;
  readonly asked: string[] = [];

  marks = async (animeId: number, episode: number, durationMs: number): Promise<SkipMarks> => {
    this.asked.push(`${animeId}:${episode}:${durationMs}`);
    return this.found;
  };
}

function fieldsText(fields: { status?: ListStatus; episodes?: number }): string {
  return Object.entries(fields)
    .map(([name, value]) => `${name}=${String(value)}`)
    .join(" ");
}

class FakeShikimori {
  readonly calls: string[] = [];
  rates: UserRate[] = [];
  details: Anime = frieren();
  detailsFailure: unknown = null;
  /** While set, the list read waits for it. */
  ratesHold: Promise<void> | null = null;
  /** While set, the list read throws it. */
  ratesFailure: unknown = null;
  /** One entry per write, in order; a truthy entry is thrown by that write. */
  failures: unknown[] = [];
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
      details: async (id) => {
        this.calls.push(`details ${id}`);
        if (this.detailsFailure) throw this.detailsFailure;
        return this.details;
      },
      byIds: async (ids) => (ids.includes(this.details.id) ? [this.details] : []),
      userRates: async () => {
        if (this.ratesHold) await this.ratesHold;
        if (this.ratesFailure) throw this.ratesFailure;
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

let storage: Storage;
let kodik: FakeKodik;
let engine: FakeEngine;
let aniskip: FakeAniSkip;
let server: FakeShikimori;
let progress: ProgressStore;
let library: Library;
let media: { play: ReturnType<typeof vi.fn<() => Promise<void>>>; pause: ReturnType<typeof vi.fn<() => void>>; seek: ReturnType<typeof vi.fn<(ms: number) => void>> };
let toasts: string[];

beforeEach(() => {
  storage = memoryStorage();
  kodik = new FakeKodik();
  engine = new FakeEngine();
  aniskip = new FakeAniSkip();
  server = new FakeShikimori();
  // In the list, one episode behind the one the tests play.
  server.rates = [{ id: 1, animeId: ANIME, status: "watching", episodes: 6, updatedAt: 1 }];
  progress = new ProgressStore(memoryStorage());
  media = { play: vi.fn(() => Promise.resolve()), pause: vi.fn(), seek: vi.fn() };
  toasts = [];
});

function build(): PlayerController {
  const shikimori = server.api();
  library = new Library({ shikimori, authorized: fakeAuthorized, accountId: () => 42, progress });
  const port: MediaPort = media;
  return new PlayerController({
    kodik,
    aniskip,
    library,
    progress,
    shikimori,
    engine,
    media: port,
    toast: (text) => toasts.push(text),
    now: () => NOW,
    storage,
  });
}

/** Opened, the list read, and the first frame on screen. */
async function playing(episode = 7): Promise<PlayerController> {
  const controller = build();
  await controller.open(ANIME, episode);
  await settle();
  controller.onPlaying();
  return controller;
}

function row(episode: number, positionMs: number): EpisodeProgress {
  return { animeId: ANIME, episode, positionMs, durationMs: DUR, updatedAt: 1 };
}

function saved(episode: number): number | undefined {
  return progress.of(ANIME).find((item) => item.episode === episode)?.positionMs;
}

describe("PlayerController: opening an episode", () => {
  it("opens at the resume position with the remembered dub", async () => {
    rememberDub(ANIME, ANIDUB, storage);
    progress.put(row(7, 860_000));
    const controller = build();

    await controller.open(ANIME, 7);

    expect(kodik.asked).toEqual(["609:7"]);
    expect(engine.loads).toEqual([{ url: link(609, 7, 720), startMs: 860_000 }]);
    expect(media.play).toHaveBeenCalledTimes(1);
    const state = controller.getState();
    expect(state.phase).toBe("playing");
    expect(state.anime?.title).toBe("Провожающая в последний путь Фрирен");
    expect(state.episode).toBe(7);
    expect(state.track).toEqual(ANIDUB);
    expect(state.tracks[0]).toEqual(ANIDUB);
    expect(state.qualities).toEqual([720, 480, 360]);
    expect(state.quality).toBe(720);
    expect(state.positionMs).toBe(860_000);
    expect(state.hasNext).toBe(true);
  });

  it("starts from the top when the saved position is a mis-tap or a finished episode", async () => {
    progress.put(row(7, 20_000));
    const controller = build();
    await controller.open(ANIME, 7);
    progress.put(row(8, 1_400_000));
    await controller.open(ANIME, 8);

    expect(engine.loads.map((load) => load.startMs)).toEqual([0, 0]);
  });

  it("saves the episode it leaves and stops it when another one opens", async () => {
    const controller = await playing();
    controller.onTime(600_000, DUR);
    controller.onTime(603_000, DUR);

    await controller.open(ANIME, 9);

    expect(saved(7)).toBe(603_000);
    expect(media.pause).toHaveBeenCalledTimes(1);
    expect(engine.loads.at(-1)).toEqual({ url: link(610, 9, 720), startMs: 0 });
  });

  it("opens in the default quality when the stream offers it", async () => {
    setDefaultQuality(480, storage);
    const controller = build();

    await controller.open(ANIME, 7);

    expect(engine.loads).toEqual([{ url: link(610, 7, 480), startMs: 0 }]);
    expect(controller.getState().quality).toBe(480);
  });

  it("ranks the dubs: with no memory AniLibria goes before an unknown studio, and becomes the memory", async () => {
    kodik.tracks = [UNKNOWN_STUDIO, ANILIBRIA];
    const controller = build();

    await controller.open(ANIME, 7);

    expect(controller.getState().tracks.map((track) => track.id)).toEqual([610, 1001]);
    expect(kodik.asked).toEqual(["610:7"]);
    expect(rememberedDub(ANIME, storage)).toEqual({ id: 610, title: "AniLibria.TV" });
  });

  it("plays the next dub when the remembered one stops short of the episode, says so and keeps the memory", async () => {
    const shortDub = { ...ANIDUB, episodesCount: 6 };
    kodik.tracks = [ANILIBRIA, shortDub, UNKNOWN_STUDIO];
    rememberDub(ANIME, shortDub, storage);
    const controller = build();

    await controller.open(ANIME, 7);

    // Its count already says no: it is not asked.
    expect(kodik.asked).toEqual(["610:7"]);
    expect(controller.getState().track).toEqual(ANILIBRIA);
    expect(toasts).toEqual(["В озвучке AniDUB серии 7 нет — включена AniLibria.TV"]);
    expect(rememberedDub(ANIME, storage)).toEqual({ id: 609, title: "AniDUB" });
  });

  it("walks on when Kodik says the remembered dub lacks the episode", async () => {
    const uncounted = { ...ANIDUB, episodesCount: null };
    rememberDub(ANIME, uncounted, storage);
    kodik.tracks = [ANILIBRIA, uncounted];
    kodik.failures.set("609:7", new KodikError("episode"));
    const controller = build();

    await controller.open(ANIME, 7);

    expect(kodik.asked).toEqual(["609:7", "610:7"]);
    expect(engine.loads).toEqual([{ url: link(610, 7, 720), startMs: 0 }]);
    expect(toasts).toEqual(["В озвучке AniDUB серии 7 нет — включена AniLibria.TV"]);
    expect(rememberedDub(ANIME, storage)?.id).toBe(609);
  });

  it("adopts a stand-in without a word when nothing was remembered (ResolveEpisodeStream.adopted)", async () => {
    kodik.tracks = [ANILIBRIA, ANIDUB];
    kodik.failures.set("610:7", new KodikError("episode"));
    const controller = build();

    await controller.open(ANIME, 7);

    expect(controller.getState().track).toEqual(ANIDUB);
    expect(toasts).toEqual([]);
    expect(rememberedDub(ANIME, storage)).toEqual({ id: 609, title: "AniDUB" });
  });

  it("fails with the list button when no dub has the episode", async () => {
    kodik.missingEverywhere(7);
    const controller = build();

    await controller.open(ANIME, 7);

    const state = controller.getState();
    expect(state.phase).toBe("failed");
    expect(state.failure).toEqual({ message: "Серия 7 пока не вышла ни в одной озвучке", action: "list" });
    expect(engine.loads).toEqual([]);
    // The top bar and the dub menu keep working.
    expect(state.anime?.id).toBe(ANIME);
    expect(state.tracks).toHaveLength(3);
  });

  it("asks at most five other dubs before giving up", async () => {
    kodik.tracks = Array.from({ length: 9 }, (_, index) => ({ ...UNKNOWN_STUDIO, id: 2000 + index, title: `Студия ${index}` }));
    kodik.missingEverywhere(7);
    const controller = build();

    await controller.open(ANIME, 7);

    expect(kodik.asked).toHaveLength(6);
    expect(controller.getState().failure?.message).toBe("Серия 7 пока не вышла ни в одной озвучке");
  });

  it("fails without walking when the source itself failed", async () => {
    kodik.failures.set("610:7", new KodikError("upstream"));
    const controller = build();

    await controller.open(ANIME, 7);

    expect(kodik.asked).toEqual(["610:7"]);
    expect(controller.getState().failure).toEqual({ message: "Kodik временно недоступен, попробуйте позже", action: "dub" });
  });

  it("falls back to the list's card when the details do not load, and fails without either", async () => {
    server.detailsFailure = new NetworkError("Failed to fetch");
    const controller = build();
    await library.load();

    await controller.open(ANIME, 7);
    expect(controller.getState().phase).toBe("playing");
    expect(controller.getState().anime?.id).toBe(ANIME);

    server.rates = [];
    const stranger = build();
    await stranger.open(ANIME, 7);
    expect(stranger.getState().phase).toBe("failed");
    expect(stranger.getState().failure).toEqual({
      message: "Не удалось загрузить аниме. Проверьте соединение и повторите",
      action: "list",
    });
  });

  it("fails when the source lists no dub at all", async () => {
    kodik.tracks = [];
    const controller = build();

    await controller.open(ANIME, 7);

    expect(controller.getState().failure).toEqual({
      message: "Источник не предложил ни одной озвучки для этого аниме",
      action: "list",
    });
  });

  it("fails with the player's copy when the dub list does not load", async () => {
    kodik.tracksFailure = new KodikError("offline");
    const controller = build();

    await controller.open(ANIME, 7);

    expect(controller.getState().failure).toEqual({ message: "Нет соединения. Проверьте интернет", action: "dub" });
  });
});

describe("PlayerController: autoplay", () => {
  it("shows «Смотреть» rather than an error when the browser refuses to play, and plays on the next press", async () => {
    media.play.mockRejectedValueOnce(new DOMException("Autoplay refused", "NotAllowedError"));
    const controller = build();

    await controller.open(ANIME, 7);
    await settle();

    expect(controller.getState()).toMatchObject({ phase: "playing", failure: null, needsGesture: true, buffering: false });
    controller.togglePlay();
    await settle();
    expect(media.play).toHaveBeenCalledTimes(2);
    expect(controller.getState().needsGesture).toBe(false);
  });

  it("ignores a play() a newer load interrupted", async () => {
    media.play.mockRejectedValueOnce(new DOMException("Interrupted", "AbortError"));
    const controller = build();

    await controller.open(ANIME, 7);
    await settle();

    expect(controller.getState()).toMatchObject({ phase: "playing", failure: null, needsGesture: false });
  });

  it("fails when play() is refused for any other reason", async () => {
    media.play.mockRejectedValueOnce(new DOMException("No source", "NotSupportedError"));
    const controller = build();

    await controller.open(ANIME, 7);
    await settle();

    expect(controller.getState().failure).toEqual({ message: "Что-то пошло не так. Повторите попытку", action: "dub" });
  });
});

describe("PlayerController: positions", () => {
  it("writes nothing between a quality swap and its first seeked, however the time reads (Review Focus 1)", async () => {
    setWatchedThreshold(0.9, storage);
    const controller = await playing();
    controller.onTime(600_000, DUR);
    controller.onTime(605_000, DUR);
    expect(saved(7)).toBe(605_000);
    const put = vi.spyOn(progress, "put");

    await controller.changeQuality(480);
    expect(engine.loads.at(-1)).toEqual({ url: link(610, 7, 480), startMs: 605_000 });
    controller.onTime(0, DUR);
    controller.onTime(DUR * 0.95, DUR);

    expect(put).toHaveBeenCalledTimes(1);
    expect(saved(7)).toBe(605_000);
    expect(controller.getState().positionMs).toBe(605_000);
    expect(server.writes()).toEqual([]);

    controller.onSeeked(605_000);
    controller.onTime(611_000, DUR);
    expect(saved(7)).toBe(611_000);
  });

  it("ignores the 0 an element reloaded under the engine reads until it seeks back (Review Focus 1)", async () => {
    const controller = await playing();
    controller.onTime(600_000, DUR);
    controller.onTime(605_000, DUR);

    // hls.js recoverMediaError reloads the element: it announces its length at 0, then seeks back.
    controller.onTime(0, DUR);
    controller.onTime(0, DUR);

    expect(saved(7)).toBe(605_000);
    expect(controller.getState().positionMs).toBe(605_000);

    // A second media error within 5 s, before the seek back: «Повторить» starts where the episode was.
    controller.onEngineFailure("media");
    await controller.retry();
    expect(engine.loads.at(-1)).toEqual({ url: link(610, 7, 720), startMs: 605_000 });
    expect(saved(7)).toBe(605_000);
  });

  it("follows the episode again once the reloaded element seeks back", async () => {
    const controller = await playing();
    controller.onTime(600_000, DUR);
    controller.onTime(605_000, DUR);

    controller.onTime(0, DUR);
    controller.onSeeked(605_000);
    controller.onTime(611_000, DUR);

    expect(saved(7)).toBe(611_000);
    expect(controller.getState().positionMs).toBe(611_000);
  });

  it("follows a seek to the start the controller did not make, once it lands", async () => {
    const controller = await playing();
    controller.onTime(600_000, DUR);
    controller.onTime(605_000, DUR);

    controller.onTime(0, DUR);
    controller.onSeeked(0);
    controller.onTime(1_000, DUR);

    expect(controller.getState().positionMs).toBe(1_000);
    expect(saved(7)).toBe(1_000);
  });

  it("follows an element that plays on from the start without seeking back", async () => {
    const controller = await playing();
    controller.onTime(600_000, DUR);

    controller.onTime(0, DUR);
    controller.onTime(500, DUR);
    controller.onTime(1_000, DUR);

    expect(controller.getState().positionMs).toBe(1_000);
  });

  it("saves every 5 s of position and on pause", async () => {
    const controller = await playing();
    const put = vi.spyOn(progress, "put");

    controller.onTime(1_000, DUR);
    controller.onTime(4_900, DUR);
    expect(put).not.toHaveBeenCalled();
    controller.onTime(5_000, DUR);
    controller.onTime(9_000, DUR);
    controller.onTime(10_250, DUR);
    controller.onTime(12_000, DUR);
    controller.onPause();

    expect(put.mock.calls.map(([sample]) => sample.positionMs)).toEqual([5_000, 10_250, 12_000]);
    expect(put.mock.calls[0]?.[0]).toEqual({ animeId: ANIME, episode: 7, positionMs: 5_000, durationMs: DUR, updatedAt: NOW });
    expect(controller.getState().paused).toBe(true);
  });

  it("saves the position when disposed and lets go of the engine", async () => {
    const controller = await playing();
    controller.onTime(600_000, DUR);
    controller.onTime(603_000, DUR);

    controller.dispose();

    expect(saved(7)).toBe(603_000);
    expect(engine.destroyed).toBe(1);
  });

  it("clamps seeks to the episode", async () => {
    const controller = await playing();
    controller.onTime(5_000, DUR);

    controller.seekBy(-10_000);
    controller.seekTo(DUR + 60_000);

    expect(media.seek.mock.calls).toEqual([[0], [DUR]]);
    expect(controller.getState().positionMs).toBe(DUR);
  });
});

describe("PlayerController: marks on Shikimori", () => {
  it("marks the episode once at 90 %", async () => {
    const controller = await playing();
    // The library itself sends nothing for a count it already has: only the calls show a second mark.
    const mark = vi.spyOn(library, "markWatched");

    controller.onTime(1_295_000, DUR);
    await settle();
    expect(server.writes()).toEqual([]);
    controller.onTime(1_296_000, DUR);
    controller.onTime(1_300_000, DUR);
    controller.onTime(1_310_000, DUR);
    await settle();

    expect(mark).toHaveBeenCalledTimes(1);
    expect(server.writes()).toEqual(["PATCH 1 episodes=7"]);
  });

  it("reads the list again on open when the last read failed, so a mark waiting for it goes out", async () => {
    server.ratesFailure = new NetworkError("Failed to fetch");
    const controller = build();
    await library.load();
    expect(library.state().kind).toBe("error");
    server.ratesFailure = null;

    await controller.open(ANIME, 7);
    await settle();
    controller.onPlaying();
    controller.onTime(1_300_000, DUR);
    await settle();

    expect(server.writes()).toEqual(["PATCH 1 episodes=7"]);
  });

  it("waits for the list before marking, then marks once (Review Focus 2)", async () => {
    const list = deferred();
    server.ratesHold = list.promise;
    const controller = await playing();

    controller.onTime(1_300_000, DUR);
    controller.onTime(1_310_000, DUR);
    await settle();
    expect(server.writes()).toEqual([]);

    list.resolve();
    await settle();
    controller.onTime(1_320_000, DUR);
    await settle();

    // The rate the list holds is patched: nothing creates a second one.
    expect(server.writes()).toEqual(["PATCH 1 episodes=7"]);
  });

  it("offers to complete the title after its last episode, and completes it on «Да»", async () => {
    server.details = frieren({ status: "released", episodes: 12, episodesAired: 12 });
    server.rates = [{ id: 1, animeId: ANIME, status: "watching", episodes: 11, updatedAt: 1 }];
    const controller = await playing(12);

    controller.onTime(1_300_000, DUR);
    await settle();
    expect(controller.getState().completion).toBe(true);

    await controller.confirmCompletion();
    expect(server.writes()).toEqual(["PATCH 1 episodes=12", "PATCH 1 status=completed"]);
    expect(controller.getState().completion).toBe(false);
  });

  it("forgets the offer on «Позже»", async () => {
    server.details = frieren({ status: "released", episodes: 12, episodesAired: 12 });
    server.rates = [{ id: 1, animeId: ANIME, status: "watching", episodes: 11, updatedAt: 1 }];
    const controller = await playing(12);
    controller.onTime(1_300_000, DUR);
    await settle();

    controller.dismissCompletion();

    expect(controller.getState().completion).toBe(false);
    expect(server.writes()).toEqual(["PATCH 1 episodes=12"]);
  });

  it("says why a mark failed", async () => {
    server.failures = [new ApiError(503)];
    const controller = await playing();

    controller.onTime(1_300_000, DUR);
    await settle();

    expect(toasts).toEqual(["Shikimori недоступен, попробуйте позже"]);
  });

  it("puts a title that starts playing into the list as «Смотрю» once the list is known", async () => {
    server.rates = [];
    const list = deferred();
    server.ratesHold = list.promise;
    const controller = build();
    await controller.open(ANIME, 7);
    controller.onPlaying();
    await settle();
    expect(server.writes()).toEqual([]);

    list.resolve();
    await settle();
    controller.onPause();
    controller.onPlaying();
    await settle();

    expect(server.writes()).toEqual(["POST 42/52991 status=watching"]);
  });

  it("leaves a title already in the list alone when it starts", async () => {
    server.rates = [{ id: 1, animeId: ANIME, status: "planned", episodes: 0, updatedAt: 1 }];
    await playing();
    await settle();

    expect(server.writes()).toEqual([]);
  });
});

describe("PlayerController: the next episode", () => {
  it("counts 10…1 and then opens the next episode at its resume point", async () => {
    progress.put(row(8, 300_000));
    const controller = await playing();
    const counted: (number | null)[] = [];

    for (const left of [11_000, 10_000, 9_200, 5_000, 400]) {
      controller.onTime(DUR - left, DUR);
      counted.push(controller.getState().countdown);
    }
    const next = deferred();
    kodik.holds.set("610:8", next.promise);
    controller.onEnded();
    await settle();

    expect(counted).toEqual([null, 10, 10, 5, 1]);
    // Resolved before switching: episode 7 stays on screen meanwhile.
    expect(controller.getState().episode).toBe(7);
    expect(engine.loads).toHaveLength(1);

    next.resolve();
    await settle();
    expect(engine.loads.at(-1)).toEqual({ url: link(610, 8, 720), startMs: 300_000 });
    expect(controller.getState()).toMatchObject({ episode: 8, countdown: null, durationMs: 0, positionMs: 300_000 });
    expect(saved(7)).toBe(DUR);
  });

  it("stops the countdown on «Отмена» and stays at the end", async () => {
    setAutoplayNext(true, storage);
    const controller = await playing();
    controller.onTime(DUR - 5_000, DUR);
    expect(controller.getState().countdown).toBe(5);

    controller.cancelCountdown();
    controller.onTime(DUR - 4_000, DUR);
    controller.onEnded();
    await settle();

    expect(controller.getState()).toMatchObject({ countdown: null, paused: true, episode: 7 });
    expect(kodik.asked).toEqual(["610:7"]);
  });

  it("counts nothing down with autoplay off, and replays from the top on play", async () => {
    setAutoplayNext(false, storage);
    const controller = await playing();
    controller.onTime(DUR - 5_000, DUR);
    expect(controller.getState().countdown).toBeNull();

    controller.onEnded();
    controller.togglePlay();

    expect(media.seek).toHaveBeenCalledWith(0);
    expect(media.play).toHaveBeenCalledTimes(2);
    expect(controller.getState().positionMs).toBe(0);
  });

  it("says why the next episode did not open and stays on this one", async () => {
    kodik.missingEverywhere(8);
    const controller = await playing();
    controller.onTime(DUR - 3_000, DUR);

    await controller.next();

    expect(toasts).toEqual(["Серия 8 пока не вышла ни в одной озвучке"]);
    expect(controller.getState()).toMatchObject({ episode: 7, phase: "playing", countdown: null });
    controller.onTime(DUR - 2_000, DUR);
    expect(controller.getState().countdown).toBeNull();
    expect(engine.loads).toHaveLength(1);
  });

  it("can still move on after a re-resolve overtook the next episode's resolve", async () => {
    const controller = await playing();
    controller.onTime(600_000, DUR);
    const next = deferred();
    kodik.holds.set("610:8", next.promise);
    const moving = controller.next();
    await settle();

    controller.onEngineFailure("network");
    await settle();
    next.resolve();
    await moving;
    expect(controller.getState().episode).toBe(7);

    kodik.holds.delete("610:8");
    controller.onPlaying();
    await controller.next();
    expect(controller.getState().episode).toBe(8);
  });

  it("counts the episode as watched when left from inside the ending zone", async () => {
    setWatchedThreshold(1, storage);
    const controller = await playing();
    controller.onTime(DUR - 25_000, DUR);
    await settle();
    expect(controller.getState().endingDue).toBe(true);
    expect(server.writes()).toEqual([]);

    await controller.next();
    await settle();

    expect(server.writes()).toEqual(["PATCH 1 episodes=7"]);
    expect(saved(7)).toBe(DUR);
    expect(controller.getState().episode).toBe(8);
  });

  it("does not count an episode left from its middle", async () => {
    const controller = await playing();
    controller.onTime(600_000, DUR);

    await controller.next();
    await settle();

    expect(server.writes()).toEqual([]);
    expect(saved(7)).toBe(600_000);
    expect(engine.loads.at(-1)).toEqual({ url: link(610, 8, 720), startMs: 0 });
  });

  it("asks the next episode in the viewer's dub, not the stand-in", async () => {
    const lagging = { ...ANIDUB, episodesCount: null };
    kodik.tracks = [ANILIBRIA, lagging];
    rememberDub(ANIME, lagging, storage);
    kodik.failures.set("609:7", new KodikError("episode"));
    const controller = await playing();
    expect(controller.getState().track?.id).toBe(610);

    await controller.next();

    expect(kodik.asked).toEqual(["609:7", "610:7", "609:8"]);
    expect(controller.getState().track?.id).toBe(609);
  });
});

describe("PlayerController: opening and ending marks", () => {
  const OPENING = { startMs: 60_000, endMs: 150_000 };
  const ENDING = { startMs: 1_300_000, endMs: 1_420_000 };

  it("offers to skip the opening and seeks to its end", async () => {
    aniskip.found = { opening: OPENING, ending: null };
    const controller = await playing();
    controller.onTime(30_000, DUR);
    await settle();

    controller.onTime(65_000, DUR);
    expect(controller.getState().skip).toBe("opening");
    controller.pressSkip();

    expect(aniskip.asked).toEqual([`${ANIME}:7:${DUR}`]);
    expect(media.seek).toHaveBeenCalledWith(150_000);
    expect(controller.getState()).toMatchObject({ positionMs: 150_000, skip: null });
  });

  it("asks for the marks again when another quality or dub makes it another file, not for a fresh link", async () => {
    const controller = await playing();
    controller.onTime(30_000, DUR);
    await settle();

    await controller.changeQuality(480);
    controller.onSeeked(30_000);
    controller.onTime(31_000, DUR - 1_000);
    await settle();
    controller.onEngineFailure("network");
    await settle();
    controller.onPlaying();
    controller.onTime(32_000, DUR - 1_000);
    await settle();
    await controller.changeDub(609);
    controller.onPlaying();
    controller.onTime(33_000, DUR - 2_000);
    await settle();

    expect(aniskip.asked).toEqual([`${ANIME}:7:${DUR}`, `${ANIME}:7:${DUR - 1_000}`, `${ANIME}:7:${DUR - 2_000}`]);
  });

  it("offers the next episode at the ending, and pressing it counts this one", async () => {
    setWatchedThreshold(1, storage);
    aniskip.found = { opening: null, ending: ENDING };
    const controller = await playing();
    controller.onTime(1_290_000, DUR);
    await settle();

    controller.onTime(1_305_000, DUR);
    expect(controller.getState().skip).toBe("ending");
    controller.pressSkip();
    await settle();

    expect(server.writes()).toEqual(["PATCH 1 episodes=7"]);
    expect(controller.getState().episode).toBe(8);
  });

  it("offers no ending skip on the last aired episode", async () => {
    aniskip.found = { opening: null, ending: ENDING };
    const controller = await playing(12);
    controller.onTime(1_290_000, DUR);
    await settle();

    controller.onTime(1_305_000, DUR);

    expect(controller.getState()).toMatchObject({ hasNext: false, skip: null });
  });

  it("skips the ending by itself into the next episode", async () => {
    setSkipEnding(true, storage);
    aniskip.found = { opening: null, ending: ENDING };
    const controller = await playing();
    controller.onTime(1_290_000, DUR);
    await settle();

    controller.onTime(1_305_000, DUR);
    controller.onTime(1_310_000, DUR);
    await settle();

    expect(controller.getState().episode).toBe(8);
  });

  it("does not skip an ending the viewer dragged into until a second has played", async () => {
    setSkipEnding(true, storage);
    aniskip.found = { opening: null, ending: ENDING };
    const controller = await playing();
    controller.onTime(600_000, DUR);
    await settle();

    controller.seekTo(1_315_000);
    controller.onSeeked(1_315_000);
    controller.onTime(1_315_000, DUR);
    controller.onTime(1_315_500, DUR);
    await settle();
    expect(controller.getState().episode).toBe(7);

    controller.onTime(1_316_000, DUR);
    await settle();
    expect(controller.getState().episode).toBe(8);
  });

  it("finishes the last aired episode when its ending skips itself", async () => {
    setSkipEnding(true, storage);
    setWatchedThreshold(1, storage);
    server.rates = [{ id: 1, animeId: ANIME, status: "watching", episodes: 11, updatedAt: 1 }];
    aniskip.found = { opening: null, ending: ENDING };
    const controller = await playing(12);
    controller.onTime(1_290_000, DUR);
    await settle();

    controller.onTime(1_305_000, DUR);
    controller.onTime(1_310_000, DUR);
    await settle();

    expect(controller.getState()).toMatchObject({ finished: true, episode: 12 });
    expect(media.pause).toHaveBeenCalled();
    expect(server.writes()).toEqual(["PATCH 1 episodes=12"]);
    expect(saved(12)).toBe(DUR);
  });
});

describe("PlayerController: failures, quality and dub", () => {
  it("re-resolves silently after the first network failure, and shows the second (Review Focus 3)", async () => {
    const controller = await playing();
    controller.onTime(600_000, DUR);
    await controller.changeQuality(480);
    controller.onSeeked(600_000);
    controller.onTime(602_000, DUR);

    controller.onEngineFailure("network");
    await settle();

    expect(kodik.asked).toEqual(["610:7", "610:7"]);
    expect(engine.loads.at(-1)).toEqual({ url: link(610, 7, 480), startMs: 602_000 });
    expect(controller.getState()).toMatchObject({ phase: "playing", failure: null, quality: 480 });
    expect(toasts).toEqual([]);

    controller.onPlaying();
    controller.onEngineFailure("network");
    await settle();

    expect(kodik.asked).toHaveLength(2);
    expect(controller.getState().failure).toEqual({ message: "Kodik временно недоступен, попробуйте позже", action: "dub" });
  });

  it("opens the fresh link in a quality picked while it was being fetched, and loads nothing from the dead one", async () => {
    const controller = await playing();
    controller.onTime(600_000, DUR);
    const fresh = deferred();
    kodik.holds.set("610:7", fresh.promise);
    controller.onEngineFailure("network");
    await settle();

    await controller.changeQuality(480);
    expect(engine.loads).toHaveLength(1);

    fresh.resolve();
    await settle();

    expect(engine.loads.at(-1)).toEqual({ url: link(610, 7, 480), startMs: 600_000 });
    expect(engine.loads).toHaveLength(2);
    expect(controller.getState()).toMatchObject({ phase: "playing", failure: null, quality: 480 });
  });

  it("keeps a pause pressed while the fresh link was being fetched", async () => {
    const controller = await playing();
    controller.onTime(600_000, DUR);
    const fresh = deferred();
    kodik.holds.set("610:7", fresh.promise);
    controller.onEngineFailure("network");
    await settle();

    controller.togglePlay();
    fresh.resolve();
    await settle();

    expect(engine.loads).toHaveLength(2);
    expect(media.play).toHaveBeenCalledTimes(1);
    expect(controller.getState()).toMatchObject({ phase: "playing", paused: true });
  });

  it("does not repeat the stand-in toast when a retry lands on the same stand-in", async () => {
    const shortDub = { ...ANIDUB, episodesCount: 6 };
    kodik.tracks = [ANILIBRIA, shortDub];
    rememberDub(ANIME, shortDub, storage);
    const controller = await playing();
    expect(toasts).toHaveLength(1);
    controller.onEngineFailure("media");

    await controller.retry();

    expect(controller.getState()).toMatchObject({ phase: "playing", track: ANILIBRIA });
    expect(engine.loads).toHaveLength(2);
    expect(toasts).toHaveLength(1);
  });

  it("fails at once when the device is offline", async () => {
    const controller = await playing();

    controller.onEngineFailure("offline");

    expect(controller.getState().failure).toEqual({ message: "Нет соединения. Проверьте интернет", action: "dub" });
    expect(kodik.asked).toHaveLength(1);
  });

  it("retries from the current position in the current quality", async () => {
    const controller = await playing();
    controller.onTime(600_000, DUR);
    await controller.changeQuality(360);
    controller.onSeeked(600_000);
    controller.onEngineFailure("media");
    expect(controller.getState().phase).toBe("failed");

    await controller.retry();

    expect(engine.loads.at(-1)).toEqual({ url: link(610, 7, 360), startMs: 600_000 });
    expect(controller.getState()).toMatchObject({ phase: "playing", failure: null });
  });

  it("retries the whole open when it never got as far as the dubs", async () => {
    kodik.tracksFailure = new KodikError("upstream");
    const controller = build();
    await controller.open(ANIME, 7);
    expect(controller.getState().phase).toBe("failed");

    kodik.tracksFailure = null;
    await controller.retry();

    expect(controller.getState().phase).toBe("playing");
    expect(engine.loads).toEqual([{ url: link(610, 7, 720), startMs: 0 }]);
  });

  it("keeps a paused episode paused through a quality change", async () => {
    const controller = await playing();
    controller.onTime(600_000, DUR);
    controller.onPause();

    await controller.changeQuality(480);
    await settle();

    expect(engine.loads.at(-1)).toEqual({ url: link(610, 7, 480), startMs: 600_000 });
    expect(media.play).toHaveBeenCalledTimes(1);
    expect(controller.getState()).toMatchObject({ quality: 480, paused: true });
  });

  it("switches the dub at the same position and quality, and remembers it", async () => {
    const controller = await playing();
    controller.onTime(600_000, DUR);
    await controller.changeQuality(480);
    controller.onSeeked(600_000);

    await controller.changeDub(609);

    expect(kodik.asked).toEqual(["610:7", "609:7"]);
    expect(engine.loads.at(-1)).toEqual({ url: link(609, 7, 480), startMs: 600_000 });
    expect(controller.getState().track).toEqual(ANIDUB);
    expect(rememberedDub(ANIME, storage)?.id).toBe(609);
  });

  it("fails with the dub copy when the chosen dub lacks the episode", async () => {
    kodik.tracks = [ANILIBRIA, { ...ANIDUB, episodesCount: null }];
    kodik.failures.set("609:7", new KodikError("episode"));
    const controller = await playing();

    await controller.changeDub(609);

    const state = controller.getState();
    expect(state.failure).toEqual({ message: "Серии 7 ещё нет в этой озвучке", action: "dub" });
    expect(state.track?.id).toBe(610);
    expect(state.tracks).toHaveLength(2);
    expect(kodik.asked).toEqual(["610:7", "609:7"]);
    expect(rememberedDub(ANIME, storage)?.id).toBe(610);
    // The old dub does not play on under the failure.
    expect(media.pause).toHaveBeenCalled();
  });

  it("does not ask a chosen dub whose count already says no", async () => {
    kodik.tracks = [ANILIBRIA, { ...ANIDUB, episodesCount: 6 }];
    const controller = await playing();

    await controller.changeDub(609);

    expect(controller.getState().failure).toEqual({ message: "Серии 7 ещё нет в этой озвучке", action: "dub" });
    expect(kodik.asked).toEqual(["610:7"]);
  });

  it("plays a dub picked from the failure surface", async () => {
    kodik.failures.set("610:7", new KodikError("upstream"));
    const controller = build();
    await controller.open(ANIME, 7);
    expect(controller.getState().phase).toBe("failed");

    await controller.changeDub(609);

    expect(controller.getState()).toMatchObject({ phase: "playing", failure: null, track: ANIDUB });
    expect(engine.loads).toEqual([{ url: link(609, 7, 720), startMs: 0 }]);
  });

  it("fails when the silent re-resolve itself fails", async () => {
    const controller = await playing();
    kodik.failures.set("610:7", new KodikError("offline"));

    controller.onEngineFailure("network");
    await settle();

    expect(controller.getState().failure).toEqual({ message: "Нет соединения. Проверьте интернет", action: "dub" });
  });

  it("drops an open that a newer one overtook", async () => {
    const first = deferred();
    kodik.holds.set("610:7", first.promise);
    const controller = build();

    const stale = controller.open(ANIME, 7);
    await settle();
    await controller.open(ANIME, 8);
    first.resolve();
    await stale;

    expect(engine.loads).toEqual([{ url: link(610, 8, 720), startMs: 0 }]);
    expect(controller.getState().episode).toBe(8);
  });

  it("shows the spinner while the element waits for data", async () => {
    const controller = await playing();
    expect(controller.getState().buffering).toBe(false);

    controller.onWaiting();

    expect(controller.getState().buffering).toBe(true);
  });

  it("stops the spinner once the seek of a paused quality change lands", async () => {
    const controller = await playing();
    controller.onTime(600_000, DUR);
    controller.onPause();
    await controller.changeQuality(480);
    expect(controller.getState().buffering).toBe(true);

    controller.onSeeked(600_000);
    controller.onTime(600_000, DUR);

    expect(controller.getState()).toMatchObject({ paused: true, buffering: false });
  });

  it("keeps the spinner after a seek lands on a playing element until it plays", async () => {
    const controller = await playing();
    controller.onTime(600_000, DUR);
    await controller.changeQuality(480);

    controller.onSeeked(600_000);
    expect(controller.getState().buffering).toBe(true);
    controller.onPlaying();

    expect(controller.getState().buffering).toBe(false);
  });

  it("stops the spinner once a source paused while loading is ready", async () => {
    const loading = deferred();
    engine.hold = loading.promise;
    const controller = build();
    const opened = controller.open(ANIME, 7);
    await settle();
    controller.togglePlay();
    loading.resolve();
    await opened;
    await settle();
    expect(controller.getState().buffering).toBe(true);

    controller.onCanPlay();

    expect(controller.getState()).toMatchObject({ paused: true, buffering: false });
  });

  it("stops the spinner once a link re-resolved while paused lands", async () => {
    const controller = await playing();
    controller.onTime(600_000, DUR);
    controller.onPause();
    controller.onEngineFailure("network");
    await settle();
    expect(engine.loads).toHaveLength(2);
    expect(media.play).toHaveBeenCalledTimes(1);

    controller.onSeeked(600_000);

    expect(controller.getState()).toMatchObject({ phase: "playing", paused: true, buffering: false });
  });

  it("stops the spinner when the viewer pauses an episode waiting for data", async () => {
    const controller = await playing();
    controller.onWaiting();

    controller.togglePlay();
    controller.onPause();

    expect(controller.getState()).toMatchObject({ paused: true, buffering: false });
  });

  it("still sends a mark that was waiting for the list when the player closes", async () => {
    const list = deferred();
    server.ratesHold = list.promise;
    const controller = await playing();
    controller.onTime(1_300_000, DUR);

    controller.dispose();
    list.resolve();
    await settle();

    expect(server.writes()).toEqual(["PATCH 1 episodes=7"]);
  });

  it("toggles between playing and paused", async () => {
    const controller = await playing();

    controller.togglePlay();
    expect(media.pause).toHaveBeenCalledTimes(1);
    controller.onPause();
    controller.togglePlay();
    await settle();

    expect(media.play).toHaveBeenCalledTimes(2);
  });

  it("tells subscribers about every change, until they leave, with methods that work detached", async () => {
    const controller = build();
    // As useSyncExternalStore holds them.
    const { subscribe, getState } = controller;
    const listener = vi.fn();
    const leave = subscribe(listener);

    await controller.open(ANIME, 7);
    const heard = listener.mock.calls.length;
    leave();
    controller.onPlaying();

    expect(heard).toBeGreaterThan(0);
    expect(listener).toHaveBeenCalledTimes(heard);
    expect(getState().episode).toBe(7);
  });

  it("keeps a pause pressed while the source was still loading", async () => {
    const loading = deferred();
    engine.hold = loading.promise;
    const controller = build();
    const opened = controller.open(ANIME, 7);
    await settle();

    controller.togglePlay();
    loading.resolve();
    await opened;
    await settle();

    expect(media.play).not.toHaveBeenCalled();
    expect(controller.getState().paused).toBe(true);
  });

  it("makes a stand-in the dub when the viewer picks it", async () => {
    const shortDub = { ...ANIDUB, episodesCount: 6 };
    kodik.tracks = [ANILIBRIA, shortDub];
    rememberDub(ANIME, shortDub, storage);
    const controller = await playing();

    await controller.changeDub(610);
    await controller.next();

    expect(rememberedDub(ANIME, storage)?.id).toBe(610);
    expect(engine.loads).toHaveLength(2);
    expect(toasts).toHaveLength(1);
  });
});
