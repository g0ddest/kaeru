import { Children, isValidElement, useId, type ReactNode } from "react";
import { TextAction } from "./Button";

export interface ShelfProps {
  title: string;
  /** A quiet «Всё ›» link: ink-soft, never amber. */
  action?: { label: string; to: string };
  /** Controls between the header and the row, e.g. the season chips. */
  extra?: ReactNode;
  children: ReactNode;
}

/** A titled horizontal row of cards; the cards are links, so the row is reachable by keyboard. */
export function Shelf({ title, action, extra, children }: ShelfProps) {
  const headingId = useId();
  return (
    <section className="shelf" aria-labelledby={headingId}>
      <div className="shelf__header">
        <h2 id={headingId} className="shelf__title t-title">
          {title}
        </h2>
        {action ? (
          <TextAction to={action.to} chevron>
            {action.label}
          </TextAction>
        ) : null}
      </div>
      {extra ? <div className="shelf__extra">{extra}</div> : null}
      <ul className="shelf__row">
        {Children.map(children, (child) => (isValidElement(child) ? <li className="shelf__item">{child}</li> : null))}
      </ul>
    </section>
  );
}
