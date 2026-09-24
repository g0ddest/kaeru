// Shared test doubles. Kept out of production code: nothing under src/ except tests imports this file.
import type { AniSkip } from "../player/aniskip";
import type { EngineFactory } from "../player/engine";
import type { Kodik } from "../player/kodik";

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
