import { useEffect, useRef, useState } from "react";
import type { FormEvent, ReactNode } from "react";
import { useSearchParams } from "react-router-dom";
import { errorMessage } from "../api/http";
import { useServices } from "../app/services";
import { catalogueCard } from "../domain/feed";
import type { Card } from "../domain/feed";
import type { Anime } from "../domain/models";
import { listKnown, useLibrary } from "../library/library";
import { IconButton, SecondaryButton } from "../ui/Button";
import { IconClose, IconSearch } from "../ui/icons";
import { PosterCard, PosterGrid } from "../ui/PosterCard";
import { SkeletonGrid, SkeletonGroup } from "../ui/Skeleton";
import { EmptyState, ErrorState } from "../ui/States";
import { useToast } from "../ui/Toast";
import { CatalogueCache, catalogueCache } from "./home";
import "./browse.css";

// Below two characters a query matches half the catalogue (iOS SearchView, Android SearchViewModel).
const MIN_QUERY = 2;
// Pause after the last keystroke before a query goes out (iOS SearchView).
const DEBOUNCE_MS = 350;
// Home's «Популярно сейчас» row reads the same key, so the two screens share one read per 6 h.
const POPULAR_KEY = "now";
// Answers are kept for a while, so Back from a title paints the results it left at once.
const SEARCH_TTL_MS = 30 * 60 * 1000;

/** Module-wide so a title opened from the results and closed again costs no second search. */
export const searchCache = new CatalogueCache({ ttlMs: SEARCH_TTL_MS });

export interface SearchScreenProps {
  /** Where «Популярно сейчас» is kept: the module-wide cache Home also uses, unless a test passes its own. */
  catalogue?: CatalogueCache;
  /** Answers to earlier queries: the module-wide cache, unless a test passes its own. */
  searches?: CatalogueCache;
}

type Results =
  | { kind: "idle" }
  | { kind: "loading"; query: string }
  | { kind: "found"; query: string; titles: Anime[] }
  | { kind: "none"; query: string }
  | { kind: "failed"; query: string; message: string };

type Popular = { kind: "loading" } | { kind: "ready"; titles: Anime[] } | { kind: "failed" };

// The year rides on the artwork: it tells two seasons of one show apart (Android ResultCard).
function searchCard(anime: Anime): Card {
  return { ...catalogueCard(anime), badge: anime.year === null ? null : String(anime.year), subtitle: null };
}

function remembered(searches: CatalogueCache, query: string): Results {
  const titles = query.length < MIN_QUERY ? undefined : searches.peek(query);
  if (titles === undefined) return { kind: "idle" };
  return titles.length > 0 ? { kind: "found", query, titles } : { kind: "none", query };
}

