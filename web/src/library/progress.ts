import type { EpisodeProgress } from "../domain/models";

/** One JSON array of every position this browser holds, across titles. */
const KEY = "kaeru.progress";
const FIELDS = ["animeId", "episode", "positionMs", "durationMs", "updatedAt"] as const;

function rowKey(animeId: number, episode: number): string {
  return `${animeId}:${episode}`;
}

function isRow(value: unknown): value is EpisodeProgress {
  if (typeof value !== "object" || value === null) return false;
  const record = value as Record<string, unknown>;
  return FIELDS.every((field) => {
    const item = record[field];
    return typeof item === "number" && Number.isFinite(item);
  });
}

function browserStorage(): Storage | null {
  try {
    return window.localStorage;
  } catch {
    // Blocked site data makes the getter itself throw.
    return null;
  }
}

/**
 * Where the viewer stopped inside each episode. Per browser only, as positions are per device on
 * Android and iOS; Shikimori holds just the watched count.
 */
export class ProgressStore {
  private readonly storage: Storage | null;
  private rows: Map<string, EpisodeProgress> | null = null;
  private readonly byAnime = new Map<number, EpisodeProgress[]>();
  private readonly listeners = new Set<() => void>();

  constructor(storage?: Storage) {
    this.storage = storage ?? browserStorage();
    if (typeof window !== "undefined") window.addEventListener("storage", this.onStorage);
  }

  of(animeId: number): EpisodeProgress[] {
    const cached = this.byAnime.get(animeId);
    if (cached) return cached;
    const rows = [...this.all().values()]
      .filter((row) => row.animeId === animeId)
      .sort((a, b) => a.episode - b.episode);
    // The same array until this title changes, so useSyncExternalStore can compare snapshots.
    this.byAnime.set(animeId, rows);
    return rows;
  }

  put(p: EpisodeProgress): void {
    this.all().set(rowKey(p.animeId, p.episode), { ...p });
    this.commit([p.animeId]);
  }

  removeFrom(animeId: number, episode: number): EpisodeProgress[] {
    const removed = this.of(animeId).filter((row) => row.episode >= episode);
    if (removed.length === 0) return [];
    const rows = this.all();
    for (const row of removed) rows.delete(rowKey(row.animeId, row.episode));
    this.commit([animeId]);
    return removed;
  }

  restore(rows: readonly EpisodeProgress[]): void {
    if (rows.length === 0) return;
    const all = this.all();
    for (const row of rows) all.set(rowKey(row.animeId, row.episode), { ...row });
    this.commit(rows.map((row) => row.animeId));
  }

  /** Forgets every position in this browser: sign-out clears the local cache. */
  clear(): void {
    this.rows = new Map();
    this.byAnime.clear();
    try {
      this.storage?.removeItem(KEY);
    } catch {
      // Blocked storage: this tab already reads as empty.
    }
    this.emit();
  }

  readonly subscribe = (listener: () => void): (() => void) => {
    this.listeners.add(listener);
    return () => {
      this.listeners.delete(listener);
    };
  };

  private all(): Map<string, EpisodeProgress> {
    if (this.rows === null) this.rows = this.read();
    return this.rows;
  }

  private read(): Map<string, EpisodeProgress> {
    const rows = new Map<string, EpisodeProgress>();
    try {
      const raw = this.storage?.getItem(KEY);
      if (!raw) return rows;
      const parsed: unknown = JSON.parse(raw);
      if (!Array.isArray(parsed)) return rows;
      for (const item of parsed) {
        if (isRow(item)) rows.set(rowKey(item.animeId, item.episode), item);
      }
    } catch {
      // A store some other build broke reads as empty rather than taking the page down.
    }
    return rows;
  }

  private commit(animeIds: Iterable<number>): void {
    for (const id of animeIds) this.byAnime.delete(id);
    try {
      this.storage?.setItem(KEY, JSON.stringify([...this.all().values()]));
    } catch {
      // Quota or blocked storage: the rows still serve this tab.
    }
    this.emit();
  }

  private emit(): void {
    for (const listener of [...this.listeners]) listener();
  }

  // Another tab wrote positions: drop the parsed copy and read again on next use.
  private readonly onStorage = (event: StorageEvent): void => {
    if (event.storageArea !== this.storage) return;
    if (event.key !== null && event.key !== KEY) return;
    this.rows = null;
    this.byAnime.clear();
    this.emit();
  };
}

export const progressStore = new ProgressStore();
