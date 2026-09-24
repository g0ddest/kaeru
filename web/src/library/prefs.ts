import { DEFAULT_THRESHOLD } from "../domain/progress";

/** Shares of an episode the settings screen offers as «watched» (Android settings). */
export const THRESHOLD_CHOICES: readonly number[] = [0.8, 0.85, 0.9, 0.95];

const KEY = "kaeru.threshold";
// Android WATCHED_THRESHOLD_RANGE: anything outside is not a share of an episode.
const LOWEST = 0.5;
const HIGHEST = 1;

function browserStorage(): Storage | null {
  try {
    return window.localStorage;
  } catch {
    return null;
  }
}

function clamp(value: number): number {
  return Math.min(HIGHEST, Math.max(LOWEST, value));
}

export function watchedThreshold(storage?: Storage): number {
  try {
    const raw = (storage ?? browserStorage())?.getItem(KEY);
    if (raw === null || raw === undefined || raw.trim() === "") return DEFAULT_THRESHOLD;
    const value = Number(raw);
    return Number.isFinite(value) ? clamp(value) : DEFAULT_THRESHOLD;
  } catch {
    return DEFAULT_THRESHOLD;
  }
}

export function setWatchedThreshold(value: number, storage?: Storage): void {
  // NaN would make every later comparison false and silently stop marking anything watched.
  if (!Number.isFinite(value)) return;
  try {
    (storage ?? browserStorage())?.setItem(KEY, String(clamp(value)));
  } catch {
    // Storage refused: the default keeps applying, which is safe.
  }
}

const AUTOPLAY_KEY = "kaeru.autoplay";
const SKIP_ENDING_KEY = "kaeru.skipEnding";
const QUALITY_KEY = "kaeru.quality";

// Only the two words this module writes; anything else reads as the default.
function readSwitch(key: string, fallback: boolean, storage: Storage | undefined): boolean {
  try {
    const raw = (storage ?? browserStorage())?.getItem(key);
    if (raw === "true") return true;
    if (raw === "false") return false;
    return fallback;
  } catch {
    return fallback;
  }
}

function writeSwitch(key: string, on: boolean, storage: Storage | undefined): void {
  try {
    (storage ?? browserStorage())?.setItem(key, String(on));
  } catch {
    // Storage refused: the default keeps applying, which is safe.
  }
}

/** «Следующая серия автоматически», on by default as on Android. */
export function autoplayNext(storage?: Storage): boolean {
  return readSwitch(AUTOPLAY_KEY, true, storage);
}

export function setAutoplayNext(on: boolean, storage?: Storage): void {
  writeSwitch(AUTOPLAY_KEY, on, storage);
}

/** «Пропускать эндинг», off by default as on Android. */
export function skipEnding(storage?: Storage): boolean {
  return readSwitch(SKIP_ENDING_KEY, false, storage);
}

export function setSkipEnding(on: boolean, storage?: Storage): void {
  writeSwitch(SKIP_ENDING_KEY, on, storage);
}

/** «Качество по умолчанию»: null is «Авто», the best a stream offers. */
export const QUALITY_CHOICES: readonly (number | null)[] = [null, 360, 480, 720, 1080];

// Quality.ofHeight: a height the settings do not offer degrades to «Авто» rather than sticking.
function offeredHeight(value: number): number | null {
  return QUALITY_CHOICES.includes(value) ? value : null;
}

export function defaultQuality(storage?: Storage): number | null {
  try {
    const raw = (storage ?? browserStorage())?.getItem(QUALITY_KEY);
    if (raw === null || raw === undefined || raw.trim() === "") return null;
    return offeredHeight(Number(raw));
  } catch {
    return null;
  }
}

export function setDefaultQuality(q: number | null, storage?: Storage): void {
  if (q !== null && offeredHeight(q) === null) return;
  try {
    const target = storage ?? browserStorage();
    if (q === null) target?.removeItem(QUALITY_KEY);
    else target?.setItem(QUALITY_KEY, String(q));
  } catch {
    // Storage refused: «Авто» keeps applying, which is safe.
  }
}
