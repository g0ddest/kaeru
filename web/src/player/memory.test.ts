// Vectors: android domain/playback/TranslationUsage.kt (usage counted per title) and the per-title
// WatchState track memory it reads.
import { describe, expect, it } from "vitest";
import type { Translation } from "./kodik";
import { dubUsage, rememberDub, rememberedDub } from "./memory";

const KEY = "kaeru.dubs";

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

describe("dub memory", () => {
  it("remembers the dub per title under kaeru.dubs", () => {
    const storage = memoryStorage();
    rememberDub(1535, { id: 610, title: "AniLibria.TV" }, storage);
    rememberDub(21, { id: 1978, title: "Studio Band" }, storage);

    expect(rememberedDub(1535, storage)).toEqual({ id: 610, title: "AniLibria.TV" });
    expect(rememberedDub(21, storage)).toEqual({ id: 1978, title: "Studio Band" });
    expect(rememberedDub(999, storage)).toBeNull();
    expect(JSON.parse(storage.getItem(KEY) ?? "")).toEqual({
      "1535": { id: 610, title: "AniLibria.TV" },
      "21": { id: 1978, title: "Studio Band" },
    });
  });

  it("keeps the latest dub of a title", () => {
    const storage = memoryStorage();
    rememberDub(1535, { id: 610, title: "AniLibria.TV" }, storage);
    rememberDub(1535, { id: 1978, title: "Studio Band" }, storage);

    expect(rememberedDub(1535, storage)).toEqual({ id: 1978, title: "Studio Band" });
  });

  it("keeps only the id and the title of a track", () => {
    const storage = memoryStorage();
    const track: Translation = { id: -1535, title: "Оригинал", type: "subtitles", episodesCount: 1 };
    rememberDub(1535, track, storage);

    // A film's sole track has a negative id.
    expect(rememberedDub(1535, storage)).toEqual({ id: -1535, title: "Оригинал" });
  });

  it("uses this browser's localStorage when no storage is given", () => {
    rememberDub(1535, { id: 610, title: "AniLibria.TV" });

    expect(window.localStorage.getItem(KEY)).not.toBeNull();
    expect(rememberedDub(1535)).toEqual({ id: 610, title: "AniLibria.TV" });
    expect(dubUsage()).toEqual(new Map([[610, 1]]));
  });

  it("counts how many titles remember each dub", () => {
    const storage = memoryStorage();
    rememberDub(1535, { id: 610, title: "AniLibria.TV" }, storage);
    rememberDub(21, { id: 610, title: "AniLibria.TV" }, storage);
    rememberDub(5114, { id: 1978, title: "Studio Band" }, storage);
    rememberDub(21, { id: 610, title: "AniLibria.TV" }, storage);

    expect(dubUsage(storage)).toEqual(new Map([[610, 2], [1978, 1]]));
    expect(dubUsage(memoryStorage())).toEqual(new Map());
  });

  it("reads a broken store as empty and skips entries it cannot read", () => {
    const storage = memoryStorage();
    for (const raw of ["", "{", "null", "[]", "42"]) {
      storage.setItem(KEY, raw);
      expect(rememberedDub(1535, storage)).toBeNull();
      expect(dubUsage(storage)).toEqual(new Map());
    }
    storage.setItem(
      KEY,
      JSON.stringify({ "1": { id: "610", title: "AniLibria.TV" }, "2": { id: 610 }, "3": { id: 1.5, title: "x" }, "4": { id: 610, title: "AniLibria.TV" } }),
    );
    expect(rememberedDub(1, storage)).toBeNull();
    expect(rememberedDub(2, storage)).toBeNull();
    expect(rememberedDub(3, storage)).toBeNull();
    expect(rememberedDub(4, storage)).toEqual({ id: 610, title: "AniLibria.TV" });
    expect(dubUsage(storage)).toEqual(new Map([[610, 1]]));
  });

  it("writes over a broken store", () => {
    const storage = memoryStorage();
    storage.setItem(KEY, "{");
    rememberDub(1535, { id: 610, title: "AniLibria.TV" }, storage);

    expect(rememberedDub(1535, storage)).toEqual({ id: 610, title: "AniLibria.TV" });
  });

  it("forgets quietly when storage is blocked", () => {
    const storage = refusing();

    expect(() => rememberDub(1535, { id: 610, title: "AniLibria.TV" }, storage)).not.toThrow();
    expect(rememberedDub(1535, storage)).toBeNull();
    expect(dubUsage(storage)).toEqual(new Map());
  });
});
