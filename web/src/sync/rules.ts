import type { EpisodeProgress } from "../domain/models";
import type { SecretState } from "../library/secret";
import type { RememberedDub } from "../player/memory";
import type { SyncPosition, SyncTitle, SyncTitles } from "./client";

// The sync rules without storage, outbox or network: the shared module's SyncMerge and SyncRules
// (shared/src/commonMain/kotlin/app/kaeru/shared/domain/sync), kept in TypeScript and checked against
// the same vectors (vectors.test.ts). service.ts does the reading and writing around them.

/** The worker keeps the 30 latest episodes of a title; older ones would only be trimmed again. */
export const EPISODES_PER_TITLE = 30;

/** The worker refuses a longer dub name, and with it the whole batch. */
export const MAX_DUB_TITLE = 200;

/** The wire's title id and episode key (client.ts reads no other). */
const TITLE_ID = /^\d{1,9}$/;
const EPISODE = /^\d{1,5}$/;

/** What this browser holds, as far as the rules need it. */
export interface LocalSyncState {
  /** Every saved position; for `newer` only which episode and when count. */
  positions: readonly EpisodeProgress[];
  /** The dub each title remembers (player/memory.ts). */
  dubs: ReadonlyMap<number, RememberedDub>;
  /** When each title's dub was chosen; one remembered before stamps existed counts as zero. */
  dubStamps: ReadonlyMap<number, number>;
  /** «Украдкой» as this browser has it, on or off. */
  secrets: ReadonlyMap<number, SecretState>;
  /**
   * How many episodes each title has announced, as far as this browser knows: what a tombstone over
   * a title watched «украдкой» here needs. A title left out, or at zero, is a length not known.
   */
  announcedEpisodes: ReadonlyMap<number, number>;
}

/** «Украдкой» for one title as another device left it. */
export interface TitleSecret extends SecretState {
  animeId: number;
}

/** What the server holds that is newer than this browser's, ready to be written here. */
export interface SyncNewer {
  /** Positions to write, `positionMs` within `0..durationMs`. */
  positions: EpisodeProgress[];
  /** By anime id: positions saved at or before the moment are dead. */
  tombstones: Map<number, number>;
  /** Only where the remembered dub actually changes. */
  dubs: Map<number, RememberedDub>;
  /** The stamps to take — also where the dub itself is already the same. */
  dubStamps: Map<number, number>;
  /**
   * In the document's order: as another device left it, or — for a title watched so here that
   * another device finished — watched through, stamped as it was here.
   */
  secrets: TitleSecret[];
}

export function isEmpty(title: SyncTitle): boolean {
  return (
    title.dub === undefined &&
    title.secret === undefined &&
    title.gone === undefined &&
    (title.eps === undefined || Object.keys(title.eps).length === 0)
  );
}

/** `patch` over `base`, the newer `at` winning per field and per episode; a tie goes to `patch`. */
export function merge(base: SyncTitle | undefined, patch: SyncTitle): SyncTitle {
  const out: SyncTitle = { ...base };
  if (patch.dub !== undefined && (out.dub === undefined || out.dub.at <= patch.dub.at)) out.dub = patch.dub;
  if (patch.secret !== undefined && (out.secret === undefined || out.secret.at <= patch.secret.at)) out.secret = patch.secret;
  if (patch.gone !== undefined && (out.gone === undefined || out.gone <= patch.gone)) out.gone = patch.gone;
  if (patch.eps !== undefined) {
    const eps = { ...out.eps };
    for (const [episode, position] of Object.entries(patch.eps)) {
      const known = eps[episode];
      if (known === undefined || known.at <= position.at) eps[episode] = position;
    }
    out.eps = eps;
  }
  return out;
}

/**
 * `title` without what `covered` (a sent batch, or the server) already holds: anything no newer, and
 * anything stamped at or before `covered`'s tombstone. An episode map left empty is dropped.
 */
export function without(title: SyncTitle, covered: SyncTitle): SyncTitle {
  const out: SyncTitle = { ...title };
  const floor = covered.gone ?? Number.NEGATIVE_INFINITY;
  if (out.dub !== undefined && (out.dub.at <= floor || (covered.dub !== undefined && out.dub.at <= covered.dub.at))) delete out.dub;
  if (
    out.secret !== undefined &&
    (out.secret.at <= floor || (covered.secret !== undefined && out.secret.at <= covered.secret.at))
  ) {
    delete out.secret;
  }
  if (out.gone !== undefined && out.gone <= floor) delete out.gone;
  if (out.eps !== undefined) {
    const eps: Record<string, SyncPosition> = {};
    for (const [episode, position] of Object.entries(out.eps)) {
      const known = covered.eps?.[episode];
      if (position.at <= floor || (known !== undefined && position.at <= known.at)) continue;
      eps[episode] = position;
    }
    if (Object.keys(eps).length > 0) out.eps = eps;
    else delete out.eps;
  }
  return out;
}

/** A position as it goes out: never below zero. */
export function wire(row: EpisodeProgress): SyncPosition {
  return { p: Math.max(0, row.positionMs), d: Math.max(0, row.durationMs), at: row.updatedAt };
}

