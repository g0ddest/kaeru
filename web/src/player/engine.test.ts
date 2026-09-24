// Vectors: the engine rule of the hls.js README (v1.7.3), its API.md «Fatal Error Recovery» (one
// recoverMediaError per 5 s), and Task 4 of docs/superpowers/plans/2026-09-24-kaeru-web-03-player.md.
// A 403 manifest, measured in Chromium and WebKit: hls.js sends a fatal networkError with response.code
// 403, Safari a MediaError 4 with no message.
import workerPath from "hls.js/dist/hls.worker.js?url";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { chooseEngine, classifyHlsError, createEngine } from "./engine";
import type { EngineFailureKind } from "./errors";

// jsdom has no Media Source, so the real hls.js would refuse to start: every instance is a fake the
// test can fire hls.js events at.
const fake = vi.hoisted(() => {
  type Handler = (event: string, data: unknown) => void;
  class FakeHls {
    static instances: FakeHls[] = [];
    readonly config: Record<string, unknown>;
    readonly handlers = new Map<string, Handler>();
    readonly on = vi.fn((event: string, handler: Handler) => {
      this.handlers.set(event, handler);
    });
    readonly attachMedia = vi.fn();
    readonly loadSource = vi.fn();
    readonly recoverMediaError = vi.fn();
    readonly destroy = vi.fn();

    // No parameter properties: erasableSyntaxOnly.
    constructor(config: Record<string, unknown>) {
      this.config = config;
      FakeHls.instances.push(this);
    }

    emit(event: string, data: unknown): void {
      this.handlers.get(event)?.(event, data);
    }
  }
  // While true, the lazy chunk cannot be fetched: no network, or a deploy removed it.
  return { FakeHls, unreachable: false };
});

function hlsLight() {
  if (fake.unreachable) throw new TypeError("Failed to fetch dynamically imported module");
  return { default: fake.FakeHls, Events: { ERROR: "hlsError" } };
}

vi.mock("hls.js/light", () => hlsLight());

const instances = fake.FakeHls.instances;
const SECOND = 1_000;

/** A browser as the engine sees it; jsdom alone is one that can play nothing. */
function browser(env: { canPlayHls: boolean; managedMediaSource: boolean; mediaSource: boolean }): void {
  vi.spyOn(HTMLMediaElement.prototype, "canPlayType").mockReturnValue(env.canPlayHls ? "maybe" : "");
  const globals = window as unknown as Record<string, unknown>;
  const source = class {
    static isTypeSupported(): boolean {
      return true;
    }
  };
  if (env.managedMediaSource) globals["ManagedMediaSource"] = source;
  if (env.mediaSource) globals["MediaSource"] = source;
}

const chrome = () => browser({ canPlayHls: true, managedMediaSource: false, mediaSource: true });

function started() {
  const video = document.createElement("video");
  const failures: EngineFailureKind[] = [];
  const engine = createEngine(video, (kind) => failures.push(kind));
  return { video, failures, engine };
}

beforeEach(() => {
  instances.length = 0;
  // jsdom logs «not implemented» for load() and does nothing.
  vi.spyOn(HTMLMediaElement.prototype, "load").mockImplementation(() => {});
});

afterEach(() => {
  vi.restoreAllMocks();
  const globals = window as unknown as Record<string, unknown>;
  delete globals["ManagedMediaSource"];
  delete globals["MediaSource"];
});

describe("chooseEngine", () => {
  it.each([
    // Chrome says «maybe» to HLS and plays it badly: MSE decides.
    ["Chrome", { canPlayHls: true, hasManagedMediaSource: false, mseSupported: true }, "hls"],
    ["Safari", { canPlayHls: true, hasManagedMediaSource: true, mseSupported: true }, "native"],
    ["an iPhone before iOS 17.1", { canPlayHls: true, hasManagedMediaSource: false, mseSupported: false }, "native"],
    ["Firefox", { canPlayHls: false, hasManagedMediaSource: false, mseSupported: true }, "hls"],
    ["a browser with neither", { canPlayHls: false, hasManagedMediaSource: false, mseSupported: false }, "none"],
  ] as const)("gives %s the %s engine", (_browser, env, kind) => {
    expect(chooseEngine(env)).toBe(kind);
  });
});

describe("classifyHlsError", () => {
  it.each([
    ["a fatal media error is recovered", { type: "mediaError", details: "bufferStalledError", fatal: true }, true, "recover"],
    ["a refused manifest online is the source's", { type: "networkError", details: "manifestLoadError", fatal: true, response: { code: 403 } }, true, "network"],
    ["a failed fragment offline is the network's", { type: "networkError", details: "fragLoadError", fatal: true, response: { code: 0 } }, false, "offline"],
    ["another fatal kind is a media failure", { type: "muxError", details: "fragParsingError", fatal: true }, true, "media"],
    // hls.js is still retrying these itself.
    ["a non-fatal network error is left to hls.js", { type: "networkError", details: "fragLoadError", fatal: false }, true, "ignore"],
    ["a non-fatal media error is left to hls.js", { type: "mediaError", details: "bufferStalledError", fatal: false }, false, "ignore"],
  ] as const)("%s", (_case, data, online, verdict) => {
    expect(classifyHlsError(data, online)).toBe(verdict);
  });
});

