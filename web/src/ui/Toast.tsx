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
/** Long enough to reach «Отменить»; the timer also waits while the toast is hovered or focused. */
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

  const show = useCallback((text: string, action?: ToastAction) => {
    nextId.current += 1;
    // A removed toast fires no blur, so a new one never inherits the pause.
    setPaused(false);
    setShown({ id: nextId.current, text, action });
  }, []);

  const dismiss = useCallback(() => {
    setPaused(false);
    setShown(null);
  }, []);

  useEffect(() => {
    if (shown === null || paused) return;
    const id = shown.id;
    const timer = setTimeout(
      () => setShown((current) => (current?.id === id ? null : current)),
      shown.action ? TOAST_WITH_ACTION_MS : TOAST_MS,
    );
    return () => clearTimeout(timer);
  }, [shown, paused]);

  const api = useMemo<ToastApi>(() => ({ show, dismiss }), [show, dismiss]);

  return (
    <ToastContext.Provider value={api}>
      {children}
      {/* Always mounted, so screen readers hear what is put into it. */}
      <div className="toast-region" role="status" aria-live="polite">
        {shown ? (
          <div
            key={shown.id}
            className="toast"
            onMouseEnter={() => setPaused(true)}
            onMouseLeave={() => setPaused(false)}
            onFocus={() => setPaused(true)}
            onBlur={() => setPaused(false)}
          >
            <span className="toast__text t-body">{shown.text}</span>
            {shown.action ? (
              <button
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
