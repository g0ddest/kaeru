import { describe, expect, it } from "vitest";
import { THRESHOLD_CHOICES, setWatchedThreshold, watchedThreshold } from "./prefs";

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
