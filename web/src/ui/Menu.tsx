import { useCallback, useEffect, useId, useRef, useState, type KeyboardEvent, type ReactNode } from "react";
import { IconButton } from "./Button";
import { IconArrowDropDown, IconCheck } from "./icons";
import { Pill } from "./Pill";
import "./Menu.css";

export interface MenuItem {
  key: string;
  label: string;
  /** Set for one choice among several (menuitemradio); left out for a plain action. */
  checked?: boolean;
  destructive?: boolean;
  /** Shown but not pickable, and skipped by the arrows: a dub that lacks the episode. */
  disabled?: boolean;
  /** A second, quieter line that describes the item: «Субтитры», «нет серии 8». */
  note?: string;
  onSelect: () => void;
}

export interface MenuButtonProps {
  /** The pill's text, or the glyph of an icon button. */
  label: ReactNode;
  /** The name of an icon button; required with `variant="icon"`. */
  ariaLabel?: string;
  items: readonly MenuItem[];
  disabled?: boolean;
  variant?: "pill" | "icon";
  /** Set to open and close the menu from outside (the player's «Сменить озвучку»); with onOpenChange. */
  open?: boolean;
  onOpenChange?: (open: boolean) => void;
}

// Disabled items are left out of the walk: the arrows and Home/End land only where Enter works.
const ITEM = "[role^='menuitem']:not(:disabled)";

/**
 * A button that opens a list of actions or choices (WAI-ARIA menu button). The trigger is Task 8's
 * Pill (with a drop-down glyph) or IconButton, so it looks like every other pill and icon button.
 */
export function MenuButton({
  label,
  ariaLabel,
  items,
  disabled = false,
  variant = "pill",
  open: shown,
  onOpenChange,
}: MenuButtonProps) {
  const [ownOpen, setOwnOpen] = useState(false);
  const open = shown ?? ownOpen;
  const latest = useRef({ open, onOpenChange });
  latest.current = { open, onOpenChange };
  // Stable, so the effects below do not run again on every render of an owner.
  const setOpen = useCallback((next: boolean | ((current: boolean) => boolean)) => {
    const { open: current, onOpenChange: tell } = latest.current;
    const value = typeof next === "function" ? next(current) : next;
    setOwnOpen(value);
    if (value !== current) tell?.(value);
  }, []);
  const root = useRef<HTMLDivElement>(null);
  const list = useRef<HTMLUListElement>(null);
  const buttonId = useId();
  const menuId = useId();

  useEffect(() => {
    if (disabled) setOpen(false);
  }, [disabled, setOpen]);

  useEffect(() => {
    if (!open) return;
    const menu = list.current;
    // Start on the current choice, so Enter on an unchanged menu keeps it.
    const start = menu?.querySelector<HTMLElement>(`${ITEM}[aria-checked='true']`) ?? menu?.querySelector<HTMLElement>(ITEM);
    start?.focus();
    const onPointerDown = (event: PointerEvent) => {
      if (event.target instanceof Node && root.current?.contains(event.target)) return;
      setOpen(false);
    };
    document.addEventListener("pointerdown", onPointerDown);
    return () => document.removeEventListener("pointerdown", onPointerDown);
  }, [open, setOpen]);

  const close = () => {
    setOpen(false);
    // The trigger is the first child: the keyboard goes back where it came from.
    (root.current?.firstElementChild as HTMLElement | null)?.focus();
  };

  const onMenuKeyDown = (event: KeyboardEvent<HTMLUListElement>) => {
    const nodes = Array.from(list.current?.querySelectorAll<HTMLElement>(ITEM) ?? []);
    const index = nodes.findIndex((node) => node === document.activeElement);
    const focusAt = (next: number) => {
      event.preventDefault();
      nodes[(next + nodes.length) % nodes.length]?.focus();
    };
    switch (event.key) {
      case "ArrowDown":
        focusAt(index + 1);
        break;
      case "ArrowUp":
        focusAt(index <= 0 ? nodes.length - 1 : index - 1);
        break;
      case "Home":
        focusAt(0);
        break;
      case "End":
        focusAt(nodes.length - 1);
        break;
      case "Escape":
        event.preventDefault();
        close();
        break;
      case "Tab":
        setOpen(false);
        break;
      default:
        break;
    }
  };

  const onTriggerKeyDown = (event: KeyboardEvent<HTMLButtonElement>) => {
    if (event.key === "ArrowDown" || event.key === "ArrowUp") {
      event.preventDefault();
      setOpen(true);
    }
  };

  const triggerProps = {
    id: buttonId,
    "aria-haspopup": "menu" as const,
    "aria-expanded": open,
    "aria-controls": open ? menuId : undefined,
    disabled,
    onClick: () => setOpen((value) => !value),
    onKeyDown: onTriggerKeyDown,
  };

  return (
    <div className="menu" ref={root}>
      {variant === "icon" ? (
        <IconButton className="menu-trigger" label={ariaLabel ?? ""} icon={label} {...triggerProps} />
      ) : (
        <Pill className="menu-trigger" trailing={<IconArrowDropDown />} aria-label={ariaLabel} {...triggerProps}>
          {label}
        </Pill>
      )}
      {open && (
        <ul
          id={menuId}
          ref={list}
          role="menu"
          aria-labelledby={buttonId}
          className={`menu-list${variant === "icon" ? " menu-list-end" : ""}`}
          onKeyDown={onMenuKeyDown}
        >
          {items.map((item, index) => {
            const labelId = `${menuId}-${index}`;
            const noteId = `${menuId}-${index}-note`;
            return (
              <li key={item.key} role="none">
                <button
                  type="button"
                  role={item.checked === undefined ? "menuitem" : "menuitemradio"}
                  aria-checked={item.checked}
                  // The note is a description, not part of the name: «AniDUB», then «нет серии 8».
                  aria-labelledby={item.note === undefined ? undefined : labelId}
                  aria-describedby={item.note === undefined ? undefined : noteId}
                  disabled={item.disabled}
                  tabIndex={-1}
                  className={`menu-item${item.destructive ? " menu-item-destructive" : ""}`}
                  onClick={() => {
                    close();
                    item.onSelect();
                  }}
                >
                  <span className="menu-item-text">
                    <span id={labelId}>{item.label}</span>
                    {item.note !== undefined && (
                      <span id={noteId} className="menu-item-note t-label">
                        {item.note}
                      </span>
                    )}
                  </span>
                  {item.checked && <IconCheck className="menu-item-tick" size={18} />}
                </button>
              </li>
            );
          })}
        </ul>
      )}
    </div>
  );
}
