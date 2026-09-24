import { useSyncExternalStore } from "react";
import { ApiError, errorMessage } from "../api/http";
import type { Shikimori } from "../api/shikimori";
import type { authorized } from "../auth/session";
import type { Anime, EpisodeProgress, LibraryEntry, ListStatus, UserRate } from "../domain/models";
import type { ProgressStore } from "./progress";

export type LibraryState =
  | { kind: "idle" }
  | { kind: "loading"; entries: LibraryEntry[] | null }
  | { kind: "ready"; entries: LibraryEntry[] }
  | { kind: "error"; message: string; entries: LibraryEntry[] | null };

export interface LibraryDeps {
  shikimori: Shikimori;
  authorized: typeof authorized;
  accountId: () => number | null;
  progress: ProgressStore;
  /** Clock for a rate's updatedAt and for the details TTL; tests pin it. */
  now?: () => number;
}

/** At most this many titles get their details per load (Android DETAILS_PER_REFRESH). */
export const DETAILS_PER_LOAD = 25;
/** Fetched details stay fresh this long (Android detailsTtl). */
export const DETAILS_TTL_MS = 6 * 60 * 60 * 1000;

/** What one queued write does to the entry on screen before Shikimori answers. */
type Optimistic = (entry: LibraryEntry | undefined) => LibraryEntry | undefined;

interface Sent<T> {
  /** The rate Shikimori now holds; undefined when the write changed nothing. */
  entry: LibraryEntry | undefined;
  result: T;
}

const NOTHING_TO_UNDO = async (): Promise<void> => {};

function entriesOf(state: LibraryState): LibraryEntry[] | null {
  return state.kind === "idle" ? null : state.entries;
}

// Android AnimeEntity.mergeShort: a list card refreshes what it carries; details it lacks stay.
function mergeShort(known: Anime, card: Anime): Anime {
  return {
    ...known,
    title: card.title,
    originalTitle: card.originalTitle,
    posterUrl: card.posterUrl ?? known.posterUrl,
    status: card.status,
    episodes: card.episodes,
    episodesAired: card.episodesAired,
    score: card.score ?? known.score,
    year: card.year ?? known.year,
    kind: card.kind ?? known.kind,
  };
}

// Details are the fuller card: air date, studios, description, screenshot. Artwork they lack stays.
function withDetails(known: Anime, details: Anime): Anime {
  return {
    ...details,
    posterUrl: details.posterUrl ?? known.posterUrl,
    backdropUrl: details.backdropUrl ?? known.backdropUrl,
  };
}

// Decision 6: a count never runs past what the catalogue announced.
function countFor(anime: Anime, episode: number): number {
  return anime.episodes > 0 ? Math.min(episode, anime.episodes) : episode;
}

// Watching it is the fact on the ground; planned or shelved is what disagrees (MarkEpisodeWatched.kt).
function picksUp(rate: UserRate): boolean {
  return rate.status === "planned" || rate.status === "on_hold";
}

function completes(anime: Anime, counted: number): boolean {
  return anime.episodes > 0 && counted >= anime.episodes;
}

// Only titles still airing that the viewer follows need the next air date (ShikimoriLibraryRepository.kt).
function wantsDetails(entry: LibraryEntry): boolean {
  const following = entry.rate.status === "watching" || entry.rate.status === "rewatching";
  return following && entry.anime.status === "ongoing";
}

export class Library {
  private readonly deps: LibraryDeps;
  private readonly now: () => number;
  private readonly listeners = new Set<() => void>();
  /** What Shikimori confirmed, per anime id. */
  private confirmed = new Map<number, LibraryEntry>();
  /** Writes not yet answered, per anime id, applied over `confirmed` in order. */
  private readonly pending = new Map<number, Optimistic[]>();
  private readonly tails = new Map<number, Promise<void>>();
  private readonly writeCounts = new Map<number, number>();
  /** Details fetched this session and when; catalogue data, so it is not tied to an account. */
  private readonly detailed = new Map<number, { at: number; anime: Anime }>();
  private view = new Map<number, LibraryEntry>();
  private current: LibraryState = { kind: "idle" };
  private loadedFor: number | null = null;
  private inflight: Promise<void> | null = null;

  constructor(deps: LibraryDeps) {
    this.deps = deps;
    this.now = deps.now ?? Date.now;
  }

