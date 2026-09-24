import { Children, isValidElement, useCallback, useEffect, useId, useRef, useState, type ReactNode } from "react";
import { IconButton, TextAction } from "./Button";
import { IconChevronLeft, IconChevronRight } from "./icons";

export interface ShelfProps {
  title: string;
  /** A quiet «Всё ›» link: ink-soft, never amber. */
  action?: { label: string; to: string };
  /** Controls between the header and the row, e.g. the season chips. */
  extra?: ReactNode;
  children: ReactNode;
}

// Room left at either end below which the row counts as scrolled to that end.
const EDGE_PX = 1;
// A page leaves the last half-seen card in view, so nothing is skipped between two pages.
const PAGE_SHARE = 0.9;

interface Reach {
  back: boolean;
  forward: boolean;
}

function reachOf(row: HTMLElement): Reach {
  return {
    back: row.scrollLeft > EDGE_PX,
    forward: row.scrollLeft + row.clientWidth < row.scrollWidth - EDGE_PX,
  };
}

/**
 * A titled horizontal row of cards; the cards are links, so the row is reachable by keyboard. A row
 * wider than the screen gets «назад»/«вперёд» buttons, shown for a mouse only: a plain wheel scrolls
 * the page, not the row, and the scrollbar is hidden.
 */
export function Shelf({ title, action, extra, children }: ShelfProps) {
  const headingId = useId();
  const row = useRef<HTMLUListElement>(null);
  const [reach, setReach] = useState<Reach>({ back: false, forward: false });

  const measure = useCallback(() => {
    const element = row.current;
    if (element === null) return;
    const next = reachOf(element);
    setReach((current) => (current.back === next.back && current.forward === next.forward ? current : next));
  }, []);

  useEffect(() => {
    measure();
    const element = row.current;
    if (element === null || typeof ResizeObserver === "undefined") return undefined;
    const observer = new ResizeObserver(measure);
    observer.observe(element);
    return () => observer.disconnect();
  }, [measure]);

  // Cards arriving or leaving change how far the row reaches.
  const count = Children.count(children);
  useEffect(measure, [measure, count]);

  function page(direction: 1 | -1) {
    const element = row.current;
    if (element === null) return;
    const still = window.matchMedia?.("(prefers-reduced-motion: reduce)").matches ?? false;
    element.scrollBy({ left: direction * element.clientWidth * PAGE_SHARE, behavior: still ? "auto" : "smooth" });
  }

  const pageable = reach.back || reach.forward;
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
        {pageable ? (
          <div className="shelf__pager">
            <IconButton
              label="Листать назад"
              icon={<IconChevronLeft />}
              disabled={!reach.back}
              onClick={() => page(-1)}
            />
            <IconButton
              label="Листать вперёд"
              icon={<IconChevronRight />}
              disabled={!reach.forward}
              onClick={() => page(1)}
            />
          </div>
        ) : null}
      </div>
      {extra ? <div className="shelf__extra">{extra}</div> : null}
      <ul ref={row} className="shelf__row" onScroll={measure}>
        {Children.map(children, (child) => (isValidElement(child) ? <li className="shelf__item">{child}</li> : null))}
      </ul>
    </section>
  );
}
