import { setSyncEnabled, syncEnabled } from "../library/prefs";
import type { EpisodeProgress, LibraryEntry, ListStatus } from "../domain/models";
import type { Library } from "../library/library";
import type { ProgressStore } from "../library/progress";
import type { SecretState, SecretStore } from "../library/secret";
import {
  forgetDubs,
  mergeDub,
  onDubRemembered,
  rememberedDubs,
  type RememberedDub,
  type StampedDub,
} from "../player/memory";
import type { SyncClient, SyncTitle, SyncTitles } from "./client";
import {
  isEmpty,
  MAX_DUB_TITLE,
  merge,
  newer,
  seed as seedTitles,
  wire,
  without,
  type LocalSyncState,
  type SyncNewer,
} from "./rules";

/** What the player tells sync: it just paused, left, or moved on, so the batch goes now. */
export interface SyncPort {
  push(options?: { keepalive?: boolean }): void;
}

export interface Sync extends SyncPort {
  /** Signed-in shell mounted: read the document, then follow local changes. */
  start(): void;
  stop(): void;
  /** «Синхронизация между устройствами» switched: on starts it (read, then the first upload), off stops it and drops the queue. */
  setEnabled(on: boolean): void;
}

export interface SyncDeps {
  client: SyncClient;
  progress: ProgressStore;
  library: Pick<Library, "state" | "subscribe" | "entry">;
  /** «Смотреть украдкой»: sent as the document's `secret` while sync is on. */
  secrets?: SecretStore;
  /** The signed-in account, or null: nothing is sent or read without one. */
  accountId: () => number | null;
  /** For the dub memory and the outbox; the browser's own by default. */
  storage?: Storage | null;
}

/** At most one batch a minute while watching (spec §3: D1's free tier writes). */
export const PUSH_EVERY_MS = 60_000;
/** Back from the background after this long: another device may have played meanwhile. */
export const PULL_AFTER_HIDDEN_MS = 5 * 60_000;
/** Changes not yet accepted by the worker, kept across reloads: `{ account, titles }`. */
const OUTBOX_KEY = "kaeru.sync.outbox";
/** Accounts whose positions this browser already sent once in full. */
const SEEDED_KEY = "kaeru.sync.seeded";
/** The account that last used this browser; sign-out keeps it, so the next account can be told apart. */
export const LAST_ACCOUNT_KEY = "kaeru.account.last";
/** Titles per POST, well under the worker's 256 KB body with 30 episodes each. */
const TITLES_PER_POST = 100;

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

/**
 * Viewing sync through the worker (spec 2026-09-26-kaeru-sync-design.md §4): reads the document on
 * start and after five minutes in the background and takes whatever is newer than this browser's;
 * sends this browser's positions, dubs and «украдкой» states in batches — at most one a minute, at once on pause, on
 * leaving the player, on a new episode and when the page goes away — and a tombstone for a title
 * the list marks «completed». A batch that fails stays in the outbox for the next try. Nothing here
 * ever throws at a caller or blocks the player.
 */
export class SyncService implements Sync {
  private readonly deps: SyncDeps;
  private readonly storage: Storage | null;
  private running = false;
  private cleanups: (() => void)[] = [];
  private timer: ReturnType<typeof setTimeout> | null = null;
  private lastPushAt = Number.NEGATIVE_INFINITY;
  /** Batches on their way; a keepalive one may overlap the regular one. */
  private inflight = 0;
  /** Outboxes on their way, as sent: the player's pagehide and this one's must not send one twice. */
  private readonly sending = new Set<string>();
  /** The outbox when storage is blocked: kept for this page at least. */
  private memoryOutbox: string | null = null;
  private again = false;
  private pulling: Promise<void> | null = null;
  private hiddenAt: number | null = null;
  /** Each listed title's status as last seen; null until the list is first known. */
  private statuses: Map<number, ListStatus> | null = null;

  constructor(deps: SyncDeps) {
    this.deps = deps;
    this.storage = deps.storage === undefined ? browserStorage() : deps.storage;
    // At construction: services are built per account before any screen reads a position.
    this.claim();
  }

  /** The signed-in shell is up; sync runs only while the setting is on as well. */
  private mounted = false;

  start(): void {
    this.mounted = true;
    if (syncEnabled(this.storage ?? undefined)) this.begin();
  }

  setEnabled(on: boolean): void {
    setSyncEnabled(on, this.storage ?? undefined);
    if (on) {
      if (this.mounted) this.begin();
      return;
    }
    this.end();
    this.memoryOutbox = null;
    try {
      this.storage?.removeItem(OUTBOX_KEY);
    } catch {
      // Nothing more will be sent either way: the service is stopped.
    }
  }