/**
 * What of `remote` is newer than `local`.
 *
 * - A title id or an episode key that is not the wire's number is skipped; so is an episode at or
 *   below zero, and a position with no length (it cannot be resumed from).
 * - A position goes in unless this browser has the same episode stamped at or after it; its `p` is
 *   clamped into `0..d`.
 * - Every tombstone is passed on as it is; the write drops what it covers.
 * - A secret goes in over none, or over an older one; `watched` is at least zero.
 * - Otherwise a tombstone over a title watched «украдкой» here, stamped at or before it, is that
 *   title watched through on another device: the server keeps nothing else of a finished one. Its
 *   count goes up to the announced episodes — where known, and above it — and its stamp stays this
 *   browser's, so the state is nothing new to send: the tombstone covers it.
 * - A dub with id zero is no dub. Otherwise it wins over none, or over an older stamp — a dub
 *   remembered without a stamp counts as stamped at zero. The stamp is taken either way; the dub
 *   itself only where it differs from the remembered one.
 */
export function newer(remote: SyncTitles, local: LocalSyncState): SyncNewer {
  const out: SyncNewer = { positions: [], tombstones: new Map(), dubs: new Map(), dubStamps: new Map(), secrets: [] };
  const titles = Object.entries(remote);
  if (titles.length === 0) return out;
  const known = new Map<string, number>();
  for (const row of local.positions) known.set(`${row.animeId}:${row.episode}`, row.updatedAt);
  for (const [id, title] of titles) {
    if (!TITLE_ID.test(id)) continue;
    const animeId = Number(id);
    if (title.gone !== undefined) out.tombstones.set(animeId, title.gone);
    for (const [key, position] of Object.entries(title.eps ?? {})) {
      if (!EPISODE.test(key)) continue;
      const episode = Number(key);
      if (episode <= 0 || position.d <= 0) continue;
      const here = known.get(`${animeId}:${episode}`);
      if (here !== undefined && here >= position.at) continue;
      const positionMs = Math.min(Math.max(position.p, 0), position.d);
      out.positions.push({ animeId, episode, positionMs, durationMs: position.d, updatedAt: position.at });
    }
    const secret = title.secret;
    const here = local.secrets.get(animeId);
    if (secret !== undefined && (here === undefined || here.at < secret.at)) {
      out.secrets.push({ animeId, on: secret.on, watched: Math.max(0, secret.watched), at: secret.at });
    } else {
      const through = watchedThrough(animeId, here, title.gone, local.announcedEpisodes.get(animeId));
      if (through !== null) out.secrets.push(through);
    }
    const dub = title.dub;
    if (dub !== undefined && dub.id !== 0) {
      const remembered = local.dubs.get(animeId);
      const mine = local.dubStamps.get(animeId) ?? (remembered === undefined ? undefined : 0);
      if (mine === undefined || mine < dub.at) {
        if (remembered === undefined || remembered.id !== dub.id || remembered.title !== dub.title) {
          out.dubs.set(animeId, { id: dub.id, title: dub.title });
        }
        out.dubStamps.set(animeId, dub.at);
      }
    }
  }
  return out;
}

/**
 * `here`, on, and stamped at or before the tombstone `gone`: every one of the `announced` episodes
 * watched, stamped as it was. Null when any of that is not so, when the length is not known, or when
 * the count is that far already.
 */
function watchedThrough(
  animeId: number,
  here: SecretState | undefined,
  gone: number | undefined,
  announced: number | undefined,
): TitleSecret | null {
  if (here === undefined || gone === undefined || !here.on || here.at > gone) return null;
  if (announced === undefined || announced <= 0 || announced <= here.watched) return null;
  return { animeId, on: true, watched: announced, at: here.at };
}

/**
 * The first full send for an account: what `local` kept before sync, less what `remote` already
 * covers (`without`). Titles `finished` says yes to are left out.
 *
 * Per title, the EPISODES_PER_TITLE latest positions with a length; the dub, its name cut to
 * MAX_DUB_TITLE, stamped as chosen here or at zero; the secret as it stands. Only titles with
 * something left are returned, to be merged into the outbox.
 */
export function seed(local: LocalSyncState, finished: (animeId: number) => boolean, remote: SyncTitles): SyncTitles {
  const batch = new Map<number, SyncTitle>();
  const byTitle = new Map<number, EpisodeProgress[]>();
  for (const row of local.positions) {
    const rows = byTitle.get(row.animeId);
    if (rows === undefined) byTitle.set(row.animeId, [row]);
    else rows.push(row);
  }
  for (const [animeId, rows] of byTitle) {
    if (finished(animeId)) continue;
    const eps: Record<string, SyncPosition> = {};
    const latest = rows
      .filter((row) => row.durationMs > 0)
      .sort((a, b) => b.updatedAt - a.updatedAt)
      .slice(0, EPISODES_PER_TITLE);
    for (const row of latest) eps[String(row.episode)] = wire(row);
    if (Object.keys(eps).length > 0) batch.set(animeId, { eps });
  }
  for (const [animeId, dub] of local.dubs) {
    if (finished(animeId)) continue;
    const stamped = { id: dub.id, title: dub.title.slice(0, MAX_DUB_TITLE), at: local.dubStamps.get(animeId) ?? 0 };
    batch.set(animeId, { ...batch.get(animeId), dub: stamped });
  }
  for (const [animeId, secret] of local.secrets) {
    if (finished(animeId)) continue;
    const state = { on: secret.on, watched: Math.max(0, secret.watched), at: secret.at };
    batch.set(animeId, { ...batch.get(animeId), secret: state });
  }
  const left: SyncTitles = {};
  for (const [animeId, title] of batch) {
    const id = String(animeId);
    const rest = without(title, remote[id] ?? {});
    if (!isEmpty(rest)) left[id] = rest;
  }
  return left;
}
