import { describe, expect, it } from "vitest";
import {
  QUALITY_CHOICES,
  THRESHOLD_CHOICES,
  autoplayNext,
  defaultQuality,
  setAutoplayNext,
  setDefaultQuality,
  setSkipEnding,
  setWatchedThreshold,
  skipEnding,
  watchedThreshold,
} from "./prefs";

const KEY = "kaeru.threshold";

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

function refusing(): Storage {
  const storage = memoryStorage();
  storage.getItem = () => {
    throw new DOMException("blocked", "SecurityError");
  };
  storage.setItem = () => {
    throw new DOMException("blocked", "SecurityError");
  };
  storage.removeItem = () => {
    throw new DOMException("blocked", "SecurityError");
  };
  return storage;
}

describe("watched threshold", () => {
  it("offers Android's four choices", () => {
    expect(THRESHOLD_CHOICES).toEqual([0.8, 0.85, 0.9, 0.95]);
  });

  it("is 0.9 until the viewer picks another", () => {
    expect(watchedThreshold(memoryStorage())).toBe(0.9);
  });

  it("keeps the viewer's choice under kaeru.threshold", () => {
    const storage = memoryStorage();
    setWatchedThreshold(0.85, storage);
    expect(storage.getItem(KEY)).toBe("0.85");
    expect(watchedThreshold(storage)).toBe(0.85);
  });

  it("uses this browser's localStorage when no storage is given", () => {
    setWatchedThreshold(0.95);
    expect(window.localStorage.getItem(KEY)).toBe("0.95");
    expect(watchedThreshold()).toBe(0.95);
  });

  it("holds any value inside Android's 0.5–1 range", () => {
    const storage = memoryStorage();
    setWatchedThreshold(0.3, storage);
    expect(watchedThreshold(storage)).toBe(0.5);
    setWatchedThreshold(1.4, storage);
    expect(watchedThreshold(storage)).toBe(1);
    storage.setItem(KEY, "0.2");
    expect(watchedThreshold(storage)).toBe(0.5);
  });

  it("never stores something that is not a number", () => {
    const storage = memoryStorage();
    setWatchedThreshold(0.8, storage);
    setWatchedThreshold(Number.NaN, storage);
    setWatchedThreshold(Number.POSITIVE_INFINITY, storage);
    expect(watchedThreshold(storage)).toBe(0.8);
  });

  it("reads garbage as the default", () => {
    const storage = memoryStorage();
    for (const raw of ["", "  ", "много", "NaN"]) {
      storage.setItem(KEY, raw);
      expect(watchedThreshold(storage)).toBe(0.9);
    }
  });

  it("falls back to the default when storage is blocked", () => {
    const storage = refusing();
    expect(() => setWatchedThreshold(0.8, storage)).not.toThrow();
    expect(watchedThreshold(storage)).toBe(0.9);
  });
});

describe("autoplay next episode", () => {
  it("is on until the viewer turns it off", () => {
    expect(autoplayNext(memoryStorage())).toBe(true);
  });

  it("keeps the switch under kaeru.autoplay", () => {
    const storage = memoryStorage();
    setAutoplayNext(false, storage);
    expect(storage.getItem("kaeru.autoplay")).toBe("false");
    expect(autoplayNext(storage)).toBe(false);
    setAutoplayNext(true, storage);
    expect(autoplayNext(storage)).toBe(true);
  });

  it("uses this browser's localStorage when no storage is given", () => {
    setAutoplayNext(false);
    expect(autoplayNext()).toBe(false);
  });

  it("reads garbage and blocked storage as on", () => {
    const storage = memoryStorage();
    for (const raw of ["", "0", "no", "False", "{}"]) {
      storage.setItem("kaeru.autoplay", raw);
      expect(autoplayNext(storage)).toBe(true);
    }
    expect(() => setAutoplayNext(false, refusing())).not.toThrow();
    expect(autoplayNext(refusing())).toBe(true);
  });
});

describe("skip the ending", () => {
  it("is off until the viewer turns it on", () => {
    expect(skipEnding(memoryStorage())).toBe(false);
  });

  it("keeps the switch under kaeru.skipEnding", () => {
    const storage = memoryStorage();
    setSkipEnding(true, storage);
    expect(storage.getItem("kaeru.skipEnding")).toBe("true");
    expect(skipEnding(storage)).toBe(true);
    setSkipEnding(false, storage);
    expect(skipEnding(storage)).toBe(false);
  });

  it("uses this browser's localStorage when no storage is given", () => {
    setSkipEnding(true);
    expect(skipEnding()).toBe(true);
  });

  it("reads garbage and blocked storage as off", () => {
    const storage = memoryStorage();
    for (const raw of ["", "1", "yes", "True", "{}"]) {
      storage.setItem("kaeru.skipEnding", raw);
      expect(skipEnding(storage)).toBe(false);
    }
    expect(() => setSkipEnding(true, refusing())).not.toThrow();
    expect(skipEnding(refusing())).toBe(false);
  });
});

describe("default quality", () => {
  it("offers «Авто» and the four heights", () => {
    expect(QUALITY_CHOICES).toEqual([null, 360, 480, 720, 1080]);
  });

  it("is «Авто» until the viewer picks a height", () => {
    expect(defaultQuality(memoryStorage())).toBeNull();
  });

  it("keeps the height under kaeru.quality and forgets it for «Авто»", () => {
    const storage = memoryStorage();
    setDefaultQuality(720, storage);
    expect(storage.getItem("kaeru.quality")).toBe("720");
    expect(defaultQuality(storage)).toBe(720);
    setDefaultQuality(null, storage);
    expect(storage.getItem("kaeru.quality")).toBeNull();
    expect(defaultQuality(storage)).toBeNull();
  });

  it("uses this browser's localStorage when no storage is given", () => {
    setDefaultQuality(1080);
    expect(defaultQuality()).toBe(1080);
  });

  it("never stores a height the settings do not offer", () => {
    const storage = memoryStorage();
    setDefaultQuality(480, storage);
    for (const value of [240, 2160, 0, -720, 720.5, Number.NaN]) setDefaultQuality(value, storage);
    expect(defaultQuality(storage)).toBe(480);
  });

  it("reads garbage and unknown heights as «Авто»", () => {
    const storage = memoryStorage();
    for (const raw of ["", "  ", "auto", "720p", "0", "-720", "240", "2160", "NaN"]) {
      storage.setItem("kaeru.quality", raw);
      expect(defaultQuality(storage)).toBeNull();
    }
  });

  it("falls back to «Авто» when storage is blocked", () => {
    const storage = refusing();
    expect(() => setDefaultQuality(720, storage)).not.toThrow();
    expect(() => setDefaultQuality(null, storage)).not.toThrow();
    expect(defaultQuality(storage)).toBeNull();
  });
});
