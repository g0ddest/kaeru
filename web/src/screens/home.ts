import { catalogueCard } from "../domain/feed";
import type { Card } from "../domain/feed";
import type { Anime, LibraryEntry } from "../domain/models";
import type { LibraryState } from "../library/library";

/** Catalogue rows are not about the viewer: six hours in memory, like Android's repository. */
export const CATALOGUE_TTL_MS = 6 * 60 * 60 * 1000;

/**
 * The answer is cached, not the attempt: a failed read stores nothing, so the next read tries again.
 */
export class CatalogueCache {
  private readonly stored = new Map<string, { titles: Anime[]; at: number }>();
  private readonly ttlMs: number;
  private readonly now: () => number;

  constructor(options: { ttlMs?: number; now?: () => number } = {}) {
    this.ttlMs = options.ttlMs ?? CATALOGUE_TTL_MS;
    this.now = options.now ?? Date.now;
  }

  async read(key: string, load: () => Promise<Anime[]>, force = false): Promise<Anime[]> {
    if (!force) {
      const hit = this.stored.get(key);
      if (hit && this.now() - hit.at < this.ttlMs) return hit.titles;
    }
    const titles = await load();
    this.stored.set(key, { titles, at: this.now() });
    return titles;
  }
}

/** Module-wide so leaving Home and coming back costs the catalogue nothing. */
export const catalogueCache = new CatalogueCache();

export type HomeContent =
  | { kind: "loading" }
  | { kind: "feed" }
  | { kind: "error"; message: string }
  | { kind: "first_sync" }
  | { kind: "empty" };

/**
 * Which screen Home is (HomeContent.kt). Only a finished sync may call a list empty, and a
 * failed refresh never takes away a feed the viewer is reading.
 */
export function homeContent(library: LibraryState, feedEmpty: boolean): HomeContent {
  if (library.kind === "idle") return { kind: "loading" };
  if (!feedEmpty) return { kind: "feed" };
  if (library.kind === "error") return { kind: "error", message: library.message };
  if (library.kind === "loading") return { kind: "first_sync" };
  return { kind: "empty" };
}

export function libraryEntries(library: LibraryState): readonly LibraryEntry[] {
  switch (library.kind) {
    case "idle":
      return [];
    case "ready":
      return library.entries;
    case "loading":
    case "error":
      return library.entries ?? [];
  }
}

export type DiscoverContent =
  | { kind: "titles"; cards: Card[] }
  | { kind: "loading" }
  | { kind: "empty" }
  | { kind: "failed" };

/** Titles win over loading; an empty list is an answer; null with nothing coming is a failure. */
export function discoverContent(titles: readonly Anime[] | null, loading: boolean): DiscoverContent {
  if (titles && titles.length > 0) return { kind: "titles", cards: uniqueById(titles).map(catalogueCard) };
  if (loading) return { kind: "loading" };
  if (titles) return { kind: "empty" };
  return { kind: "failed" };
}

/** «Популярно сейчас» has no controls, so with nothing to show it simply goes away. */
export function popularNowContent(titles: readonly Anime[] | null, loading: boolean): DiscoverContent | null {
  const content = discoverContent(titles, loading);
  return content.kind === "empty" || content.kind === "failed" ? null : content;
}

/** The season switcher stays once any season has answered, so a failed chip is never a dead end. */
export function seasonalContent(
  titles: readonly Anime[] | null,
  loading: boolean,
  anySeasonLoaded: boolean,
): DiscoverContent | null {
  const content = discoverContent(titles, loading);
  return content.kind === "failed" && !anySeasonLoaded ? null : content;
}

// The catalogue can repeat a title; React keys (and the viewer) need each one once.
function uniqueById(titles: readonly Anime[]): Anime[] {
  const seen = new Set<number>();
  return titles.filter((anime) => {
    if (seen.has(anime.id)) return false;
    seen.add(anime.id);
    return true;
  });
}
