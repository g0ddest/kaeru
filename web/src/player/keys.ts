/** What a key does on the player (spec §5): pause, 10 s either way, fullscreen, sound, next episode. */
export type PlayerKeyAction = "toggle" | "back10" | "fwd10" | "fullscreen" | "mute" | "next";

// By the physical key: in the Russian layout F, M and N type «а», «ь», «т».
const BY_CODE: ReadonlyMap<string, PlayerKeyAction> = new Map([
  ["Space", "toggle"],
  ["ArrowLeft", "back10"],
  ["ArrowRight", "fwd10"],
  ["KeyF", "fullscreen"],
  ["KeyM", "mute"],
  ["KeyN", "next"],
]);

/** A held arrow keeps seeking; a held letter or Space must not flip the state back and forth. */
const REPEATS: ReadonlySet<PlayerKeyAction> = new Set(["back10", "fwd10"]);

// Where a key belongs to what has the focus: typing (the together chat, the seek slider's own arrows),
// a menu walking its items, a dialog answering its question.
const TYPING = "input, textarea, select, [contenteditable]:not([contenteditable='false'])";
const OWN_KEYS = "[role=menu], [role=dialog]";
/** Space presses a focused control by itself; the player toggling as well would undo it. */
const PRESSABLE = "button, a, [role=button]";

/**
 * The player's shortcut for a keydown, or null when the key is not the player's to take. Browser
 * shortcuts (Ctrl, Cmd, Alt) and an input method's composition are always left alone.
 */
export function playerKeyAction(e: KeyboardEvent): PlayerKeyAction | null {
  if (e.defaultPrevented || e.isComposing || e.ctrlKey || e.metaKey || e.altKey) return null;
  const action = BY_CODE.get(e.code);
  if (action === undefined) return null;
  if (e.repeat && !REPEATS.has(action)) return null;
  const target = e.target instanceof Element ? e.target : null;
  if (target !== null) {
    if (target.closest(TYPING) !== null || (target instanceof HTMLElement && target.isContentEditable)) return null;
    if (target.closest(OWN_KEYS) !== null) return null;
    if (action === "toggle" && target.closest(PRESSABLE) !== null) return null;
  }
  return action;
}