describe("createEngine over hls.js", () => {
  const FATAL_MEDIA = { type: "mediaError", details: "bufferAppendError", fatal: true };

  it("starts hls.js at the position in seconds with its worker from our own assets", async () => {
    chrome();
    const { video, engine } = started();

    await engine.load("https://cdn.example/720.m3u8", 861_500);

    expect(instances).toHaveLength(1);
    expect(instances[0]!.config).toMatchObject({ startPosition: 861.5, workerPath });
    expect(instances[0]!.attachMedia).toHaveBeenCalledWith(video);
    expect(instances[0]!.loadSource).toHaveBeenCalledWith("https://cdn.example/720.m3u8");
  });

  it("replaces the previous instance on every load", async () => {
    chrome();
    const { engine } = started();

    await engine.load("https://cdn.example/720.m3u8", 0);
    await engine.load("https://cdn.example/480.m3u8", 42_000);

    expect(instances).toHaveLength(2);
    expect(instances[0]!.destroy).toHaveBeenCalled();
    expect(instances[1]!.destroy).not.toHaveBeenCalled();
    expect(instances[1]!.config).toMatchObject({ startPosition: 42 });
  });

  it("starts only the last of two loads that overlap", async () => {
    chrome();
    const { engine } = started();

    await Promise.all([engine.load("https://cdn.example/720.m3u8", 0), engine.load("https://cdn.example/480.m3u8", 5_000)]);

    expect(instances).toHaveLength(1);
    expect(instances[0]!.loadSource).toHaveBeenCalledWith("https://cdn.example/480.m3u8");
  });

  it("starts nothing when destroyed while hls.js is still arriving", async () => {
    chrome();
    const { engine } = started();

    const loading = engine.load("https://cdn.example/720.m3u8", 0);
    engine.destroy();
    await loading;

    expect(instances).toHaveLength(0);
  });

  it("destroys the instance on destroy", async () => {
    chrome();
    const { engine } = started();

    await engine.load("https://cdn.example/720.m3u8", 0);
    engine.destroy();

    expect(instances[0]!.destroy).toHaveBeenCalled();
  });

  it("recovers a fatal media error, but gives up on a second one within 5 s", async () => {
    chrome();
    const clock = { now: 1_000_000 };
    vi.spyOn(Date, "now").mockImplementation(() => clock.now);
    const { engine, failures } = started();
    await engine.load("https://cdn.example/720.m3u8", 0);

    instances[0]!.emit("hlsError", FATAL_MEDIA);
    expect(instances[0]!.recoverMediaError).toHaveBeenCalledTimes(1);
    expect(failures).toEqual([]);

    clock.now += 4 * SECOND;
    instances[0]!.emit("hlsError", FATAL_MEDIA);
    expect(instances[0]!.recoverMediaError).toHaveBeenCalledTimes(1);
    expect(failures).toEqual(["media"]);
  });

  it("recovers again once 5 s have passed since the last recovery", async () => {
    chrome();
    const clock = { now: 1_000_000 };
    vi.spyOn(Date, "now").mockImplementation(() => clock.now);
    const { engine, failures } = started();
    await engine.load("https://cdn.example/720.m3u8", 0);

    instances[0]!.emit("hlsError", FATAL_MEDIA);
    clock.now += 6 * SECOND;
    instances[0]!.emit("hlsError", FATAL_MEDIA);

    expect(instances[0]!.recoverMediaError).toHaveBeenCalledTimes(2);
    expect(failures).toEqual([]);
  });

  // Measured in Chrome with hls.js 1.7.3: the recovery reloads the element, which leaves it paused at the
  // same position without a `pause` event, so the controller still believes it is playing.
  function playingThroughRecovery(hls: InstanceType<typeof fake.FakeHls>) {
    const element = { paused: false };
    vi.spyOn(HTMLMediaElement.prototype, "paused", "get").mockImplementation(() => element.paused);
    hls.recoverMediaError.mockImplementation(() => {
      element.paused = true;
    });
    return vi.spyOn(HTMLMediaElement.prototype, "play").mockImplementation(() => {
      element.paused = false;
      return Promise.resolve();
    });
  }

  it("resumes a playing video after recovering it", async () => {
    chrome();
    const { video, engine, failures } = started();
    await engine.load("https://cdn.example/720.m3u8", 0);
    const play = playingThroughRecovery(instances[0]!);

    instances[0]!.emit("hlsError", FATAL_MEDIA);

    expect(instances[0]!.recoverMediaError).toHaveBeenCalledTimes(1);
    expect(play).toHaveBeenCalledTimes(1);
    expect(video.paused).toBe(false);
    expect(failures).toEqual([]);
  });

  it("leaves a paused video paused after recovering it", async () => {
    chrome();
    vi.spyOn(HTMLMediaElement.prototype, "paused", "get").mockReturnValue(true);
    const play = vi.spyOn(HTMLMediaElement.prototype, "play").mockResolvedValue();
    const { engine } = started();
    await engine.load("https://cdn.example/720.m3u8", 0);

    instances[0]!.emit("hlsError", FATAL_MEDIA);

    expect(instances[0]!.recoverMediaError).toHaveBeenCalledTimes(1);
    expect(play).not.toHaveBeenCalled();
  });

  it("swallows a refused resume after a recovery", async () => {
    chrome();
    const escaped: unknown[] = [];
    const onUnhandled = (reason: unknown): void => {
      escaped.push(reason);
    };
    process.on("unhandledRejection", onUnhandled);
    try {
      vi.spyOn(HTMLMediaElement.prototype, "paused", "get").mockReturnValue(false);
      const { video, engine, failures } = started();
      // Not a vi spy: it handles the promises it returns itself, which would hide a missing catch.
      let plays = 0;
      video.play = () => {
        plays++;
        return Promise.reject(new DOMException("The play() request was interrupted", "AbortError"));
      };
      await engine.load("https://cdn.example/720.m3u8", 0);

      instances[0]!.emit("hlsError", FATAL_MEDIA);
      // Node reports an unhandled rejection once the microtasks have run.
      await new Promise((resolve) => setTimeout(resolve, 0));

      expect(plays).toBe(1);
      expect(escaped).toEqual([]);
      expect(failures).toEqual([]);
    } finally {
      process.off("unhandledRejection", onUnhandled);
    }
  });

  it("reports one failure per source, however many fatal errors follow", async () => {
    chrome();
    const clock = { now: 1_000_000 };
    vi.spyOn(Date, "now").mockImplementation(() => clock.now);
    const { engine, failures } = started();
    await engine.load("https://cdn.example/720.m3u8", 0);

    instances[0]!.emit("hlsError", { type: "networkError", details: "fragLoadError", fatal: true, response: { code: 403 } });
    instances[0]!.emit("hlsError", { type: "networkError", details: "fragLoadError", fatal: true, response: { code: 403 } });
    instances[0]!.emit("hlsError", FATAL_MEDIA);
    expect(failures).toEqual(["network"]);
    // Given up on: no recovery either.
    expect(instances[0]!.recoverMediaError).not.toHaveBeenCalled();

    // The next source is new and reports for itself.
    await engine.load("https://cdn.example/720.m3u8", 12_000);
    instances[1]!.emit("hlsError", { type: "networkError", details: "manifestLoadError", fatal: true, response: { code: 403 } });
    expect(failures).toEqual(["network", "network"]);
  });

  it("reports one failure once a media error is given up on", async () => {
    chrome();
    const clock = { now: 1_000_000 };
    vi.spyOn(Date, "now").mockImplementation(() => clock.now);
    const { engine, failures } = started();
    await engine.load("https://cdn.example/720.m3u8", 0);

    instances[0]!.emit("hlsError", FATAL_MEDIA);
    clock.now += SECOND;
    instances[0]!.emit("hlsError", FATAL_MEDIA);
    clock.now += SECOND;
    instances[0]!.emit("hlsError", FATAL_MEDIA);
    // Past the 5 s window, but the source has already been given up on.
    clock.now += 10 * SECOND;
    instances[0]!.emit("hlsError", FATAL_MEDIA);

    expect(failures).toEqual(["media"]);
    expect(instances[0]!.recoverMediaError).toHaveBeenCalledTimes(1);
  });

  it("reports a refused playlist as the source's while online", async () => {
    chrome();
    const { engine, failures } = started();
    await engine.load("https://cdn.example/720.m3u8", 0);

    instances[0]!.emit("hlsError", { type: "networkError", details: "manifestLoadError", fatal: true, response: { code: 403 } });

    expect(failures).toEqual(["network"]);
  });

  it("reports a failed request as no network while offline", async () => {
    chrome();
    vi.spyOn(navigator, "onLine", "get").mockReturnValue(false);
    const { engine, failures } = started();
    await engine.load("https://cdn.example/720.m3u8", 0);

    instances[0]!.emit("hlsError", { type: "networkError", details: "fragLoadError", fatal: true, response: { code: 0 } });

    expect(failures).toEqual(["offline"]);
  });

  it("leaves non-fatal errors to hls.js", async () => {
    chrome();
    const { engine, failures } = started();
    await engine.load("https://cdn.example/720.m3u8", 0);

    instances[0]!.emit("hlsError", { type: "networkError", details: "fragLoadError", fatal: false });
    instances[0]!.emit("hlsError", { type: "mediaError", details: "bufferStalledError", fatal: false });

    expect(instances[0]!.recoverMediaError).not.toHaveBeenCalled();
    expect(failures).toEqual([]);
  });
});

