import { useCallback, useEffect, useId, useMemo, useReducer, useRef, useState, useSyncExternalStore } from "react";
import type { ReactNode } from "react";
import type { Shikimori } from "../api/shikimori";
import { useServices } from "../app/services";
import { episodeLine, primaryAction, watchPath } from "../domain/actions";
import { buildFeed, feedRows, isFeedEmpty } from "../domain/feed";
import type { FeedItem } from "../domain/feed";
import type { Anime, EpisodeProgress } from "../domain/models";
import { seasonApiValue, seasonChips, seasonTitle } from "../domain/season";
import type { Season } from "../domain/season";
import { useLibrary } from "../library/library";
import type { LibraryState } from "../library/library";
import { watchedThreshold } from "../library/prefs";
import type { ProgressStore } from "../library/progress";
import { PrimaryButton, SecondaryButton, TextAction } from "../ui/Button";
import { IconPlay } from "../ui/icons";
import { PillGroup } from "../ui/Pill";
import { PosterCard } from "../ui/PosterCard";
import { Shelf } from "../ui/Shelf";
import { SkeletonBlock, SkeletonGroup, SkeletonHero, SkeletonShelf } from "../ui/Skeleton";
import { EmptyState, ErrorState, SyncingNotice } from "../ui/States";
import { useToast } from "../ui/Toast";
import { catalogueCache, homeContent, libraryEntries, popularNowContent, seasonalContent } from "./home";
import type { CatalogueCache, DiscoverContent } from "./home";
import "./HomeScreen.css";

const PAGE_TITLE = "Главная";
const DETAILS = "Подробнее";
const EMPTY_TITLE = "Здесь появятся тайтлы из списка «Смотрю»";
const EMPTY_TEXT =
  "Отметьте аниме как «Смотрю» на Shikimori или найдите его здесь. Kaeru продолжит с той серии, на которой вы остановились.";
const FIND_ANIME = "Найти аниме";
const OFFLINE = "Нет сети";
const RETRY = "Повторить";
const POPULAR_NOW = "Популярно сейчас";
const POPULAR_IN_SEASON = "Популярное в сезоне";
const SEASON_GROUP = "Сезон";
const SEASON_EMPTY = "В этом сезоне пока ничего нет";
const SEASON_FAILED = "Не удалось загрузить сезон";
/** Cache key of the one catalogue row that is not about a season (Android's NOW_KEY). */
const NOW_KEY = "now";
const SKELETON_CARDS = [0, 1, 2, 3, 4, 5];

export interface HomeScreenProps {
  /** Clock for the feed and the season chips; tests pin it. */
  now?: () => number;
  /** Catalogue cache; the module one survives leaving and re-entering the screen. */
  catalogue?: CatalogueCache;
}

export function HomeScreen({ now = Date.now, catalogue = catalogueCache }: HomeScreenProps) {
  const { shikimori, library, progress } = useServices();
  const state = useLibrary(library);
  const progressVersion = useProgressVersion(progress);
  const online = useOnline();
  // Read once per render so the hero label and the cards agree on what «watched» means.
  const threshold = watchedThreshold();
  // One clock per feed: «осталось 14 мин» and «завтра» do not tick while being read.
  const feedNow = useMemo(() => now(), [now, state, progressVersion]);
  const progressOf = useCallback(
    (animeId: number): readonly EpisodeProgress[] => progress.of(animeId),
    [progress, progressVersion],
  );
  const feed = useMemo(
    () => buildFeed(libraryEntries(state), progressOf, feedNow, threshold),
    [state, progressOf, feedNow, threshold],
  );
  const rows = useMemo(() => feedRows(feed, progressOf, feedNow, threshold), [feed, progressOf, feedNow, threshold]);
  const content = homeContent(state, isFeedEmpty(feed));
  const discover = useDiscover(shikimori, catalogue, now, online);
  const { show } = useToast();

  const reload = useCallback(() => {
    // Library reports failures through its state; there is nothing to handle here.
    library.load().catch(() => undefined);
  }, [library]);

  // Sync once per start, as Android does; «Повторить» asks again. The app shell may have started it.
  useEffect(() => {
    if (library.state().kind === "idle") reload();
  }, [library, reload]);

  // Over a feed the viewer can still use, a failed refresh is a toast; offline, the strip says it.
  const reported = useRef<LibraryState | null>(null);
  useEffect(() => {
    if (state.kind !== "error" || content.kind !== "feed" || !online || reported.current === state) return;
    reported.current = state;
    show(state.message, { label: RETRY, onClick: reload });
  }, [state, content.kind, online, show, reload]);

  const top = content.kind === "feed" ? feed.top : null;

  return (
    <div className="home">
      {/* The hero is the page's visible title; this names the page for screen readers. */}
      <h1 className="visually-hidden">{PAGE_TITLE}</h1>
      {!online && (
        <p className="home-offline t-label" role="status">
          {OFFLINE}
        </p>
      )}
      {content.kind === "loading" && (
        <SkeletonGroup>
          <HomeSkeleton />
        </SkeletonGroup>
      )}
      {content.kind === "first_sync" && (
        <>
          {/* The notice is the one announcement; the blocks under it are hidden from screen readers. */}
          <SyncingNotice />
          <HomeSkeleton />
        </>
      )}
      {content.kind === "error" && <ErrorState message={content.message} onRetry={reload} />}
      {content.kind === "empty" && (
        <>
          <EmptyState title={EMPTY_TITLE} text={EMPTY_TEXT} action={{ label: FIND_ANIME, to: "/search" }} />
          {online && <DiscoverRows discover={discover} />}
        </>
      )}
      {content.kind === "feed" && (
        <>
          {top && <Hero item={top} progress={progressOf(top.entry.anime.id)} threshold={threshold} now={feedNow} />}
          {rows.map((row) => (
            <Shelf key={row.title} title={row.title}>
              {row.cards.map((card) => (
                <PosterCard key={card.key} card={card} />
              ))}
            </Shelf>
          ))}
          {online && <DiscoverRows discover={discover} />}
        </>
      )}
    </div>
  );
}

