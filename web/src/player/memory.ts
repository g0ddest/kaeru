/** One JSON object across titles: anime id → the dub it was last played with. */
const KEY = "kaeru.dubs";

export interface RememberedDub {
  id: number;
  title: string;
}

function browserStorage(): Storage | null {
  try {
    return window.localStorage;
  } catch {
    // Blocked site data makes the getter itself throw.
    return null;
  }
}

function record(value: unknown): Record<string, unknown> | null {
  return typeof value === "object" && value !== null && !Array.isArray(value) ? (value as Record<string, unknown>) : null;
}

/** A remembered dub with when it was chosen (ms): the newer choice wins across devices. */
export interface StampedDub extends RememberedDub {
  at: number;
}

type DubListener = (animeId: number, dub: StampedDub, storage: Storage | null) => void;

const listeners = new Set<DubListener>();

function readStamped(value: unknown): StampedDub | null {
  const row = record(value);
  const id = row?.["id"];
  const title = row?.["title"];
  const at = row?.["at"];
  // A film's sole track has a negative id, so any whole number is a track.
  if (typeof id !== "number" || !Number.isInteger(id) || typeof title !== "string") return null;
  // Entries written before sync carry no stamp: chosen «at the beginning of time», so any other wins.
  return { id, title, at: typeof at === "number" && Number.isFinite(at) ? at : 0 };
}

function readDub(value: unknown): RememberedDub | null {
  const dub = readStamped(value);
  return dub === null ? null : { id: dub.id, title: dub.title };
}

function write(target: Storage | null, animeId: number, dub: StampedDub): void {
  // Only what the menu and sync need: a whole Translation passed in keeps nothing else.
  const all = { ...readAll(target), [String(animeId)]: { id: dub.id, title: dub.title, at: dub.at } };
  try {
    target?.setItem(KEY, JSON.stringify(all));
  } catch {
    // Quota or blocked storage: the ranking falls back to studios, which is safe.
  }
}

function readAll(storage: Storage | null): Record<string, unknown> {
  try {
    const raw = storage?.getItem(KEY);
    return (raw ? record(JSON.parse(raw)) : null) ?? {};
  } catch {
    // A store some other build broke reads as empty rather than taking the player down.
    return {};
  }
}

/**
 * The dub this title was last played with, per browser like Android's WatchState row. The player
 * ranks it first and the title page's «Озвучка» shows it.
 */
export function rememberedDub(animeId: number, storage?: Storage): RememberedDub | null {
  return readDub(readAll(storage ?? browserStorage())[String(animeId)]);
}

/** The viewer's own choice: stamped now and told to sync, which sends it to the other devices. */
export function rememberDub(animeId: number, track: RememberedDub, storage?: Storage, at: number = Date.now()): void {
  const target = storage ?? browserStorage();
  const dub: StampedDub = { id: track.id, title: track.title, at };
  write(target, animeId, dub);
  for (const listener of [...listeners]) listener(animeId, dub, target);
}

/** A choice made on another device: taken only when newer than this browser's, and not echoed back. */
export function mergeDub(animeId: number, dub: StampedDub, storage?: Storage): boolean {
  const target = storage ?? browserStorage();
  const local = readStamped(readAll(target)[String(animeId)]);
  if (local !== null && local.at >= dub.at) return false;
  write(target, animeId, dub);
  return true;
}

/** Every remembered dub with its stamp, by anime id. */
export function rememberedDubs(storage?: Storage): Map<number, StampedDub> {
  const dubs = new Map<number, StampedDub>();
  for (const [key, value] of Object.entries(readAll(storage ?? browserStorage()))) {
    const dub = readStamped(value);
    if (dub !== null && /^\d+$/.test(key)) dubs.set(Number(key), dub);
  }
  return dubs;
}

/** Forgets every remembered dub: another account took this browser over. */
export function forgetDubs(storage?: Storage): void {
  try {
    (storage ?? browserStorage())?.removeItem(KEY);
  } catch {
    // Blocked storage holds nothing to forget.
  }
}

/** Hears every rememberDub (not mergeDub), with the storage it went to. */
export function onDubRemembered(listener: DubListener): () => void {
  listeners.add(listener);
  return () => {
    listeners.delete(listener);
  };
}

/**
 * Track id → how many anime remember it (TranslationUsage.of): which studio this viewer keeps coming
 * back to. Counted per anime by its first row, and anime remembering nothing are left out.
 */
export function usageOf(remembered: readonly { animeId: number; translationId: number | null }[]): Map<number, number> {
  const usage = new Map<number, number>();
  const seen = new Set<number>();
  for (const { animeId, translationId } of remembered) {
    if (seen.has(animeId)) continue;
    seen.add(animeId);
    if (translationId !== null) usage.set(translationId, (usage.get(translationId) ?? 0) + 1);
  }
  return usage;
}

/**
 * Track id → how many titles remember it (TranslationUsage.of): which studio this viewer keeps
 * coming back to, the best guess for a title never played here.
 */
export function dubUsage(storage?: Storage): Map<number, number> {
  return usageOf(
    Object.entries(readAll(storage ?? browserStorage())).map(([animeId, value]) => ({
      animeId: Number(animeId),
      translationId: readDub(value)?.id ?? null,
    })),
  );
}
