// Vectors: Task 6 of docs/superpowers/plans/2026-09-24-kaeru-web-03-player.md («mediaSession.ts») and
// scratchpad map 3 §6: setPositionState throws on a NaN or infinite duration, an unsupported action
// throws from setActionHandler, and Safari before 15 has no mediaSession at all. jsdom has neither
// navigator.mediaSession nor MediaMetadata, so each test installs its own.
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { clearMediaSession, showInMediaSession, showMediaPosition, type MediaSessionActions } from "./mediaSession";

type Handler = (details: MediaSessionActionDetails) => void;

class FakeSession {
  metadata: unknown = null;
  readonly handlers = new Map<string, Handler | null>();
  readonly positions: (MediaPositionState | undefined)[] = [];
  /** Actions this browser does not know: setting them throws, as Chrome does for an unknown action. */
  unsupported = new Set<string>();
  failPosition = false;

  setActionHandler = (action: string, handler: Handler | null): void => {
    if (this.unsupported.has(action)) throw new TypeError(`The provided value '${action}' is not a valid enum value`);
    this.handlers.set(action, handler);
  };

  setPositionState = (state?: MediaPositionState): void => {
    if (this.failPosition) throw new TypeError("bad position");
    this.positions.push(state);
  };

  run(action: string, details: Partial<MediaSessionActionDetails> = {}): void {
    const handler = this.handlers.get(action);
    if (!handler) throw new Error(`no handler for ${action}`);
    handler({ action: action as MediaSessionAction, ...details });
  }
}

class FakeMetadata {
  readonly init: MediaMetadataInit;
  constructor(init: MediaMetadataInit) {
    this.init = init;
  }
}

let session: FakeSession;
let actions: MediaSessionActions & { calls: string[] };

function install(withMetadata = true): void {
  session = new FakeSession();
  Object.defineProperty(navigator, "mediaSession", { configurable: true, value: session });
  if (withMetadata) vi.stubGlobal("MediaMetadata", FakeMetadata);
}

beforeEach(() => {
  const calls: string[] = [];
  actions = {
    calls,
    play: () => calls.push("play"),
    pause: () => calls.push("pause"),
    seekBy: (ms) => calls.push(`seekBy ${ms}`),
    seekTo: (ms) => calls.push(`seekTo ${ms}`),
    next: () => calls.push("next"),
  };
});

afterEach(() => {
  delete (navigator as unknown as { mediaSession?: unknown }).mediaSession;
  vi.unstubAllGlobals();
});

const NOW = { title: "7 серия", artist: "Провожающая в последний путь Фрирен", album: "AniLibria.TV", artwork: "https://shikimori.io/poster.jpg" };

describe("media session", () => {
  it("does nothing where the browser has no media session", () => {
    expect("mediaSession" in navigator).toBe(false);
    expect(() => {
      showInMediaSession(NOW, actions);
      showMediaPosition(1_000, 60_000);
      clearMediaSession();
    }).not.toThrow();
  });

  it("names the episode, the title, the dub and the poster", () => {
    install();
    showInMediaSession(NOW, actions);
    expect(session.metadata).toBeInstanceOf(FakeMetadata);
    expect((session.metadata as FakeMetadata).init).toEqual({
      title: "7 серия",
      artist: "Провожающая в последний путь Фрирен",
      album: "AniLibria.TV",
      artwork: [{ src: "https://shikimori.io/poster.jpg" }],
    });
  });

  it("leaves the artwork out for a title without a poster", () => {
    install();
    showInMediaSession({ ...NOW, artwork: null }, actions);
    expect((session.metadata as FakeMetadata).init.artwork).toEqual([]);
  });

  it("routes the system controls to the player", () => {
    install();
    showInMediaSession(NOW, actions);

    session.run("play");
    session.run("pause");
    session.run("seekbackward");
    session.run("seekforward");
    session.run("seekbackward", { seekOffset: 5 });
    session.run("seekforward", { seekOffset: 30 });
    session.run("seekto", { seekTime: 42.5 });
    session.run("seekto", {});
    session.run("nexttrack");

    expect(actions.calls).toEqual([
      "play",
      "pause",
      "seekBy -10000",
      "seekBy 10000",
      "seekBy -5000",
      "seekBy 30000",
      "seekTo 42500",
      "next",
    ]);
  });

  it("offers no next track when there is no next episode", () => {
    install();
    showInMediaSession(NOW, { ...actions, next: null });
    expect(session.handlers.get("nexttrack")).toBeNull();
    expect(session.handlers.get("play")).toBeTypeOf("function");
  });

  it("sets every other handler when the browser refuses one", () => {
    install();
    session.unsupported.add("seekto");
    showInMediaSession(NOW, actions);
    expect(session.handlers.has("seekto")).toBe(false);
    for (const action of ["play", "pause", "seekbackward", "seekforward", "nexttrack"]) {
      expect(session.handlers.get(action)).toBeTypeOf("function");
    }
  });

  it("still sets the handlers where MediaMetadata is missing", () => {
    install(false);
    showInMediaSession(NOW, actions);
    expect(session.metadata).toBeNull();
    expect(session.handlers.get("play")).toBeTypeOf("function");
  });

  it("reports the position only against a finite length, in seconds, never past the end", () => {
    install();
    showMediaPosition(600_000, 1_440_000);
    showMediaPosition(1_500_000, 1_440_000);
    showMediaPosition(1_000, Number.NaN);
    showMediaPosition(1_000, Number.POSITIVE_INFINITY);
    showMediaPosition(1_000, 0);
    showMediaPosition(-5, 1_440_000);
    expect(session.positions).toEqual([
      { duration: 1440, position: 600, playbackRate: 1 },
      { duration: 1440, position: 1440, playbackRate: 1 },
      { duration: 1440, position: 0, playbackRate: 1 },
    ]);
  });

  it("swallows a position the browser refuses", () => {
    install();
    session.failPosition = true;
    expect(() => showMediaPosition(600_000, 1_440_000)).not.toThrow();
  });

  it("clears the metadata, every handler and the position", () => {
    install();
    showInMediaSession(NOW, actions);
    showMediaPosition(600_000, 1_440_000);

    clearMediaSession();

    expect(session.metadata).toBeNull();
    for (const action of ["play", "pause", "seekbackward", "seekforward", "seekto", "nexttrack"]) {
      expect(session.handlers.get(action)).toBeNull();
    }
    expect(session.positions.at(-1)).toBeUndefined();
    expect(session.positions).toHaveLength(2);
  });
});