function useProgressVersion(progress: ProgressStore): number {
  const [version, bump] = useReducer((n: number) => n + 1, 0);
  useEffect(() => progress.subscribe(bump), [progress]);
  return version;
}

function subscribeOnline(listener: () => void): () => void {
  window.addEventListener("online", listener);
  window.addEventListener("offline", listener);
  return () => {
    window.removeEventListener("online", listener);
    window.removeEventListener("offline", listener);
  };
}

function useOnline(): boolean {
  return useSyncExternalStore(subscribeOnline, () => navigator.onLine);
}

interface Discover {
  popularNow: Anime[] | null;
  loadingNow: boolean;
  seasons: readonly [Season, Season, Season];
  season: Season;
  seasonTitles: Anime[] | null;
  loadingSeason: boolean;
  anySeasonLoaded: boolean;
  /** Takes a chip's value, which is the season's API value («summer_2026»). */
  select: (value: string) => void;
  retrySeason: () => void;
}

function useDiscover(shikimori: Shikimori, cache: CatalogueCache, now: () => number, online: boolean): Discover {
  const [seasons] = useState(() => seasonChips(now()));
  const [season, setSeason] = useState<Season>(seasons[1]);
  const [popularNow, setPopularNow] = useState<Anime[] | null>(null);
  const [loadingNow, setLoadingNow] = useState(false);
  // Visited seasons stay in memory so a chip pressed again shows its titles without a blink.
  const [seasonal, setSeasonal] = useState<ReadonlyMap<string, Anime[]>>(() => new Map());
  const [loadingKeys, setLoadingKeys] = useState<ReadonlySet<string>>(() => new Set());
  const [anySeasonLoaded, setAnySeasonLoaded] = useState(false);
  // Keys already on the wire; StrictMode runs effects twice and a chip can be pressed twice.
  const inFlight = useRef(new Set<string>());

  const loadNow = useCallback(() => {
    if (inFlight.current.has(NOW_KEY)) return;
    inFlight.current.add(NOW_KEY);
    setLoadingNow(true);
    cache
      .read(NOW_KEY, () => shikimori.popularNow())
      .then(
        (titles) => setPopularNow(titles),
        // A failure keeps what is on screen; with nothing there the row stays hidden.
        () => undefined,
      )
      .finally(() => {
        inFlight.current.delete(NOW_KEY);
        setLoadingNow(false);
      });
  }, [cache, shikimori]);

  const loadSeason = useCallback(
    (target: Season) => {
      const key = seasonApiValue(target);
      if (inFlight.current.has(key)) return;
      inFlight.current.add(key);
      setLoadingKeys((keys) => new Set(keys).add(key));
      cache
        .read(key, () => shikimori.popularInSeason(target))
        .then(
          (titles) => {
            setSeasonal((map) => new Map(map).set(key, titles));
            setAnySeasonLoaded(true);
          },
          // Forgotten rather than remembered empty, so pressing the chip again is a retry.
          () =>
            setSeasonal((map) => {
              const next = new Map(map);
              next.delete(key);
              return next;
            }),
        )
        .finally(() => {
          inFlight.current.delete(key);
          setLoadingKeys((keys) => {
            const next = new Set(keys);
            next.delete(key);
            return next;
          });
        });
    },
    [cache, shikimori],
  );

  const select = useCallback(
    (value: string) => {
      const next = seasons.find((candidate) => seasonApiValue(candidate) === value);
      if (next) setSeason(next);
    },
    [seasons],
  );

  // The catalogue is the one part of Home that needs the network; it loads when there is one.
  useEffect(() => {
    if (online) loadNow();
  }, [online, loadNow]);

  useEffect(() => {
    if (online) loadSeason(season);
  }, [online, season, loadSeason]);

  const key = seasonApiValue(season);
  return {
    popularNow,
    loadingNow,
    seasons,
    season,
    seasonTitles: seasonal.get(key) ?? null,
    loadingSeason: loadingKeys.has(key),
    anySeasonLoaded,
    select,
    retrySeason: () => loadSeason(season),
  };
}

