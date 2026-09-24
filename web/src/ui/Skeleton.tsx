import type { CSSProperties, ReactNode } from "react";

interface BlockProps {
  className?: string;
  width?: CSSProperties["width"];
  height?: CSSProperties["height"];
  radius?: CSSProperties["borderRadius"];
}

/** A pulsing placeholder block in the exact shape of what is loading; never a spinner. */
export function SkeletonBlock({ className, width, height, radius }: BlockProps) {
  return (
    <span
      className={className ? `skeleton ${className}` : "skeleton"}
      style={{ width, height, borderRadius: radius }}
      aria-hidden="true"
    />
  );
}

/** One announced loading region; the blocks inside stay hidden from screen readers. */
export function SkeletonGroup({ label = "Загрузка…", children }: { label?: string; children: ReactNode }) {
  return (
    <div className="skeleton-group" role="status" aria-busy="true">
      <span className="visually-hidden">{label}</span>
      {children}
    </div>
  );
}

export function SkeletonHero() {
  return <SkeletonBlock className="skeleton--hero" radius={0} />;
}

function PosterBlock({ row }: { row: boolean }) {
  return (
    <div className={row ? "skeleton-card skeleton-card--row" : "skeleton-card"}>
      <SkeletonBlock className="skeleton-card__art" />
      <SkeletonBlock height={16} width="80%" />
    </div>
  );
}

export function SkeletonShelf({ cards = 6 }: { cards?: number }) {
  return (
    <div className="skeleton-shelf" aria-hidden="true">
      <SkeletonBlock className="skeleton-shelf__title" width="40%" height={24} />
      <div className="skeleton-shelf__row">
        {Array.from({ length: cards }, (_, index) => (
          <PosterBlock key={index} row />
        ))}
      </div>
    </div>
  );
}

export function SkeletonGrid({ count = 9 }: { count?: number }) {
  return (
    <div className="skeleton-grid poster-grid" aria-hidden="true">
      {Array.from({ length: count }, (_, index) => (
        <PosterBlock key={index} row={false} />
      ))}
    </div>
  );
}
