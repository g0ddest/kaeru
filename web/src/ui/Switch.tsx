import { useId } from "react";

export interface SwitchProps {
  label: string;
  /** A sentence under the row saying what the setting does; it describes the switch, not names it. */
  note?: string;
  checked: boolean;
  onChange: (on: boolean) => void;
}

/**
 * A setting that is on or off (Android SettingSwitchRow + KaeruSwitch): the whole row is one
 * `role="switch"` button, so a click anywhere on it, Space or Enter turns it. Amber track when on.
 */
export function Switch({ label, note, checked, onChange }: SwitchProps) {
  const noteId = useId();
  return (
    <div className="switch-row">
      <button
        type="button"
        role="switch"
        aria-checked={checked}
        aria-describedby={note === undefined ? undefined : noteId}
        className={`switch${checked ? " switch--on" : ""}`}
        onClick={() => onChange(!checked)}
      >
        <span className="switch__label t-title-sm">{label}</span>
        <span className="switch__track" aria-hidden="true">
          <span className="switch__thumb" />
        </span>
      </button>
      {note !== undefined && (
        <p id={noteId} className="switch__note t-body">
          {note}
        </p>
      )}
    </div>
  );
}