  private begin(): void {
    if (this.running) return;
    this.running = true;
    this.cleanups = [
      this.deps.progress.watch(this.onPosition),
      onDubRemembered(this.onDub),
      this.deps.library.subscribe(this.onLibrary),
    ];
    if (this.deps.secrets !== undefined) this.cleanups.push(this.deps.secrets.watch(this.onSecret));
    if (typeof window !== "undefined") {
      window.addEventListener("pagehide", this.onPageHide);
      document.addEventListener("visibilitychange", this.onVisibility);
      this.cleanups.push(() => {
        window.removeEventListener("pagehide", this.onPageHide);
        document.removeEventListener("visibilitychange", this.onVisibility);
      });
    }
    this.onLibrary();
    void this.pull();
  }

  /**
   * Another account than the last one to use this browser: its positions, dubs and unsent changes
   * go before anything is read or sent, so none of them reach this account's document. The same
   * account signing back in keeps everything.
   */
  private claim(): void {
    const account = this.deps.accountId();
    if (account === null) return;
    let last: string | null = null;
    try {
      last = this.storage?.getItem(LAST_ACCOUNT_KEY) ?? null;
    } catch {
      last = null;
    }
    if (last !== null && last !== String(account)) {
      this.deps.progress.clear();
      if (this.storage !== null) forgetDubs(this.storage);
      this.memoryOutbox = null;
      try {
        this.storage?.removeItem(OUTBOX_KEY);
        this.storage?.removeItem(SEEDED_KEY);
      } catch {
        // Blocked storage keeps nothing of the other account either.
      }
    }
    try {
      this.storage?.setItem(LAST_ACCOUNT_KEY, String(account));
    } catch {
      // Blocked storage: every start looks like the first one, which clears nothing.
    }
  }

  stop(): void {
    this.mounted = false;
    this.end();
  }

  private end(): void {
    this.running = false;
    for (const cleanup of this.cleanups) cleanup();
    this.cleanups = [];
    this.statuses = null;
    if (this.timer !== null) clearTimeout(this.timer);
    this.timer = null;
  }

  push(options: { keepalive?: boolean } = {}): void {
    if (!this.running) return;
    void this.send(options.keepalive === true);
  }

  /** Reads the document and takes what is newer; one read at a time. */
  pull(): Promise<void> {
    if (this.pulling !== null) return this.pulling;
    const run = this.read().finally(() => {
      this.pulling = null;
    });
    this.pulling = run;
    return run;
  }

  private async read(): Promise<void> {
    const account = this.deps.accountId();
    if (!this.running || account === null) return;
    try {
      const titles = await this.deps.client.get();
      if (!this.running || this.deps.accountId() !== account) return;
      this.apply(titles);
      this.seed(account, titles);
      this.offerSecrets(titles);
    } catch {
      // Offline or refused: the next start or return from the background reads again.
    }
    this.schedule();
  }

  private readonly onPosition = (row: EpisodeProgress): void => {
    if (this.finished(row.animeId)) return;
    if (this.enqueue(row.animeId, { eps: { [String(row.episode)]: wire(row) } })) this.schedule();
  };

  private readonly onDub = (animeId: number, dub: StampedDub, storage: Storage | null): void => {
    // Another storage is a test's or another store's, not this browser's memory.
    if (storage !== this.storage || this.finished(animeId)) return;
    const title = dub.title.slice(0, MAX_DUB_TITLE);
    if (this.enqueue(animeId, { dub: { id: dub.id, title, at: dub.at } })) this.schedule();
  };

  private readonly onSecret = (animeId: number, state: SecretState): void => {
    if (this.enqueue(animeId, { secret: { on: state.on, watched: state.watched, at: state.at } })) this.schedule();
  };

  /** Secret states the server lacks or holds older: changed while sync was off, or never sent. */
  private offerSecrets(remote: SyncTitles): void {
    const secrets = this.deps.secrets;
    if (secrets === undefined) return;
    let outbox = this.readOutbox();
    let changed = false;
    for (const [animeId, local] of secrets.all()) {
      const id = String(animeId);
      const state: SyncTitle = { secret: { on: local.on, watched: local.watched, at: local.at } };
      const left = without(state, remote[id] ?? {});
      if (isEmpty(left)) continue;
      outbox = { ...outbox, [id]: merge(outbox[id], left) };
      changed = true;
    }
    if (changed) this.writeOutbox(outbox);
  }