export function SearchScreen({ catalogue = catalogueCache, searches = searchCache }: SearchScreenProps) {
  const { shikimori, library } = useServices();
  // Subscribed so cards repaint when a title lands in the list or a failed add is reverted.
  const known = listKnown(useLibrary(library));
  const toast = useToast();
  const [params, setParams] = useSearchParams();
  const [text, setText] = useState(() => params.get("q") ?? "");
  const [results, setResults] = useState<Results>(() => remembered(searches, text.trim()));
  const [popular, setPopular] = useState<Popular>({ kind: "loading" });
  const [adding, setAdding] = useState<ReadonlySet<number>>(() => new Set());
  const field = useRef<HTMLInputElement>(null);
  // A remembered answer counts as sent: the query is not asked again.
  const sent = useRef<string | null>(results.kind === "idle" ? null : text.trim());
  const ticket = useRef(0);
  const opened = useRef(text.trim());

  useEffect(() => {
    if (library.state().kind === "idle") void library.load().catch(() => undefined);
  }, [library]);

  useEffect(() => {
    let live = true;
    catalogue.read(POPULAR_KEY, () => shikimori.popularNow()).then(
      (titles) => {
        if (live) setPopular({ kind: "ready", titles });
      },
      () => {
        if (live) setPopular({ kind: "failed" });
      },
    );
    return () => {
      live = false;
    };
  }, [catalogue, shikimori]);

  function run(query: string) {
    sent.current = query;
    ticket.current += 1;
    const mine = ticket.current;
    setResults({ kind: "loading", query });
    if (params.get("q") !== query) setParams({ q: query }, { replace: true });
    searches.read(query, () => shikimori.search(query), true).then(
      (titles) => {
        // Only the newest query may paint: a slow answer to an older one is dropped.
        if (mine !== ticket.current) return;
        setResults(titles.length > 0 ? { kind: "found", query, titles } : { kind: "none", query });
      },
      (error: unknown) => {
        if (mine !== ticket.current) return;
        setResults({ kind: "failed", query, message: errorMessage(error) });
      },
    );
  }

  useEffect(() => {
    const query = text.trim();
    if (query.length < MIN_QUERY) {
      ticket.current += 1;
      sent.current = null;
      setResults({ kind: "idle" });
      if (params.has("q")) setParams({}, { replace: true });
      return undefined;
    }
    if (query === sent.current) return undefined;
    // A query that came with the address runs at once; typing waits for a pause.
    const delay = sent.current === null && query === opened.current ? 0 : DEBOUNCE_MS;
    const timer = setTimeout(() => {
      if (sent.current !== query) run(query);
    }, delay);
    return () => clearTimeout(timer);
  }, [text]);

  function submit(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    const query = text.trim();
    if (query.length >= MIN_QUERY) run(query);
    // Enter also puts the on-screen keyboard away.
    field.current?.blur();
  }

  function clear() {
    setText("");
    field.current?.focus();
  }

  async function add(anime: Anime) {
    setAdding((current) => new Set(current).add(anime.id));
    try {
      await library.setStatus(anime, "planned");
    } catch (error) {
      toast.show(errorMessage(error), {
        label: "Повторить",
        onClick: () => {
          void add(anime);
        },
      });
    } finally {
      setAdding((current) => {
        const next = new Set(current);
        next.delete(anime.id);
        return next;
      });
    }
  }

  function addButton(anime: Anime) {
    // Already in the list wins over a write in flight (Android addAction).
    const inList = library.entry(anime.id) !== undefined;
    const busy = adding.has(anime.id);
    // Until the list is read, a title Shikimori already holds looks absent and an add would overwrite it.
    return (
      <SecondaryButton
        compact
        fullWidth
        disabled={inList || busy || !known}
        aria-label={inList || busy ? undefined : `Добавить ${anime.title} в планы`}
        onClick={() => {
          void add(anime);
        }}
      >
        {inList ? "В списке" : busy ? "Добавляем…" : "В планы"}
      </SecondaryButton>
    );
  }

  function grid(titles: readonly Anime[]): ReactNode {
    return (
      <PosterGrid>
        {titles.map((anime) => (
          <PosterCard key={anime.id} layout="grid" card={searchCard(anime)} footer={addButton(anime)} />
        ))}
      </PosterGrid>
    );
  }

  function idle(): ReactNode {
    if (popular.kind === "failed" || (popular.kind === "ready" && popular.titles.length === 0)) {
      return (
        <EmptyState
          title="Что посмотреть сегодня?"
          text="Введите название — Kaeru поищет его на Shikimori и положит найденное в ваш список."
        />
      );
    }
    return (
      <section aria-labelledby="srch-popular">
        <h2 id="srch-popular" className="t-title srch-section__title">
          Популярно сейчас
        </h2>
        {popular.kind === "ready" ? (
          grid(popular.titles)
        ) : (
          <SkeletonGroup>
            <SkeletonGrid />
          </SkeletonGroup>
        )}
      </section>
    );
  }

  function body(): ReactNode {
    switch (results.kind) {
      case "idle":
        return idle();
      case "loading":
        return (
          <SkeletonGroup label="Ищем аниме…">
            <SkeletonGrid />
          </SkeletonGroup>
        );
      case "found":
        return grid(results.titles);
      case "none":
        return <EmptyState title="Ничего не найдено" text="Попробуйте оригинальное название или короче." />;
      case "failed": {
        const query = results.query;
        return <ErrorState message={results.message} onRetry={() => run(query)} />;
      }
    }
  }

  return (
    <div className="page">
      <h1 className="page-title t-headline">Поиск</h1>
      <div className="browse-body">
        <form role="search" className="srch-form" onSubmit={submit}>
          <IconSearch className="srch-form__icon" size={20} />
          <input
            ref={field}
            type="search"
            className="srch-form__input t-body-lg"
            value={text}
            onChange={(event) => setText(event.target.value)}
            placeholder="Название аниме"
            aria-label="Название аниме"
            enterKeyHint="search"
            autoComplete="off"
            spellCheck={false}
          />
          {text === "" ? null : (
            <IconButton label="Очистить" icon={<IconClose />} className="srch-form__clear" onClick={clear} />
          )}
        </form>
        <div className="srch-body">{body()}</div>
      </div>
    </div>
  );
}
