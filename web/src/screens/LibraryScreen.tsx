import { useEffect, useReducer } from "react";
import type { ReactNode } from "react";
import { useSearchParams } from "react-router-dom";
import { useServices } from "../app/services";
import type { LibraryEntry } from "../domain/models";
import { useLibrary } from "../library/library";
import {
  SORT_OPTIONS,
  emptyTabCopy,
  libraryCard,
  libraryTabs,
  parseSort,
  parseTab,
  selectLibrary,
} from "../library/listing";
import { watchedThreshold } from "../library/prefs";
import { PillGroup } from "../ui/Pill";
import { PosterCard, PosterGrid } from "../ui/PosterCard";
import { SkeletonGrid, SkeletonGroup } from "../ui/Skeleton";
import { EmptyState, ErrorState } from "../ui/States";
import "./browse.css";

const PANEL_ID = "lib-panel";
// PillGroup gives each tab the id `${TAB_PREFIX}-${status}`; the panel is named by that id.
const TAB_PREFIX = "lib-tab";
const SYNCING = "Синхронизируем список с Shikimori…";

/** «Мой список»: six status tabs over one poster grid (Android LibraryScreen). */
export function LibraryScreen() {
  const { library, progress } = useServices();
  const state = useLibrary(library);
  const [params, setParams] = useSearchParams();
  const [, repaint] = useReducer((count: number) => count + 1, 0);
  const status = parseTab(params.get("tab"));
  const sort = parseSort(params.get("sort"));

  // Card strips read this browser's positions, so a new position repaints the grid.
  useEffect(() => progress.subscribe(repaint), [progress]);

  useEffect(() => {
    if (library.state().kind === "idle") void library.load().catch(() => undefined);
  }, [library]);

  // Tab and sort live in the address, so Back from a title returns to the same view.
  function setView(name: "tab" | "sort", value: string) {
    setParams(
      (current) => {
        const next = new URLSearchParams(current);
        next.set(name, value);
        return next;
      },
      { replace: true },
    );
  }

  function retry() {
    void library.load().catch(() => undefined);
  }

  // A plain function, not a nested component: a component type made per render would remount
  // the tabs on every change and lose the keyboard focus.
  function list(entries: readonly LibraryEntry[]): ReactNode {
    const threshold = watchedThreshold();
    const visible = selectLibrary(entries, status, sort);
    const copy = emptyTabCopy(status);
    return (
      <>
        <PillGroup
          kind="tab"
          label="Мой список"
          options={libraryTabs(entries).map((tab) => ({ value: tab.status, label: tab.text }))}
          value={status}
          onChange={(next) => setView("tab", next)}
          panelId={PANEL_ID}
          idPrefix={TAB_PREFIX}
          className="lib-tabs"
        />
        <div className="lib-sort">
          {SORT_OPTIONS.map((option) => (
            <button
              key={option.value}
              type="button"
              className="t-title-sm"
              aria-pressed={option.value === sort}
              onClick={() => setView("sort", option.value)}
            >
              {option.label}
            </button>
          ))}
        </div>
        <div
          role="tabpanel"
          id={PANEL_ID}
          aria-labelledby={`${TAB_PREFIX}-${status}`}
          tabIndex={0}
          className="lib-panel"
        >
          {visible.length > 0 ? (
            <PosterGrid>
              {visible.map((entry) => (
                <PosterCard
                  key={entry.anime.id}
                  layout="grid"
                  card={libraryCard(entry, progress.of(entry.anime.id), threshold)}
                />
              ))}
            </PosterGrid>
          ) : (
            <EmptyState
              align="start"
              title={copy.title}
              text={copy.text}
              action={copy.offersSearch ? { label: "Найти аниме", to: "/search" } : undefined}
            />
          )}
        </div>
      </>
    );
  }

  const entries = state.kind === "idle" ? null : state.entries;

  return (
    <div className="page">
      <h1 className="page-title t-headline">Мой список</h1>
      <div className="browse-body">
        {entries !== null ? (
          list(entries)
        ) : state.kind === "error" ? (
          <ErrorState message={state.message} onRetry={retry} />
        ) : (
          <SkeletonGroup label={SYNCING}>
            <SkeletonGrid />
          </SkeletonGroup>
        )}
      </div>
    </div>
  );
}