describe("createEngine when hls.js cannot be fetched", () => {
  afterEach(() => {
    fake.unreachable = false;
  });

  // A fresh engine module and a fresh mock, so the chunk is fetched anew rather than taken from an
  // earlier test.
  async function freshEngine(): Promise<typeof createEngine> {
    vi.resetModules();
    vi.doMock("hls.js/light", () => hlsLight());
    return (await import("./engine")).createEngine;
  }

  it("reports a failure while online, and fetches hls.js again on the next load", async () => {
    chrome();
    const create = await freshEngine();
    const failures: EngineFailureKind[] = [];
    const engine = create(document.createElement("video"), (kind) => failures.push(kind));

    fake.unreachable = true;
    await engine.load("https://cdn.example/720.m3u8", 0);
    expect(failures).toEqual(["media"]);

    fake.unreachable = false;
    await engine.load("https://cdn.example/720.m3u8", 0);
    expect(instances).toHaveLength(1);
    expect(failures).toEqual(["media"]);
  });

  it("reports no network while offline", async () => {
    chrome();
    vi.spyOn(navigator, "onLine", "get").mockReturnValue(false);
    const create = await freshEngine();
    const failures: EngineFailureKind[] = [];
    const engine = create(document.createElement("video"), (kind) => failures.push(kind));

    fake.unreachable = true;
    await engine.load("https://cdn.example/720.m3u8", 0);

    expect(failures).toEqual(["offline"]);
  });
});

