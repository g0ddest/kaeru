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
