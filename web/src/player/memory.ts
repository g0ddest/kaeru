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

function readDub(value: unknown): RememberedDub | null {
  const row = record(value);
  const id = row?.["id"];
  const title = row?.["title"];
  // A film's sole track has a negative id, so any whole number is a track.
  if (typeof id !== "number" || !Number.isInteger(id) || typeof title !== "string") return null;
  return { id, title };
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

export function rememberDub(animeId: number, track: RememberedDub, storage?: Storage): void {
  const target = storage ?? browserStorage();
  // Only what the menu needs: a whole Translation passed in keeps nothing else.
  const all = { ...readAll(target), [String(animeId)]: { id: track.id, title: track.title } };
  try {
    target?.setItem(KEY, JSON.stringify(all));
  } catch {
    // Quota or blocked storage: the ranking falls back to studios, which is safe.
  }
}

/**
 * Track id → how many titles remember it (TranslationUsage.of): which studio this viewer keeps
 * coming back to, the best guess for a title never played here.
 */
export function dubUsage(storage?: Storage): Map<number, number> {
  const usage = new Map<number, number>();
  for (const value of Object.values(readAll(storage ?? browserStorage()))) {
    const dub = readDub(value);
    if (dub !== null) usage.set(dub.id, (usage.get(dub.id) ?? 0) + 1);
  }
  return usage;
}
