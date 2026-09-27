import type { Anime } from "../domain/models";

/** «Смотреть украдкой» for one title, as the sync document carries it (spec 2026-09-26 §2). */
export interface SecretState {
  on: boolean;
  /** Episodes watched on the quiet: the count Shikimori is not told. */
  watched: number;
  /** When it last changed on some device, ms. */
  at: number;
}

/** The state plus the title's card, kept so «Украдкой» draws without Shikimori. */
export interface SecretTitle extends SecretState {
  anime: Anime | null;
}

export interface SecretStoreDeps {
  /** The browser's own by default; null keeps everything in memory. */
  storage?: Storage | null;
  accountId: () => number | null;
}

/** One JSON object: account id → anime id → SecretTitle. */
const KEY = "kaeru.secret";

function browserStorage(): Storage | null {
  try {
    return window.localStorage;
  } catch {
    return null;
  }
}

function record(value: unknown): Record<string, unknown> | null {
  return typeof value === "object" && value !== null && !Array.isArray(value) ? (value as Record<string, unknown>) : null;
}

const finite = (value: unknown): value is number => typeof value === "number" && Number.isFinite(value);

function readTitle(value: unknown): SecretTitle | null {
  const source = record(value);
  if (source === null) return null;
  const { on, watched, at } = source;
  if (typeof on !== "boolean" || !finite(watched) || !finite(at)) return null;
  const anime = record(source["anime"]);
  const card = anime !== null && finite(anime["id"]) && typeof anime["title"] === "string" ? (anime as unknown as Anime) : null;
  return { on, watched: Math.max(0, Math.floor(watched)), at, anime: card };
}

/**
 * Titles watched «украдкой», per account, in this browser. Nothing here reaches Shikimori; with sync
 * on, the service sends each local change as the document's `secret` (src/sync/service.ts).
 */
export class SecretStore {
  private readonly storage: Storage | null;
  private readonly accountId: () => number | null;
  /** Everything read or written, by account; storage is the truth across tabs and reloads. */
  private memory: Record<string, Record<string, SecretTitle>> | null = null;
  private readonly listeners = new Set<() => void>();
  private readonly watchers = new Set<(animeId: number, state: SecretState) => void>();

  constructor(deps: SecretStoreDeps) {
    this.storage = deps.storage === undefined ? browserStorage() : deps.storage;
    this.accountId = deps.accountId;
  }

  get(animeId: number): SecretTitle | undefined {
    return this.all().get(animeId);
  }

  /** Every title of the signed-in account, on or off. */
  all(): Map<number, SecretTitle> {
    const account = this.accountId();
    const out = new Map<number, SecretTitle>();
    if (account === null) return out;
    for (const [id, title] of Object.entries(this.load()[String(account)] ?? {})) out.set(Number(id), title);
    return out;
  }

  /**
   * A new state for the title. `anime` caches its card (a missing one keeps what is known); `quiet`
   * is for a state that came from the server and must not be sent back.
   */
  set(animeId: number, state: SecretState, anime?: Anime | null, options: { quiet?: boolean } = {}): void {
    const account = this.accountId();
    if (account === null) return;
    const all = this.load();
    const titles = { ...all[String(account)] };
    const known = titles[String(animeId)]?.anime ?? null;
    titles[String(animeId)] = { on: state.on, watched: state.watched, at: state.at, anime: anime ?? known };
    all[String(account)] = titles;
    this.save(all);
    if (options.quiet !== true) {
      for (const watcher of [...this.watchers]) watcher(animeId, { on: state.on, watched: state.watched, at: state.at });
    }
    this.notify();
  }

  /** The title's card, fetched for a state that arrived without one. */
  card(animeId: number, anime: Anime): void {
    const held = this.get(animeId);
    if (held === undefined) return;
    this.set(animeId, held, anime, { quiet: true });
  }

  subscribe(listener: () => void): () => void {
    this.listeners.add(listener);
    return () => {
      this.listeners.delete(listener);
    };
  }

  /** Hears local changes only: what sync sends. */
  watch(watcher: (animeId: number, state: SecretState) => void): () => void {
    this.watchers.add(watcher);
    return () => {
      this.watchers.delete(watcher);
    };
  }

  private load(): Record<string, Record<string, SecretTitle>> {
    if (this.storage === null) return (this.memory ??= {});
    const out: Record<string, Record<string, SecretTitle>> = {};
    try {
      const root = record(JSON.parse(this.storage.getItem(KEY) ?? "null"));
      for (const [account, raw] of Object.entries(root ?? {})) {
        const titles: Record<string, SecretTitle> = {};
        for (const [id, value] of Object.entries(record(raw) ?? {})) {
          const title = /^\d{1,9}$/.test(id) ? readTitle(value) : null;
          if (title !== null) titles[id] = title;
        }
        out[account] = titles;
      }
    } catch {
      // Broken or blocked storage reads as nothing kept.
    }
    return out;
  }

  private save(all: Record<string, Record<string, SecretTitle>>): void {
    if (this.storage === null) {
      this.memory = all;
      return;
    }
    try {
      this.storage.setItem(KEY, JSON.stringify(all));
    } catch {
      // Storage refused: this change lives only until the page goes away.
    }
  }

  private notify(): void {
    for (const listener of [...this.listeners]) listener();
  }
}
