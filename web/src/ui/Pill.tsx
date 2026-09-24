import { useRef, type ButtonHTMLAttributes, type KeyboardEvent, type ReactNode } from "react";

export interface PillProps extends Omit<ButtonHTMLAttributes<HTMLButtonElement>, "className"> {
  /** Amber with dark text: only for the one pill the viewer is looking at. */
  selected?: boolean;
  /** A trailing glyph, e.g. IconArrowDropDown on a pill that opens a menu. */
  trailing?: ReactNode;
  className?: string;
}

export function Pill({ selected = false, trailing, className, type, children, ...rest }: PillProps) {
  const names = ["pill", selected ? "pill--selected" : "", className ?? ""].filter((name) => name !== "").join(" ");
  return (
    <button type={type ?? "button"} className={names} {...rest}>
      <span className="pill__label">{children}</span>
      {trailing ? (
        <span className="pill__trailing" aria-hidden="true">
          {trailing}
        </span>
      ) : null}
    </button>
  );
}

export interface PillOption<T extends string | number> {
  value: T;
  label: string;
}

export interface PillGroupProps<T extends string | number> {
  /** "radio": a radiogroup (season chips); "tab": a tablist (My list statuses). */
  kind: "radio" | "tab";
  /** The group's accessible name. */
  label: string;
  options: readonly PillOption<T>[];
  value: T;
  onChange: (value: T) => void;
  /** Tabs: the id of the panel they control. */
  panelId?: string;
  /** Tabs: each tab gets the id `${idPrefix}-${value}`, so the panel can name its tab. */
  idPrefix?: string;
  className?: string;
}

/** A row of pills with one tab stop; arrows move the choice (WAI-ARIA radio group and tabs). */
export function PillGroup<T extends string | number>({
  kind,
  label,
  options,
  value,
  onChange,
  panelId,
  idPrefix,
  className,
}: PillGroupProps<T>) {
  const ref = useRef<HTMLDivElement>(null);
  const selectedIndex = options.findIndex((option) => option.value === value);
  const tabStop = selectedIndex >= 0 ? selectedIndex : 0;

  function select(index: number) {
    const count = options.length;
    const next = ((index % count) + count) % count;
    onChange(options[next].value);
    ref.current?.querySelectorAll<HTMLButtonElement>("button")[next]?.focus();
  }

  function onKeyDown(event: KeyboardEvent<HTMLDivElement>) {
    if (options.length === 0) return;
    const buttons = Array.from(ref.current?.querySelectorAll<HTMLButtonElement>("button") ?? []);
    const focused = buttons.findIndex((button) => button === document.activeElement);
    const from = focused >= 0 ? focused : tabStop;
    const vertical = kind === "radio";
    if (event.key === "ArrowRight" || (vertical && event.key === "ArrowDown")) select(from + 1);
    else if (event.key === "ArrowLeft" || (vertical && event.key === "ArrowUp")) select(from - 1);
    else if (event.key === "Home") select(0);
    else if (event.key === "End") select(options.length - 1);
    else return;
    event.preventDefault();
  }

  return (
    <div
      ref={ref}
      role={kind === "radio" ? "radiogroup" : "tablist"}
      aria-label={label}
      className={["pill-group", className ?? ""].filter((name) => name !== "").join(" ")}
      onKeyDown={onKeyDown}
    >
      {options.map((option, index) => {
        const selected = option.value === value;
        return (
          <Pill
            key={String(option.value)}
            id={idPrefix ? `${idPrefix}-${String(option.value)}` : undefined}
            role={kind === "radio" ? "radio" : "tab"}
            aria-checked={kind === "radio" ? selected : undefined}
            aria-selected={kind === "tab" ? selected : undefined}
            aria-controls={kind === "tab" ? panelId : undefined}
            tabIndex={index === tabStop ? 0 : -1}
            selected={selected}
            onClick={() => onChange(option.value)}
          >
            {option.label}
          </Pill>
        );
      })}
    </div>
  );
}