  readonly state = (): LibraryState => this.current;

  readonly subscribe = (listener: () => void): (() => void) => {
    this.listeners.add(listener);
    return () => {
      this.listeners.delete(listener);
    };
  };

  entry(animeId: number): LibraryEntry | undefined {
    return this.view.get(animeId);
  }

  /**
   * Reads the list, then refreshes details for the ongoing titles being watched. The state turns
   * ready as soon as the list is in; the returned promise settles once the details pass is done.
   */
  load(): Promise<void> {
    if (this.inflight) return this.inflight;
    const run = this.refresh().finally(() => {
      this.inflight = null;
    });
    this.inflight = run;
    return run;
  }

  setStatus(anime: Anime, status: ListStatus): Promise<void> {
    const shown = this.view.get(anime.id);
    if (shown && shown.rate.status === status && !this.pending.has(anime.id)) return Promise.resolve();
    const at = this.now();
    return this.write<undefined>(
      anime.id,
      (entry) => {
        if (!entry) return { anime, rate: { id: -anime.id, animeId: anime.id, status, episodes: 0, updatedAt: at } };
        if (entry.rate.status === status) return entry;
        return { anime: entry.anime, rate: { ...entry.rate, status, updatedAt: at } };
      },
      async (confirmed) => {
        if (confirmed && confirmed.rate.status === status) return { entry: undefined, result: undefined };
        if (!confirmed) {
          const userId = this.account();
          const created = await this.deps.authorized((token) =>
            this.deps.shikimori.createRate(token, userId, anime.id, { status }),
          );
          return { entry: { anime, rate: this.statusFrom(created, anime.id, status) }, result: undefined };
        }
        const patched = await this.patch(confirmed.rate.id, { status });
        return { entry: { anime: confirmed.anime, rate: this.statusFrom(patched, anime.id, status) }, result: undefined };
      },
    );
  }

  markWatched(anime: Anime, episode: number): Promise<{ suggestCompleted: boolean }> {
    const target = countFor(anime, episode);
    if (target < 1) return Promise.resolve({ suggestCompleted: false });
    const at = this.now();
    return this.write<{ suggestCompleted: boolean }>(
      anime.id,
      (entry) => {
        if (!entry) {
          return { anime, rate: { id: -anime.id, animeId: anime.id, status: "watching", episodes: target, updatedAt: at } };
        }
        const status: ListStatus = picksUp(entry.rate) ? "watching" : entry.rate.status;
        const episodes = Math.max(entry.rate.episodes, target);
        if (status === entry.rate.status && episodes === entry.rate.episodes) return entry;
        return { anime: entry.anime, rate: { ...entry.rate, status, episodes, updatedAt: at } };
      },
      async (confirmed) => {
        if (!confirmed) {
          // No rate to PATCH yet: one POST carries both the status and the count.
          const userId = this.account();
          const created = await this.deps.authorized((token) =>
            this.deps.shikimori.createRate(token, userId, anime.id, { status: "watching", episodes: target }),
          );
          return {
            entry: { anime, rate: this.statusFrom(created, anime.id, "watching") },
            result: { suggestCompleted: completes(anime, target) },
          };
        }
        let entry = confirmed;
        if (picksUp(entry.rate)) {
          const patched = await this.patch(entry.rate.id, { status: "watching" });
          entry = { anime: entry.anime, rate: this.statusFrom(patched, anime.id, "watching") };
          // Landed even if the count below fails: the title is being watched either way.
          this.confirm(anime.id, entry);
        }
        if (entry.rate.episodes >= target) return { entry, result: { suggestCompleted: false } };
        const patched = await this.patch(entry.rate.id, { episodes: target });
        return {
          entry: { anime: entry.anime, rate: this.countFrom(entry.rate, patched) },
          result: { suggestCompleted: completes(anime, target) },
        };
      },
    );
  }

