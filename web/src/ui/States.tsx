import { PrimaryButton, SecondaryButton } from "./Button";

export interface StateAction {
  label: string;
  /** A destination makes the action a link; otherwise onClick runs. */
  to?: string;
  onClick?: () => void;
}

type Align = "center" | "start";

/** Title, one line of help, at most one amber action (Android States.kt). */
export function EmptyState({
  title,
  text,
  action,
  align = "center",
}: {
  title: string;
  text?: string;
  action?: StateAction;
  align?: Align;
}) {
  return (
    <div className={`state state--${align}`}>
      <h2 className="state__title t-headline">{title}</h2>
      {text ? <p className="state__text t-body">{text}</p> : null}
      {action ? (
        <div className="state__action">
          {action.to !== undefined ? (
            <PrimaryButton to={action.to}>{action.label}</PrimaryButton>
          ) : (
            <PrimaryButton onClick={action.onClick}>{action.label}</PrimaryButton>
          )}
        </div>
      ) : null}
    </div>
  );
}

/** The message and «Повторить» as a secondary button: retry is never amber. */
export function ErrorState({ message, onRetry, align = "center" }: { message: string; onRetry?: () => void; align?: Align }) {
  return (
    <div className={`state state--${align}`} role="alert">
      <p className="state__message t-body-lg">{message}</p>
      {onRetry ? (
        <div className="state__action">
          <SecondaryButton onClick={onRetry}>Повторить</SecondaryButton>
        </div>
      ) : null}
    </div>
  );
}

/** A 4px amber strip; decorative, since the text beside it carries the numbers. */
export function ProgressStrip({ value }: { value: number }) {
  const percent = Math.round(Math.min(1, Math.max(0, value)) * 1000) / 10;
  return (
    <span className="progress-strip" aria-hidden="true">
      <span className="progress-strip__fill" style={{ width: `${percent}%` }} />
    </span>
  );
}

/** A 35% segment sweeping back and forth: work of unknown length. */
export function IndeterminateStrip({ label }: { label?: string }) {
  return label ? (
    <span className="indeterminate" role="progressbar" aria-label={label} />
  ) : (
    <span className="indeterminate" aria-hidden="true" />
  );
}

/** The first sync, so an empty list is never claimed while it is still being fetched. */
export function SyncingNotice({ text = "Синхронизируем список с Shikimori…" }: { text?: string }) {
  return (
    <div className="syncing" role="status">
      <p className="t-body">{text}</p>
      <IndeterminateStrip />
    </div>
  );
}
