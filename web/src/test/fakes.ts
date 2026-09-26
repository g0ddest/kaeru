import { setSyncEnabled } from "../library/prefs";
// Shared test doubles. Kept out of production code: nothing under src/ except tests imports this file.
import type { AniSkip } from "../player/aniskip";
import type { EngineFactory } from "../player/engine";
import type { Kodik } from "../player/kodik";
import type { Sync } from "../sync/service";

/** A Storage in memory: a fresh browser per test, and no crosstalk with window.localStorage. */
export function memoryStorage(): Storage {
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

const REFUSED = "playback is not used by this screen";

/**
 * The player's services for a screen that plays nothing: Kodik and AniSkip reject and the engine
 * factory throws, so a screen that starts asking for video fails its test instead of passing quietly.
 */
export function noPlayback(): { kodik: Kodik; aniskip: AniSkip; engine: EngineFactory } {
  const refuse = async (): Promise<never> => {
    throw new Error(REFUSED);
  };
  return {
    kodik: { translations: refuse, resolve: refuse },
    aniskip: { marks: refuse },
    engine: () => {
      throw new Error(REFUSED);
    },
  };
}

/** Viewing sync that sends and reads nothing: screens under test make no /sync requests. */
export function noSync(): Sync & { pushes: { keepalive?: boolean }[]; enabled: boolean[] } {
  const pushes: { keepalive?: boolean }[] = [];
  const enabled: boolean[] = [];
  return {
    pushes,
    enabled,
    start: () => {},
    stop: () => {},
    // Writes the setting as the real service does, so a screen reading it back sees the change.
    setEnabled: (on) => {
      enabled.push(on);
      setSyncEnabled(on);
    },
    push: (options = {}) => {
      pushes.push(options);
    },
  };
}
