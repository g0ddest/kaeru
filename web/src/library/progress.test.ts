import { describe, expect, it, vi } from "vitest";
import type { EpisodeProgress } from "../domain/models";
import { ProgressStore, progressStore } from "./progress";

const KEY = "kaeru.progress";

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

function row(animeId: number, episode: number, positionMs = 600_000): EpisodeProgress {
  return { animeId, episode, positionMs, durationMs: 1_440_000, updatedAt: 1_000 + episode };
}

describe("ProgressStore", () => {
  it("keeps a title's positions in episode order and survives a reload of the page", () => {
    const storage = memoryStorage();
    const store = new ProgressStore(storage);
    store.put(row(1535, 3));
    store.put(row(1535, 1));
    store.put(row(21, 1));

    expect(store.of(1535).map((p) => p.episode)).toEqual([1, 3]);
    expect(store.of(21)).toEqual([row(21, 1)]);
    expect(store.of(999)).toEqual([]);
    expect(new ProgressStore(storage).of(1535)).toEqual([row(1535, 1), row(1535, 3)]);
  });

  it("keeps one row per episode, the latest", () => {
    const store = new ProgressStore(memoryStorage());
    store.put(row(1535, 2, 100_000));
    store.put(row(1535, 2, 700_000));

    expect(store.of(1535)).toEqual([row(1535, 2, 700_000)]);
  });

  it("hands out the same array until that title changes", () => {
    const store = new ProgressStore(memoryStorage());
    store.put(row(1535, 1));
    store.put(row(21, 1));
    const deathNote = store.of(1535);
    const other = store.of(21);

    expect(store.of(1535)).toBe(deathNote);
    store.put(row(1535, 2));
    expect(store.of(1535)).not.toBe(deathNote);
    expect(store.of(21)).toBe(other);
  });

  it("forgets the tapped episode and every later one of that title, and returns them", () => {
    const store = new ProgressStore(memoryStorage());
    for (const episode of [4, 5, 6, 7]) store.put(row(1535, episode));
    store.put(row(21, 6));

    expect(store.removeFrom(1535, 6)).toEqual([row(1535, 6), row(1535, 7)]);
    expect(store.of(1535).map((p) => p.episode)).toEqual([4, 5]);
    expect(store.of(21)).toEqual([row(21, 6)]);
    expect(store.removeFrom(1535, 9)).toEqual([]);
  });

  it("puts removed rows back", () => {
    const store = new ProgressStore(memoryStorage());
    store.put(row(1535, 5));
    store.put(row(1535, 6));
    const removed = store.removeFrom(1535, 5);

    store.restore(removed);

    expect(store.of(1535)).toEqual([row(1535, 5), row(1535, 6)]);
  });

  it("clears every position of every title, in memory and in storage", () => {
    const storage = memoryStorage();
    const store = new ProgressStore(storage);
    store.put(row(1535, 1));
    store.put(row(21, 4));

    store.clear();

    expect(store.of(1535)).toEqual([]);
    expect(store.of(21)).toEqual([]);
    expect(storage.getItem(KEY)).toBeNull();
    expect(new ProgressStore(storage).of(21)).toEqual([]);
  });

  it("tells subscribers about every change until they leave", () => {
    const store = new ProgressStore(memoryStorage());
    const listener = vi.fn();
    const leave = store.subscribe(listener);

    store.put(row(1535, 1));
    const removed = store.removeFrom(1535, 1);
    store.restore(removed);
    store.clear();
    expect(listener).toHaveBeenCalledTimes(4);

    store.removeFrom(1535, 1);
    store.restore([]);
    expect(listener).toHaveBeenCalledTimes(4);

    leave();
    store.put(row(1535, 2));
    expect(listener).toHaveBeenCalledTimes(4);
  });

  it("reads a broken or foreign store as empty and skips malformed rows", () => {
    const broken = memoryStorage();
    broken.setItem(KEY, "{not json");
    expect(new ProgressStore(broken).of(1535)).toEqual([]);

    const mixed = memoryStorage();
    mixed.setItem(KEY, JSON.stringify([row(1535, 1), { animeId: 1535, episode: "2" }, null, row(1535, 3)]));
    expect(new ProgressStore(mixed).of(1535).map((p) => p.episode)).toEqual([1, 3]);
  });

  it("keeps serving this tab when storage refuses to write", () => {
    const full = memoryStorage();
    full.setItem = () => {
      throw new DOMException("quota", "QuotaExceededError");
    };
    full.removeItem = () => {
      throw new DOMException("blocked", "SecurityError");
    };
    const store = new ProgressStore(full);

    store.put(row(1535, 1));
    expect(store.of(1535)).toEqual([row(1535, 1)]);
    store.clear();
    expect(store.of(1535)).toEqual([]);
  });

  it("picks up positions another tab wrote", () => {
    const store = new ProgressStore(window.localStorage);
    expect(store.of(1535)).toEqual([]);
    const listener = vi.fn();
    store.subscribe(listener);

    window.localStorage.setItem(KEY, JSON.stringify([row(1535, 8)]));
    window.dispatchEvent(new StorageEvent("storage", { key: KEY, storageArea: window.localStorage }));

    expect(listener).toHaveBeenCalledTimes(1);
    expect(store.of(1535)).toEqual([row(1535, 8)]);
  });

  it("the shared store lives in this browser's localStorage", () => {
    progressStore.put(row(1535, 2));
    expect(JSON.parse(window.localStorage.getItem(KEY) ?? "[]")).toEqual([row(1535, 2)]);
    progressStore.clear();
    expect(window.localStorage.getItem(KEY)).toBeNull();
  });
});