  markUnwatched(anime: Anime, episode: number): Promise<() => Promise<void>> {
    return this.write<() => Promise<void>>(
      anime.id,
      (entry) =>
        entry && episode >= 1 && entry.rate.episodes >= episode
          ? { anime: entry.anime, rate: { ...entry.rate, episodes: episode - 1 } }
          : entry,
      async (confirmed) => {
        if (!confirmed || episode < 1 || confirmed.rate.episodes < episode) {
          return { entry: undefined, result: NOTHING_TO_UNDO };
        }
        const previousCount = confirmed.rate.episodes;
        const patched = await this.patch(confirmed.rate.id, { episodes: episode - 1 });
        const entry: LibraryEntry = { anime: confirmed.anime, rate: this.countFrom(confirmed.rate, patched) };
        // Only once the count is down: a refused write keeps the viewer's place (MarkEpisodeUnwatched.kt).
        const forgotten = this.deps.progress.removeFrom(anime.id, episode);
        return { entry, result: () => this.restoreCount(anime, previousCount, forgotten) };
      },
    );
  }

  // Undo gives back the count that stood before, not the tapped episode: 6 and 7 went too.
  private restoreCount(anime: Anime, previousCount: number, forgotten: readonly EpisodeProgress[]): Promise<void> {
    return this.write<undefined>(
      anime.id,
      (entry) =>
        entry && entry.rate.episodes < previousCount
          ? { anime: entry.anime, rate: { ...entry.rate, episodes: previousCount } }
          : entry,
      async (confirmed) => {
        let entry: LibraryEntry | undefined;
        if (confirmed && confirmed.rate.episodes < previousCount) {
          const patched = await this.patch(confirmed.rate.id, { episodes: previousCount });
          entry = { anime: confirmed.anime, rate: this.countFrom(confirmed.rate, patched) };
        }
        this.deps.progress.restore(forgotten);
        return { entry, result: undefined };
      },
    );
  }

  private async refresh(): Promise<void> {
    const userId = this.deps.accountId();
    if (userId === null) {
      this.reset();
      return;
    }
    if (userId !== this.loadedFor) {
      // Another account's list must never show under this one.
      this.confirmed = new Map();
      this.loadedFor = userId;
      this.current = { kind: "loading", entries: null };
    } else {
      this.current = { kind: "loading", entries: entriesOf(this.current) };
    }
    this.publish();
    const pendingBefore = new Set(this.pending.keys());
    const countsBefore = new Map(this.writeCounts);
    try {
      const rates = await this.deps.authorized((token) => this.deps.shikimori.userRates(userId, token));
      const ids = [...new Set(rates.map((rate) => rate.animeId))];
      const cards = ids.length > 0 ? await this.deps.shikimori.byIds(ids) : [];
      if (this.deps.accountId() !== userId) {
        this.reset();
        return;
      }
      // A title written to while the list was in flight keeps what this tab confirmed:
      // the list may have been read before that write landed (ShikimoriLibraryRepository.kt).
      const dirty = new Set<number>([...pendingBefore, ...this.pending.keys()]);
      for (const [id, count] of this.writeCounts) {
        if (countsBefore.get(id) !== count) dirty.add(id);
      }
      const cardsById = new Map(cards.map((card) => [card.id, card]));
      const next = new Map<number, LibraryEntry>();
      for (const rate of rates) {
        if (dirty.has(rate.animeId)) continue;
        const card = cardsById.get(rate.animeId);
        if (!card) continue;
        const known = this.confirmed.get(rate.animeId)?.anime ?? this.detailed.get(rate.animeId)?.anime;
        next.set(rate.animeId, { anime: known ? mergeShort(known, card) : card, rate });
      }
      for (const id of dirty) {
        const local = this.confirmed.get(id);
        if (local) next.set(id, local);
      }
      this.confirmed = next;
      this.current = { kind: "ready", entries: [] };
    } catch (error) {
      this.current = { kind: "error", message: errorMessage(error), entries: entriesOf(this.current) };
      this.publish();
      return;
    }
    this.publish();
    await this.refreshDetails(userId);
  }

  // List cards carry no air date, studios or screenshots: «Скоро» and «N серия выйдет завтра»
  // need details. Up to 25 per load, the longest-waiting first, each fresh for 6 h (Android).
  private async refreshDetails(userId: number): Promise<void> {
    const staleBefore = this.now() - DETAILS_TTL_MS;
    const due = [...this.confirmed.values()]
      .filter(wantsDetails)
      // Never fetched sorts ahead of everything: a new title deserves its air date first.
      .map((entry) => ({ id: entry.anime.id, at: this.detailed.get(entry.anime.id)?.at ?? Number.NEGATIVE_INFINITY }))
      .filter((title) => title.at <= staleBefore)
      .sort((a, b) => (a.at === b.at ? 0 : a.at < b.at ? -1 : 1))
      .slice(0, DETAILS_PER_LOAD);
    for (const { id } of due) {
      if (this.loadedFor !== userId || this.deps.accountId() !== userId) return;
      let details: Anime;
      try {
        details = await this.deps.shikimori.details(id);
      } catch {
        // Ignored: the list card still draws the title, and the next load asks again.
        continue;
      }
      this.detailed.set(id, { at: this.now(), anime: details });
      const held = this.confirmed.get(id);
      if (held && this.loadedFor === userId) {
        this.confirmed.set(id, { anime: withDetails(held.anime, details), rate: held.rate });
        this.publish();
      }
    }
  }