  /** A title turning «completed» leaves a tombstone; turning back before it went out takes it back. */
  private readonly onLibrary = (): void => {
    const entries = listed(this.deps.library.state());
    if (entries === null) return;
    const now = new Map(entries.map((entry) => [entry.anime.id, entry.rate.status]));
    const before = this.statuses;
    this.statuses = now;
    // The first list seen is where things stand, not a change.
    if (before === null) return;
    for (const [animeId, status] of now) {
      const was = before.get(animeId);
      if (status === "completed" && was !== "completed") this.markGone(animeId);
      else if (status !== "completed" && was === "completed") this.unmarkGone(animeId);
    }
  };

  private readonly onPageHide = (): void => {
    this.push({ keepalive: true });
  };

  private readonly onVisibility = (): void => {
    if (document.visibilityState === "hidden") {
      this.hiddenAt = Date.now();
      this.push({ keepalive: true });
      return;
    }
    const hiddenAt = this.hiddenAt;
    this.hiddenAt = null;
    if (hiddenAt !== null && Date.now() - hiddenAt >= PULL_AFTER_HIDDEN_MS) void this.pull();
  };

  private finished(animeId: number): boolean {
    return this.deps.library.entry(animeId)?.rate.status === "completed";
  }

  private markGone(animeId: number): void {
    const at = Date.now();
    const outbox = this.readOutbox();
    const id = String(animeId);
    // Whatever was waiting for this title is older than the tombstone and would be refused anyway.
    const kept = without(outbox[id] ?? {}, { gone: at });
    outbox[id] = { ...kept, gone: at };
    this.writeOutbox(outbox);
    this.schedule();
  }

  private unmarkGone(animeId: number): void {
    const outbox = this.readOutbox();
    const title = outbox[String(animeId)];
    if (title?.gone === undefined) return;
    delete title.gone;
    if (isEmpty(title)) delete outbox[String(animeId)];
    this.writeOutbox(outbox);
  }

  /** What the server holds, taken where it is newer than this browser's (rules.ts `newer`). */
  private apply(titles: SyncTitles): void {
    if (Object.keys(titles).length === 0) return;
    const local = this.localState();
    const change = newer(titles, local);
    const { progress } = this.deps;
    for (const [animeId, gone] of change.tombstones) progress.forget(animeId, gone);
    progress.restore(change.positions);
    for (const [animeId, at] of change.dubStamps) {
      // Kept with its stamp in one entry here: an unchanged dub is written back with the new one.
      const dub = change.dubs.get(animeId) ?? local.dubs.get(animeId);
      if (dub !== undefined) mergeDub(animeId, { id: dub.id, title: dub.title, at }, this.storage ?? undefined);
    }
    this.applySecrets(change);
    const outbox = this.readOutbox();
    let outboxChanged = false;
    for (const [id, title] of Object.entries(titles)) {
      const waiting = outbox[id];
      if (waiting === undefined) continue;
      const left = without(waiting, title);
      if (isEmpty(left)) delete outbox[id];
      else outbox[id] = left;
      outboxChanged = true;
    }
    if (outboxChanged) this.writeOutbox(outbox);
  }

  private applySecrets(change: SyncNewer): void {
    const secrets = this.deps.secrets;
    if (secrets === undefined) return;
    const taken = new Set<number>();
    for (const { animeId, on, watched, at } of change.secrets) {
      secrets.set(animeId, { on, watched, at }, null, { quiet: true });
      taken.add(animeId);
    }
    for (const [animeId, gone] of change.tombstones) {
      if (taken.has(animeId)) continue;
      // The web's own rule, kept out of `newer` on purpose: Android and iOS do not have it, and
      // whether it joins the shared rules is still to be decided. The worker keeps nothing but the
      // tombstone of a finished title, secret state included, so a secret title another device
      // closed was watched through. The owner can move it on from there.
      const local = secrets.get(animeId);
      const episodes = local?.anime?.episodes ?? 0;
      if (local?.on === true && local.at <= gone && episodes > local.watched) {
        secrets.set(animeId, { on: true, watched: episodes, at: local.at }, null, { quiet: true });
      }
    }
  }

  /** Once per account: what this browser kept before sync, where it is newer than the server's (rules.ts `seed`). */
  private seed(account: number, remote: SyncTitles): void {
    const seeded = this.readSeeded();
    if (seeded.includes(account)) return;
    const batch = seedTitles(this.localState(), (animeId) => this.finished(animeId), remote);
    const outbox = this.readOutbox();
    for (const [id, left] of Object.entries(batch)) outbox[id] = merge(outbox[id], left);
    this.writeOutbox(outbox);
    this.writeSeeded([...seeded, account]);
  }