describe("createEngine over the browser's own HLS", () => {
  const safari = () => browser({ canPlayHls: true, managedMediaSource: true, mediaSource: true });
  const iphone = () => browser({ canPlayHls: true, managedMediaSource: false, mediaSource: false });

  it("sets the source and seeks to the position once its metadata is in", async () => {
    safari();
    const { video, engine } = started();

    await engine.load("https://cdn.example/720.m3u8", 861_500);

    expect(video.getAttribute("src")).toBe("https://cdn.example/720.m3u8");
    expect(video.currentTime).toBe(0);
    video.dispatchEvent(new Event("loadedmetadata"));
    expect(video.currentTime).toBe(861.5);
    expect(instances).toHaveLength(0);
  });

  it("seeks only on the first metadata of a source, to the latest load's position", async () => {
    iphone();
    const { video, engine } = started();

    await engine.load("https://cdn.example/720.m3u8", 10_000);
    await engine.load("https://cdn.example/480.m3u8", 20_000);
    video.dispatchEvent(new Event("loadedmetadata"));
    expect(video.currentTime).toBe(20);

    // The viewer has moved on; a later metadata event must not pull them back.
    video.currentTime = 300;
    video.dispatchEvent(new Event("loadedmetadata"));
    expect(video.currentTime).toBe(300);
  });

  it("reports an element error as the source's while online", async () => {
    safari();
    const { video, engine, failures } = started();
    await engine.load("https://cdn.example/720.m3u8", 0);

    video.dispatchEvent(new Event("error"));

    expect(failures).toEqual(["network"]);
  });

  it("reports an element error as no network while offline", async () => {
    safari();
    vi.spyOn(navigator, "onLine", "get").mockReturnValue(false);
    const { video, engine, failures } = started();
    await engine.load("https://cdn.example/720.m3u8", 0);

    video.dispatchEvent(new Event("error"));

    expect(failures).toEqual(["offline"]);
  });

  it("empties the element on destroy and reports nothing after", async () => {
    safari();
    const { video, engine, failures } = started();
    await engine.load("https://cdn.example/720.m3u8", 0);

    engine.destroy();

    expect(video.hasAttribute("src")).toBe(false);
    expect(HTMLMediaElement.prototype.load).toHaveBeenCalled();
    video.dispatchEvent(new Event("error"));
    expect(failures).toEqual([]);
  });
});

describe("createEngine in a browser that plays no HLS", () => {
  it("reports the browser unsupported on load", async () => {
    // jsdom as it is: canPlayType says "" and there is no Media Source.
    const { video, engine, failures } = started();

    await engine.load("https://cdn.example/720.m3u8", 0);

    expect(failures).toEqual(["unsupported"]);
    expect(video.hasAttribute("src")).toBe(false);
    expect(instances).toHaveLength(0);
  });
});
