import { useEffect, useId, useRef, useState, type KeyboardEvent, type ReactNode } from "react";
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
}

const ITEM = "[role^='menuitem']";

/**
 * A button that opens a list of actions or choices (WAI-ARIA menu button). The trigger is Task 8's
 * Pill (with a drop-down glyph) or IconButton, so it looks like every other pill and icon button.
 */
export function MenuButton({ label, ariaLabel, items, disabled = false, variant = "pill" }: MenuButtonProps) {
  const [open, setOpen] = useState(false);
  const root = useRef<HTMLDivElement>(null);
  const list = useRef<HTMLUListElement>(null);
  const buttonId = useId();
  const menuId = useId();

  useEffect(() => {
    if (disabled) setOpen(false);
  }, [disabled]);

  useEffect(() => {
    if (!open) return;
    const menu = list.current;
    // Start on the current choice, so Enter on an unchanged menu keeps it.
    const start = menu?.querySelector<HTMLElement>("[aria-checked='true']") ?? menu?.querySelector<HTMLElement>(ITEM);
    start?.focus();
    const onPointerDown = (event: PointerEvent) => {
      if (event.target instanceof Node && root.current?.contains(event.target)) return;
      setOpen(false);
    };
    document.addEventListener("pointerdown", onPointerDown);
    return () => document.removeEventListener("pointerdown", onPointerDown);
  }, [open]);

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
          {items.map((item) => (
            <li key={item.key} role="none">
              <button
                type="button"
                role={item.checked === undefined ? "menuitem" : "menuitemradio"}
                aria-checked={item.checked}
                tabIndex={-1}
                className={`menu-item${item.destructive ? " menu-item-destructive" : ""}`}
                onClick={() => {
                  close();
                  item.onSelect();
                }}
              >
                <span>{item.label}</span>
                {item.checked && <IconCheck className="menu-item-tick" size={18} />}
              </button>
            </li>
          ))}
        </ul>
      )}
    </div>
  );
}