interface HeroProps {
  item: FeedItem;
  progress: readonly EpisodeProgress[];
  threshold: number;
  now: number;
}

function Hero({ item, progress, threshold, now }: HeroProps) {
  const titleId = useId();
  const anime = item.entry.anime;
  // The same decision the title screen uses, so hero and title page never name different episodes.
  const action = primaryAction(item.entry, anime, progress, threshold, now);
  const line = episodeLine(item.kind, item.entry, item.episode, progress, now);
  return (
    <section className="hero" aria-labelledby={titleId}>
      <HeroArt anime={anime} />
      <div className="hero-text">
        <h2 id={titleId} className="hero-title t-display">
          {anime.title}
        </h2>
        <p className="hero-line t-body">{line}</p>
        <div className="hero-actions">
          {/* Navigation is a link; an episode that cannot start yet is a disabled button going nowhere. */}
          {action.enabled ? (
            <PrimaryButton to={watchPath(anime.id, action.episode)} icon={<IconPlay />}>
              {action.label}
            </PrimaryButton>
          ) : (
            <PrimaryButton disabled icon={<IconPlay />}>
              {action.label}
            </PrimaryButton>
          )}
          <SecondaryButton to={`/anime/${anime.id}`}>{DETAILS}</SecondaryButton>
        </div>
      </div>
    </section>
  );
}

// Decorative: the title sits right beside the artwork in text.
function HeroArt({ anime }: { anime: Anime }) {
  return (
    <div className="hero-art" aria-hidden="true">
      {anime.backdropUrl ? (
        <img className="hero-backdrop" src={anime.backdropUrl} alt="" />
      ) : anime.posterUrl ? (
        <>
          <img className="hero-blur" src={anime.posterUrl} alt="" />
          <img className="hero-poster" src={anime.posterUrl} alt="" />
        </>
      ) : null}
      <div className="hero-scrim" />
    </div>
  );
}

function DiscoverRows({ discover }: { discover: Discover }) {
  const popular = popularNowContent(discover.popularNow, discover.loadingNow);
  const seasonal = seasonalContent(discover.seasonTitles, discover.loadingSeason, discover.anySeasonLoaded);
  if (popular === null && seasonal === null) return null;
  return (
    <div className="home-discover">
      {popular && <DiscoverShelf title={POPULAR_NOW} content={popular} />}
      {seasonal && (
        <DiscoverShelf
          title={POPULAR_IN_SEASON}
          content={seasonal}
          switcher={
            <PillGroup
              kind="radio"
              label={SEASON_GROUP}
              className="home-seasons"
              options={discover.seasons.map((season) => ({ value: seasonApiValue(season), label: seasonTitle(season) }))}
              value={seasonApiValue(discover.season)}
              onChange={discover.select}
            />
          }
          onRetry={discover.retrySeason}
        />
      )}
    </div>
  );
}

interface DiscoverShelfProps {
  title: string;
  content: DiscoverContent;
  switcher?: ReactNode;
  onRetry?: () => void;
}

function DiscoverShelf({ title, content, switcher, onRetry }: DiscoverShelfProps) {
  // A row reporting on itself is one quiet line under its heading, not a centred empty state.
  const status =
    content.kind === "loading" ? (
      <RowSkeleton />
    ) : content.kind === "empty" ? (
      <p className="home-note t-body">{SEASON_EMPTY}</p>
    ) : content.kind === "failed" ? (
      <div className="home-failed">
        <p className="home-note t-body">{SEASON_FAILED}</p>
        {onRetry && (
          <TextAction className="home-retry" onClick={onRetry}>
            {RETRY}
          </TextAction>
        )}
      </div>
    ) : null;
  const extra =
    switcher || status ? (
      <>
        {switcher}
        {status}
      </>
    ) : undefined;
  return (
    <Shelf title={title} extra={extra}>
      {content.kind === "titles" ? content.cards.map((card) => <PosterCard key={card.key} card={card} />) : null}
    </Shelf>
  );
}

// The row keeps its real heading (and chips) while loading; only the cards are placeholders.
function RowSkeleton() {
  return (
    <SkeletonGroup>
      <div className="home-skel-row">
        {SKELETON_CARDS.map((n) => (
          <div key={n} className="home-skel-card">
            <SkeletonBlock className="home-skel-art" />
            <SkeletonBlock height={16} width="80%" />
          </div>
        ))}
      </div>
    </SkeletonGroup>
  );
}

// The shape of the screen before the feed arrives, so nothing jumps when it does.
function HomeSkeleton() {
  return (
    <>
      <SkeletonHero />
      <SkeletonShelf />
      <SkeletonShelf />
    </>
  );
}
