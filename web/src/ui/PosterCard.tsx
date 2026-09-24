import { Children, isValidElement, useState, type ReactNode } from "react";
import { Link } from "react-router-dom";
import type { Card } from "../domain/feed";
import { ProgressStrip } from "./States";

export interface PosterCardProps {
  card: Card;
  /** "row": the fixed poster width of a shelf; "grid": fills its grid cell. */
  layout?: "row" | "grid";
  /** Where the card leads; the title page by default. */
  to?: string;
  /** A control under the card (Search's «В планы»), kept outside the link as its own tab stop. */
  footer?: ReactNode;
}

/** The fallback artwork: the title's first letter, upper-cased the Russian way (ё → Ё). */
export function posterLetter(title: string): string {
  const first = Array.from(title.trim())[0] ?? "";
  return first.toLocaleUpperCase("ru");
}

export function PosterCard({ card, layout = "row", to, footer }: PosterCardProps) {
  // By URL, not a flag: a card keeps its key while a later load brings a new poster, which gets its own try.
  const [failedUrl, setFailedUrl] = useState<string | null>(null);
  const [loadedUrl, setLoadedUrl] = useState<string | null>(null);
  const poster = card.posterUrl !== null && card.posterUrl !== failedUrl ? card.posterUrl : null;

  return (
    <div className={`poster-card poster-card--${layout}`}>
      <Link to={to ?? `/anime/${card.animeId}`} className="poster-card__link">
        <span className="poster-card__art">
          {poster !== null ? (
            // The title sits beside the artwork, so the image is decorative.
            <img
              className="poster-card__img"
              src={poster}
              alt=""
              loading="lazy"
              decoding="async"
              data-loaded={loadedUrl === poster}
              onLoad={() => setLoadedUrl(poster)}
              onError={() => setFailedUrl(poster)}
            />
          ) : (
            <span className="poster-card__letter" aria-hidden="true">
              {posterLetter(card.title)}
            </span>
          )}
          {card.badge ? <span className="poster-card__badge t-label">{card.badge}</span> : null}
          {card.progress !== null && card.progress > 0 ? <ProgressStrip value={card.progress} /> : null}
        </span>
        <span className="poster-card__title t-title-sm">{card.title}</span>
        {card.subtitle ? <span className="poster-card__subtitle t-label">{card.subtitle}</span> : null}
      </Link>
      {footer ? <div className="poster-card__footer">{footer}</div> : null}
    </div>
  );
}

/** A responsive poster grid: minmax(--grid-min, 1fr), two title lines reserved by the cards. */
export function PosterGrid({ label, children }: { label?: string; children: ReactNode }) {
  return (
    <ul className="poster-grid" aria-label={label}>
      {Children.map(children, (child) => (isValidElement(child) ? <li className="poster-grid__item">{child}</li> : null))}
    </ul>
  );
}
