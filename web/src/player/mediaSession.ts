import { SEEK_STEP_MS } from "./rules";

/** What the lock screen, the headset buttons and Chrome's picture-in-picture window show and do. */
export interface NowPlaying {
  /** The episode, «7 серия». */
  title: string;
  /** The anime. */
  artist: string;
  /** The dub. */
  album: string;
  /** An absolute poster URL. */
  artwork: string | null;
}

export interface MediaSessionActions {
  play(): void;
  pause(): void;
  seekBy(ms: number): void;
  seekTo(ms: number): void;
  /** Null hides the next button: there is no next episode yet. */
  next: (() => void) | null;
}

const ACTIONS: readonly MediaSessionAction[] = ["play", "pause", "seekbackward", "seekforward", "seekto", "nexttrack"];

// Safari before 15 has none; jsdom neither.
function session(): MediaSession | null {
  return "mediaSession" in navigator ? navigator.mediaSession : null;
}

/** One action at a time: a browser that does not know one throws, and must not lose the rest. */
function handle(media: MediaSession, action: MediaSessionAction, handler: MediaSessionActionHandler | null): void {
  try {
    media.setActionHandler(action, handler);
  } catch {
    // Unsupported here: the system simply offers no such control.
  }
}

/** Seconds as the system gives them; the player's own step when it gives none. */
function offsetMs(details: MediaSessionActionDetails): number {
  const seconds = details.seekOffset;
  return typeof seconds === "number" && Number.isFinite(seconds) && seconds > 0 ? seconds * 1_000 : SEEK_STEP_MS;
}

/** Hands the system controls the episode playing now, and the player's hands for its buttons. */
export function showInMediaSession(now: NowPlaying, actions: MediaSessionActions): void {
  const media = session();
  if (media === null) return;
  if (typeof MediaMetadata === "function") {
    try {
      media.metadata = new MediaMetadata({
        title: now.title,
        artist: now.artist,
        album: now.album,
        artwork: now.artwork === null ? [] : [{ src: now.artwork }],
      });
    } catch {
      // An artwork URL the browser will not parse: the controls work without a name.
    }
  }
  handle(media, "play", () => actions.play());
  handle(media, "pause", () => actions.pause());
  handle(media, "seekbackward", (details) => actions.seekBy(-offsetMs(details)));
  handle(media, "seekforward", (details) => actions.seekBy(offsetMs(details)));
  handle(media, "seekto", (details) => {
    if (typeof details.seekTime === "number" && Number.isFinite(details.seekTime)) actions.seekTo(details.seekTime * 1_000);
  });
  const next = actions.next;
  handle(media, "nexttrack", next === null ? null : () => next());
}

/**
 * The system's progress bar. Only against a finite length: setPositionState throws on NaN and
 * Infinity, and on a position past the end.
 */
export function showMediaPosition(positionMs: number, durationMs: number, playbackRate = 1): void {
  const media = session();
  if (media === null || typeof media.setPositionState !== "function") return;
  if (!Number.isFinite(durationMs) || durationMs <= 0 || !Number.isFinite(positionMs)) return;
  const duration = durationMs / 1_000;
  try {
    media.setPositionState({ duration, position: Math.min(duration, Math.max(0, positionMs / 1_000)), playbackRate });
  } catch {
    // A rate the browser refuses: the bar simply does not move.
  }
}

/** The page is leaving the player: the system controls must not stay bound to it. */
export function clearMediaSession(): void {
  const media = session();
  if (media === null) return;
  media.metadata = null;
  for (const action of ACTIONS) handle(media, action, null);
  try {
    media.setPositionState?.();
  } catch {
    // Nothing to clear.
  }
}
