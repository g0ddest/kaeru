import { createContext, useCallback, useContext, useEffect, useMemo, useRef, useState, type ReactNode } from "react";

export interface ToastAction {
  label: string;
  onClick: () => void;
}

export interface ToastApi {
  show: (text: string, action?: ToastAction) => void;
  dismiss: () => void;
}

export const TOAST_MS = 4000;
/**
 * An action toast takes the focus to its action, so a keyboard reaches it at once; the timer waits
 * while the viewer hovers it, tabs to it or presses a key on it.
 */
export const TOAST_WITH_ACTION_MS = 8000;

interface Shown {
  id: number;
  text: string;
  action: ToastAction | undefined;
}

const ToastContext = createContext<ToastApi | null>(null);

export function ToastProvider({ children }: { children: ReactNode }) {
  const [shown, setShown] = useState<Shown | null>(null);
  const [paused, setPaused] = useState(false);
  const nextId = useRef(0);
  const region = useRef<HTMLDivElement>(null);
  const actionButton = useRef<HTMLButtonElement>(null);
  // Where the focus was before a toast took it to its action, to go back there when it closes.
  const cameFrom = useRef<HTMLElement | null>(null);
  // The toast is moving the focus itself: that is not the viewer lingering on it.
  const placing = useRef(false);

  // Called before the toast leaves: a removed button would drop the focus to the page.
  const giveFocusBack = useCallback(() => {
    const target = cameFrom.current;
    cameFrom.current = null;
    if (!(region.current?.contains(document.activeElement) ?? false)) return;
    if (target?.isConnected) target.focus({ preventScroll: true });
  }, []);

  const show = useCallback((text: string, action?: ToastAction) => {
    nextId.current += 1;
    // A removed toast fires no blur, so a new one never inherits the pause.
    setPaused(false);
    const active = document.activeElement;
    if (action && active instanceof HTMLElement && !(region.current?.contains(active) ?? false)) {
      cameFrom.current = active;
    }
    setShown({ id: nextId.current, text, action });
  }, []);

  const dismiss = useCallback(() => {
    giveFocusBack();
    setPaused(false);
    setShown(null);
  }, [giveFocusBack]);

  // An action has to be reachable before the toast goes: the region sits after every page control.
  useEffect(() => {
    if (!shown?.action) return;
    placing.current = true;
    actionButton.current?.focus({ preventScroll: true });
    placing.current = false;
  }, [shown]);

  useEffect(() => {
    if (shown === null || paused) return;
    const id = shown.id;
    const timer = setTimeout(() => {
      giveFocusBack();
      setShown((current) => (current?.id === id ? null : current));
    }, shown.action ? TOAST_WITH_ACTION_MS : TOAST_MS);
    return () => clearTimeout(timer);
  }, [shown, paused, giveFocusBack]);

  const api = useMemo<ToastApi>(() => ({ show, dismiss }), [show, dismiss]);

  return (
    <ToastContext.Provider value={api}>
      {children}
      {/* Always mounted, so screen readers hear what is put into it. */}
      <div ref={region} className="toast-region" role="status" aria-live="polite">
        {shown ? (
          <div
            key={shown.id}
            className="toast"
            onMouseEnter={() => setPaused(true)}
            onMouseLeave={() => setPaused(false)}
            onFocus={() => {
              if (!placing.current) setPaused(true);
            }}
            onBlur={() => setPaused(false)}
            onKeyDown={(event) => {
              if (event.key === "Escape") {
                event.stopPropagation();
                dismiss();
                return;
              }
              // A key pressed on the toast means the viewer is on it, even though they did not put the focus there.
              setPaused(true);
            }}
          >
            <span className="toast__text t-body">{shown.text}</span>
            {shown.action ? (
              <button
                ref={actionButton}
                type="button"
                className="btn btn--text toast__action"
                onClick={() => {
                  const action = shown.action;
                  dismiss();
                  action?.onClick();
                }}
              >
                {shown.action.label}
              </button>
            ) : null}
          </div>
        ) : null}
      </div>
    </ToastContext.Provider>
  );
}

export function useToast(): ToastApi {
  const api = useContext(ToastContext);
  if (api === null) throw new Error("useToast() is used outside <ToastProvider>");
  return api;
}
