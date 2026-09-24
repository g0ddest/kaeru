import workerPath from "hls.js/dist/hls.worker.js?url";
import type { EngineFailureKind } from "./errors";

/** One source at a time on the player's one `<video>`; failures arrive through the factory's callback. */
export interface Engine {
  /** Replaces the current source; playback position becomes startMs once loaded. */
  load(url: string, startMs: number): Promise<void>;
  destroy(): void;
}

export type EngineFactory = (video: HTMLVideoElement, onFailure: (kind: EngineFailureKind) => void) => Engine;

/** Who plays the HLS playlist: the browser itself, hls.js over Media Source, or nobody. */
export type EngineKind = "native" | "hls" | "none";

/**
 * The hls.js README's rule (v1.7.3), not «canPlayType first»: Chrome answers «maybe» to HLS and then
 * fails on some streams. Native only where Managed Media Source says the browser is a modern Safari, or
 * where there is no Media Source at all (an iPhone before 17.1); everywhere else hls.js if it can run.
 */
export function chooseEngine(env: { canPlayHls: boolean; hasManagedMediaSource: boolean; mseSupported: boolean }): EngineKind {
  if (env.canPlayHls && (env.hasManagedMediaSource || !env.mseSupported)) return "native";
  if (env.mseSupported) return "hls";
  return env.canPlayHls ? "native" : "none";
}

/**
 * What one hls.js error means to the player. Non-fatal ones hls.js is still retrying; a fatal media error
 * is worth one `recoverMediaError`; a fatal network error is either no network at all or a link the CDN
 * refused, which is how an expired signature looks (the controller resolves the episode again).
 */
export function classifyHlsError(
  d: { type: string; details: string; fatal: boolean; response?: { code?: number } },
  online: boolean,
): "ignore" | "recover" | EngineFailureKind {
  if (!d.fatal) return "ignore";
  if (d.type === "mediaError") return "recover";
  if (d.type === "networkError") return online ? "network" : "offline";
  return "media";
}

const HLS_TYPE = "application/vnd.apple.mpegurl";
// What Kodik serves: H.264 and AAC in MPEG-TS, which hls.js remuxes to fragmented MP4.
const KODIK_CODECS = 'video/mp4; codecs="avc1.42E01E,mp4a.40.2"';
/** hls.js's own advice: one `recoverMediaError` per 5 s, or a broken file loops forever. */
const RECOVERY_GAP_MS = 5_000;
// A whole played episode kept behind the position would crowd out low-memory tablets and laptops.
const BACK_BUFFER_S = 90;
const FORWARD_BUFFER_S = 30;

type HlsModule = typeof import("hls.js/light");

// Fetched on the first hls.js source only: Safari plays natively and never downloads it.
let hlsModule: Promise<HlsModule> | null = null;

function loadHls(): Promise<HlsModule> {
  // A chunk that failed to arrive is asked for again by the next load, not remembered as broken.
  hlsModule ??= import("hls.js/light").catch((error: unknown) => {
    hlsModule = null;
    throw error;
  });
  return hlsModule;
}

interface MediaSourceClass {
  isTypeSupported(type: string): boolean;
}

function detect(video: HTMLVideoElement): EngineKind {
  // Neither is guaranteed: no Media Source on an iPhone, and the managed one is Safari's alone.
  const scope = window as unknown as { ManagedMediaSource?: MediaSourceClass; MediaSource?: MediaSourceClass };
  return chooseEngine({
    canPlayHls: video.canPlayType(HLS_TYPE) !== "",
    hasManagedMediaSource: "ManagedMediaSource" in window,
    mseSupported: !!(scope.ManagedMediaSource ?? scope.MediaSource)?.isTypeSupported(KODIK_CODECS),
  });
}

/**
 * hls.js with a new instance per source: Kodik has no master playlist, so a quality or an episode is a
 * separate playlist, and a fresh instance carries nothing over from the last one but the element.
 */
function hlsEngine(video: HTMLVideoElement, onFailure: (kind: EngineFailureKind) => void): Engine {
  let hls: InstanceType<HlsModule["default"]> | null = null;
  // Bumped by every load and destroy: a load still waiting for hls.js to arrive has been overtaken.
  let generation = 0;

  function drop(): void {
    hls?.destroy();
    hls = null;
  }

  return {
    async load(url, startMs) {
      const mine = ++generation;
      drop();
      let module: HlsModule;
      try {
        module = await loadHls();
      } catch {
        // Offline, or a deploy replaced the chunk under an open page. Reported like any other failure,
        // so load never rejects.
        if (mine === generation) onFailure(navigator.onLine ? "media" : "offline");
        return;
      }
      if (mine !== generation) return;

      const { default: Hls, Events } = module;
      const instance = new Hls({
        workerPath,
        startPosition: startMs / 1_000,
        backBufferLength: BACK_BUFFER_S,
        maxBufferLength: FORWARD_BUFFER_S,
      });
      // Per source: a new file earns its own recovery.
      let recoveredAt: number | null = null;
      instance.on(Events.ERROR, (_event, data) => {
        const verdict = classifyHlsError(data, navigator.onLine);
        if (verdict === "ignore") return;
        if (verdict === "recover") {
          const now = Date.now();
          if (recoveredAt === null || now - recoveredAt >= RECOVERY_GAP_MS) {
            recoveredAt = now;
            instance.recoverMediaError();
            return;
          }
          onFailure("media");
          return;
        }
        onFailure(verdict);
      });
      instance.attachMedia(video);
      instance.loadSource(url);
      hls = instance;
    },
    destroy() {
      generation++;
      drop();
    },
  };
}

/**
 * Safari's own HLS, the same AVFoundation the iOS app plays Kodik with, AirPlay included. It never says
 * an HTTP status: a refused playlist is MediaError 4 with no message, so any element error while online
 * is the source's, and the controller answers it as it answers a 403 from hls.js.
 */
function nativeEngine(video: HTMLVideoElement, onFailure: (kind: EngineFailureKind) => void): Engine {
  // The seek waiting for the current source's metadata; a newer load replaces it.
  let pendingSeek: (() => void) | null = null;
  let listening = false;
  const failed = (): void => onFailure(navigator.onLine ? "network" : "offline");

  function forgetSeek(): void {
    if (pendingSeek !== null) video.removeEventListener("loadedmetadata", pendingSeek);
    pendingSeek = null;
  }

  return {
    async load(url, startMs) {
      forgetSeek();
      if (!listening) video.addEventListener("error", failed);
      listening = true;
      // currentTime set before the metadata is lost; set on the first metadata, it is the start.
      const seek = (): void => {
        forgetSeek();
        video.currentTime = startMs / 1_000;
      };
      pendingSeek = seek;
      video.addEventListener("loadedmetadata", seek);
      video.src = url;
    },
    destroy() {
      forgetSeek();
      video.removeEventListener("error", failed);
      listening = false;
      video.removeAttribute("src");
      // Without load() the element keeps the old source's buffer and network connection.
      video.load();
    },
  };
}

function noEngine(onFailure: (kind: EngineFailureKind) => void): Engine {
  return {
    async load() {
      onFailure("unsupported");
    },
    destroy() {},
  };
}

/** Picks the engine once per element: the browser does not change under a mounted player. */
export const createEngine: EngineFactory = (video, onFailure) => {
  switch (detect(video)) {
    case "native":
      return nativeEngine(video, onFailure);
    case "hls":
      return hlsEngine(video, onFailure);
    case "none":
      return noEngine(onFailure);
  }
};