  private reset(): void {
    this.loadedFor = null;
    this.confirmed = new Map();
    this.current = { kind: "idle" };
    this.publish();
  }

  private write<T>(
    animeId: number,
    optimistic: Optimistic,
    send: (confirmed: LibraryEntry | undefined) => Promise<Sent<T>>,
  ): Promise<T> {
    this.writeCounts.set(animeId, (this.writeCounts.get(animeId) ?? 0) + 1);
    const ops = this.pending.get(animeId) ?? [];
    ops.push(optimistic);
    this.pending.set(animeId, ops);
    this.publish();
    // One queue per title, so an older value never lands after a newer one (decision 7).
    const previous = this.tails.get(animeId) ?? Promise.resolve();
    const run = previous.then(async () => {
      try {
        const sent = await send(this.confirmed.get(animeId));
        this.confirm(animeId, sent.entry);
        return sent.result;
      } finally {
        this.settle(animeId, optimistic);
      }
    });
    const tail = run.then(
      () => undefined,
      () => undefined,
    );
    this.tails.set(animeId, tail);
    void tail.then(() => {
      if (this.tails.get(animeId) === tail) this.tails.delete(animeId);
    });
    return run;
  }

  // A write changes the rate. The title keeps what the list or a details pass gave it meanwhile.
  private confirm(animeId: number, entry: LibraryEntry | undefined): void {
    if (!entry) return;
    const held = this.confirmed.get(animeId);
    this.confirmed.set(animeId, held ? { anime: held.anime, rate: entry.rate } : entry);
  }

  // The write is answered either way: its optimistic layer goes, which is the revert on failure.
  private settle(animeId: number, op: Optimistic): void {
    const ops = this.pending.get(animeId) ?? [];
    const index = ops.indexOf(op);
    if (index >= 0) ops.splice(index, 1);
    if (ops.length === 0) this.pending.delete(animeId);
    this.publish();
  }

  private publish(): void {
    const view = new Map<number, LibraryEntry>();
    for (const id of new Set([...this.confirmed.keys(), ...this.pending.keys()])) {
      let entry = this.confirmed.get(id);
      for (const apply of this.pending.get(id) ?? []) entry = apply(entry);
      if (entry) view.set(id, entry);
    }
    this.view = view;
    const entries = [...view.values()];
    const state = this.current;
    if (state.kind === "ready") this.current = { kind: "ready", entries };
    else if (state.kind === "loading" && state.entries !== null) this.current = { kind: "loading", entries };
    else if (state.kind === "error" && state.entries !== null) {
      this.current = { kind: "error", message: state.message, entries };
    }
    for (const listener of [...this.listeners]) listener();
  }

  private account(): number {
    const id = this.deps.accountId();
    // Signed out between the click and the write: say so the way an expired token would.
    if (id === null) throw new ApiError(401);
    return id;
  }

  private patch(rateId: number, fields: { status?: ListStatus; episodes?: number }): Promise<UserRate> {
    return this.deps.authorized((token) => this.deps.shikimori.updateRate(token, rateId, fields));
  }

  // Android setStatus: the server's rate, carrying the status this tab chose.
  private statusFrom(server: UserRate, animeId: number, status: ListStatus): UserRate {
    return { ...server, animeId, status, updatedAt: this.now() };
  }

  // Android setEpisodes: the local rate with the count the server stored.
  private countFrom(local: UserRate, server: UserRate): UserRate {
    return { ...local, id: server.id, episodes: server.episodes, updatedAt: this.now() };
  }
}

export function useLibrary(library: Library): LibraryState {
  return useSyncExternalStore(library.subscribe, library.state, library.state);
}
