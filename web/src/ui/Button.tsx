import type { ButtonHTMLAttributes, MouseEventHandler, ReactNode } from "react";
import { Link } from "react-router-dom";
import { IconChevronRight } from "./icons";

interface CommonProps {
  children: ReactNode;
  /** A leading glyph from icons.tsx: 20px on buttons, 18px on text actions. */
  icon?: ReactNode;
  className?: string;
  /** Fills the row; its focus ring then skips the 1.06 scale, having nowhere to grow. */
  fullWidth?: boolean;
  /** 48px high with 8px padding, for the button under a grid card. */
  compact?: boolean;
  /** Text actions: a trailing chevron, as in Android's «Всё ›». */
  chevron?: boolean;
}

export type ButtonAsButton = CommonProps &
  Omit<ButtonHTMLAttributes<HTMLButtonElement>, "children" | "className"> & { to?: undefined };

export type ButtonAsLink = CommonProps & {
  /** Navigation is a link, never a button that navigates. */
  to: string;
  replace?: boolean;
  onClick?: MouseEventHandler<HTMLAnchorElement>;
  "aria-label"?: string;
};

export type ButtonProps = ButtonAsButton | ButtonAsLink;

type Variant = "primary" | "secondary" | "text" | "destructive";

function isLink(props: ButtonProps): props is ButtonAsLink {
  return typeof props.to === "string";
}

function classNames(variant: Variant, props: CommonProps): string {
  return ["btn", `btn--${variant}`, props.fullWidth ? "btn--full" : "", props.compact ? "btn--compact" : "", props.className ?? ""]
    .filter((name) => name !== "")
    .join(" ");
}

function Action({ variant, props }: { variant: Variant; props: ButtonProps }) {
  const content = (
    <>
      {props.icon ? (
        <span className="btn__icon" aria-hidden="true">
          {props.icon}
        </span>
      ) : null}
      <span className="btn__label">{props.children}</span>
      {props.chevron ? <IconChevronRight className="btn__chevron" size={18} /> : null}
    </>
  );

  if (isLink(props)) {
    return (
      <Link
        to={props.to}
        replace={props.replace}
        onClick={props.onClick}
        aria-label={props["aria-label"]}
        className={classNames(variant, props)}
      >
        {content}
      </Link>
    );
  }

  const {
    children: _children,
    icon: _icon,
    className: _className,
    fullWidth: _fullWidth,
    compact: _compact,
    chevron: _chevron,
    to: _to,
    type,
    ...rest
  } = props;
  // type="button" by default: a button inside a form must never submit it by accident.
  return (
    <button type={type ?? "button"} className={classNames(variant, props)} {...rest}>
      {content}
    </button>
  );
}

/** The one amber action per view: dark text on amber, never white (2:1 contrast). */
export function PrimaryButton(props: ButtonProps) {
  return <Action variant="primary" props={props} />;
}

export function SecondaryButton(props: ButtonProps) {
  return <Action variant="secondary" props={props} />;
}

/** Quiet ink-soft action, never amber. */
export function TextAction(props: ButtonProps) {
  return <Action variant="text" props={props} />;
}

export function DestructiveButton(props: ButtonProps) {
  return <Action variant="destructive" props={props} />;
}

export interface IconButtonProps extends Omit<ButtonHTMLAttributes<HTMLButtonElement>, "children" | "aria-label"> {
  /** The accessible name; the glyph itself is hidden from screen readers. */
  label: string;
  icon: ReactNode;
  /** Over artwork: a 42% black disc keeps the glyph readable on any poster. */
  overArt?: boolean;
}

export function IconButton({ label, icon, overArt = false, className, type, ...rest }: IconButtonProps) {
  const names = ["icon-button", overArt ? "icon-button--over-art" : "", className ?? ""]
    .filter((name) => name !== "")
    .join(" ");
  return (
    <button type={type ?? "button"} aria-label={label} title={label} className={names} {...rest}>
      {icon}
    </button>
  );
}
