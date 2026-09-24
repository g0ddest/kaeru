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

// Where a key belongs to what has the focus: typing (the together chat), a menu walking its items, a
// dialog answering its question.
const TYPING = "input, textarea, select, [contenteditable]:not([contenteditable='false'])";
const OWN_KEYS = "[role=menu], [role=dialog]";
/** Space presses a focused control by itself; the player toggling as well would undo it. */
const PRESSABLE = "button, a, [role=button]";

/**
 * The seek slider, which a click leaves focused: it has no use for Space or the letters, and its arrows
 * are the player's 10 s rather than its own 1-s step.
 */
function isSlider(target: Element): boolean {
  return target instanceof HTMLInputElement && target.type === "range";
}

/**
 * Whether a focused control shows its ring (:focus-visible): the keyboard or a remote put it there, not
 * a pointer. A browser without the selector presses the control, as browsers always have.
 */
export function focusShown(element: Element): boolean {
  try {
    return element.matches(":focus-visible");
  } catch {
    return true;
  }
}

export interface FocusOrigin {
  /** Whether the keyboard (or a remote) put the focus on `element`. */
  keyboardFocused: (element: Element) => boolean;
  stop: () => void;
}

/**
 * Whether the keyboard put the focus where it is, read from :focus-visible as the focus arrives. Read
 * later it says otherwise: Chrome turns the ring on for a clicked control at the first key pressed
 * there, before the page hears that key, so every Space would look like the keyboard's.
 */
export function trackFocusOrigin(doc: Document = document): FocusOrigin {
  let focused: Element | null = null;
  let ring = false;
  const onFocusIn = (event: FocusEvent) => {
    if (!(event.target instanceof Element)) return;
    focused = event.target;
    ring = focusShown(event.target);
  };
  doc.addEventListener("focusin", onFocusIn);
  return {
    // A control focused before tracking began has only its ring as it is now.
    keyboardFocused: (element) => (element === focused ? ring : focusShown(element)),
    stop: () => doc.removeEventListener("focusin", onFocusIn),
  };
}

/**
 * The player's shortcut for a keydown, or null when the key is not the player's to take. Browser
 * shortcuts (Ctrl, Cmd, Alt) and an input method's composition are always left alone. Space presses a
 * control the keyboard focused; on one a click left focused (or a script, after a click) it pauses.
 */
export function playerKeyAction(
  e: KeyboardEvent,
  keyboardFocused: (element: Element) => boolean = focusShown,
): PlayerKeyAction | null {
  if (e.defaultPrevented || e.isComposing || e.ctrlKey || e.metaKey || e.altKey) return null;
  const action = BY_CODE.get(e.code);
  if (action === undefined) return null;
  if (e.repeat && !REPEATS.has(action)) return null;
  const target = e.target instanceof Element ? e.target : null;
  if (target !== null) {
    if (isSlider(target)) return action;
    if (target.closest(TYPING) !== null || (target instanceof HTMLElement && target.isContentEditable)) return null;
    if (target.closest(OWN_KEYS) !== null) return null;
    const control = action === "toggle" ? target.closest(PRESSABLE) : null;
    if (control !== null && keyboardFocused(control)) return null;
  }
  return action;
}