  /** This browser's positions, dubs and secrets, as the rules read them. */
  private localState(): LocalSyncState {
    const dubs = new Map<number, RememberedDub>();
    const dubStamps = new Map<number, number>();
    // A dub remembered before stamps existed reads as stamped at zero.
    for (const [animeId, dub] of rememberedDubs(this.storage ?? undefined)) {
      dubs.set(animeId, { id: dub.id, title: dub.title });
      dubStamps.set(animeId, dub.at);
    }
    const secrets = new Map<number, SecretState>(this.deps.secrets?.all() ?? []);
    return { positions: this.deps.progress.list(), dubs, dubStamps, secrets };
  }

  /** The next batch, no sooner than a minute after the last one. */
  private schedule(): void {
    if (!this.running || this.timer !== null || this.inflight > 0) return;
    if (this.deps.accountId() === null || Object.keys(this.readOutbox()).length === 0) return;
    const delay = Math.max(0, this.lastPushAt + PUSH_EVERY_MS - Date.now());
    this.timer = setTimeout(() => {
      this.timer = null;
      void this.send(false);
    }, delay);
  }

  private async send(keepalive: boolean): Promise<void> {
    if (!this.running || this.deps.accountId() === null) return;
    // A batch on its way goes on; this one follows it. A page going away cannot wait for it.
    if (this.inflight > 0 && !keepalive) {
      this.again = true;
      return;
    }
    const outbox = this.readOutbox();
    const ids = Object.keys(outbox);
    const sent = JSON.stringify(outbox);
    if (ids.length === 0 || this.sending.has(sent)) return;
    if (this.timer !== null) clearTimeout(this.timer);
    this.timer = null;
    this.lastPushAt = Date.now();
    this.inflight += 1;
    this.sending.add(sent);
    let failed = false;
    try {
      for (let start = 0; start < ids.length; start += TITLES_PER_POST) {
        const batch: SyncTitles = {};
        for (const id of ids.slice(start, start + TITLES_PER_POST)) batch[id] = outbox[id] as SyncTitle;
        const answer = await this.deps.client.post(batch, { keepalive });
        if (!this.running) return;
        this.accepted(batch);
        this.apply(answer);
      }
    } catch {
      // Kept in the outbox; the next batch carries it a minute later.
      failed = true;
    } finally {
      this.inflight -= 1;
      this.sending.delete(sent);
    }
    if (this.again && !failed) {
      this.again = false;
      void this.send(false);
      return;
    }
    this.again = false;
    this.schedule();
  }

  /** Out of the outbox: what the worker took, unless it changed again meanwhile. */
  private accepted(batch: SyncTitles): void {
    const outbox = this.readOutbox();
    for (const [id, sent] of Object.entries(batch)) {
      const waiting = outbox[id];
      if (waiting === undefined) continue;
      const left = without(waiting, sent);
      if (isEmpty(left)) delete outbox[id];
      else outbox[id] = left;
    }
    this.writeOutbox(outbox);
  }

  private enqueue(animeId: number, change: SyncTitle): boolean {
    if (this.deps.accountId() === null) return false;
    const outbox = this.readOutbox();
    outbox[String(animeId)] = merge(outbox[String(animeId)], change);
    this.writeOutbox(outbox);
    return true;
  }

  private readOutbox(): SyncTitles {
    const account = this.deps.accountId();
    try {
      const raw = this.storage === null ? this.memoryOutbox : this.storage.getItem(OUTBOX_KEY);
      const root = record(JSON.parse(raw ?? "null"));
      // Another account's changes are never sent with this one's token.
      if (root === null || root["account"] !== account) return {};
      const titles = record(root["titles"]);
      return titles === null ? {} : (titles as SyncTitles);
    } catch {
      return {};
    }
  }

  private writeOutbox(titles: SyncTitles): void {
    const raw = Object.keys(titles).length === 0 ? null : JSON.stringify({ account: this.deps.accountId(), titles });
    if (this.storage === null) {
      this.memoryOutbox = raw;
      return;
    }
    try {
      if (raw === null) this.storage.removeItem(OUTBOX_KEY);
      else this.storage.setItem(OUTBOX_KEY, raw);
    } catch {
      // Blocked storage: this batch is simply not kept past this page.
    }
  }

  private readSeeded(): number[] {
    try {
      const parsed: unknown = JSON.parse(this.storage?.getItem(SEEDED_KEY) ?? "[]");
      return Array.isArray(parsed) ? parsed.filter((id): id is number => typeof id === "number") : [];
    } catch {
      return [];
    }
  }

  private writeSeeded(accounts: number[]): void {
    try {
      this.storage?.setItem(SEEDED_KEY, JSON.stringify(accounts));
    } catch {
      // Blocked storage: the next start sends the same positions again, which the worker ignores.
    }
  }
}

function listed(state: ReturnType<Library["state"]>): LibraryEntry[] | null {
  if (state.kind === "ready") return state.entries;
  if (state.kind === "loading" || state.kind === "error") return state.entries;
  return null;
}
