import { useEffect, useId, useLayoutEffect, useRef, useState, useSyncExternalStore } from "react";
import { Link, useLocation, useNavigate, useParams } from "react-router-dom";
import { errorMessage } from "../api/http";
import { useServices } from "../app/services";
import { primaryAction } from "../domain/actions";
import { factsLine, formatTime, pluralEpisodesAccusative, statusLabel } from "../domain/format";
import { STATUS_MENU, type Anime, type EpisodeProgress, type LibraryEntry, type ListStatus } from "../domain/models";
import { listKnown, useLibrary } from "../library/library";
import { watchedThreshold } from "../library/prefs";
import { IconButton, PrimaryButton, SecondaryButton, TextAction } from "../ui/Button";
import { Dialog } from "../ui/Dialog";
import { IconBack, IconCheckCircle, IconClock, IconMore, IconPlay, IconPlayCircle } from "../ui/icons";
import { MenuButton, type MenuItem } from "../ui/Menu";
import { SkeletonBlock, SkeletonGroup } from "../ui/Skeleton";
import { ErrorState } from "../ui/States";
import { useToast } from "../ui/Toast";
import { EPISODE_PAGE, episodeRows, type EpisodeRow } from "./episodeRows";
import "./TitleScreen.css";

const LOAD_FAILED = "Не удалось загрузить аниме. Проверьте соединение и повторите";

type Details = { kind: "loading" } | { kind: "ready"; anime: Anime } | { kind: "failed" };

function watchPath(animeId: number, episode: number): string {
  return `/watch/${animeId}/${episode}`;
}

/** /anime/:id — details, list status and episode marks (map 4, decisions 4–7 and 9). */
export function TitleScreen() {
  const { id } = useParams();
  const animeId = Number(id);
  const { shikimori, library, progress } = useServices();
  const [details, setDetails] = useState<Details>({ kind: "loading" });
  const [attempt, setAttempt] = useState(0);
  const [threshold] = useState(() => watchedThreshold());
  const libraryState = useLibrary(library);
  const entry = useSyncExternalStore(library.subscribe, () => library.entry(animeId));
  const rows = useSyncExternalStore(progress.subscribe, () => progress.of(animeId));

  useEffect(() => {
    // The signed-in shell starts the list too; whoever comes first starts it once.
    if (library.state().kind === "idle") void library.load().catch(() => undefined);
  }, [library]);

  useEffect(() => {
    if (!Number.isInteger(animeId) || animeId <= 0) {
      setDetails({ kind: "failed" });
      return;
    }
    let live = true;
    setDetails({ kind: "loading" });
    shikimori.details(animeId).then(
      (anime) => {
        if (live) setDetails({ kind: "ready", anime });
      },
      () => {
        if (live) setDetails({ kind: "failed" });
      },
    );
    return () => {
      live = false;
    };
  }, [shikimori, animeId, attempt]);

  const retry = () => setAttempt((count) => count + 1);
  // Details are the full card; the list's card is enough to draw while they load.
  const anime = details.kind === "ready" && details.anime.id === animeId ? details.anime : (entry?.anime ?? null);

  if (anime === null) {
    return details.kind === "failed" ? (
      <div className="title title-failed">
        <ErrorState message={LOAD_FAILED} onRetry={retry} />
      </div>
    ) : (
      <TitleSkeleton />
    );
  }
  return (
    <TitleContent
      key={anime.id}
      anime={anime}
      entry={entry}
      rows={rows}
      threshold={threshold}
      known={listKnown(libraryState)}
      failed={details.kind === "failed"}
      onRetry={retry}
    />
  );
}

interface ContentProps {
  anime: Anime;
  entry: LibraryEntry | undefined;
  rows: readonly EpisodeProgress[];
  threshold: number;
  known: boolean;
  failed: boolean;
  onRetry: () => void;
}

function TitleContent({ anime, entry, rows, threshold, known, failed, onRetry }: ContentProps) {
  const { library } = useServices();
  const toast = useToast();
  const navigate = useNavigate();
  const location = useLocation();
  const controls = useRef<HTMLDivElement>(null);
  const [completion, setCompletion] = useState(false);
  const [focusStatus, setFocusStatus] = useState(false);

  useEffect(() => {
    // «Добавить в планы» turns into the status menu; keep the keyboard where it was.
    if (focusStatus && entry) {
      controls.current?.querySelector<HTMLButtonElement>("button")?.focus();
      setFocusStatus(false);
    }
  }, [focusStatus, entry]);

  const action = primaryAction(entry ?? null, anime, rows, threshold, Date.now());
  const facts = factsLine(anime);
  // Task 5 already cleaned the text; a second pass would decode entities twice.
  const description = anime.description;
  const artwork = anime.backdropUrl ?? anime.posterUrl;
  const current = entry?.rate.status;

  const fail = (error: unknown): void => {
    toast.show(errorMessage(error));
  };
  const back = () => {
    // A deep link has no page of ours to go back to.
    if (location.key !== "default") void navigate(-1);
    else void navigate("/");
  };
  const play = (episode: number) => {
    void navigate(watchPath(anime.id, episode));
  };
  const changeStatus = (status: ListStatus) => {
    library.setStatus(anime, status).catch(fail);
  };
  const addToPlans = () => {
    setFocusStatus(true);
    changeStatus("planned");
  };
  const markWatched = (episode: number) => {
    library.markWatched(anime, episode).then(({ suggestCompleted }) => {
      // Only ever a suggestion; an already completed title has nothing to ask.
      if (suggestCompleted && library.entry(anime.id)?.rate.status !== "completed") setCompletion(true);
    }, fail);
  };
  const markUnwatched = (episode: number) => {
    library.markUnwatched(anime, episode).then((undo) => {
      // No confirmation first: the undo is the better one (decision 6).
      toast.show(`Серия ${episode} отмечена непросмотренной`, {
        label: "Отменить",
        onClick: () => {
          undo().catch(fail);
        },
      });
    }, fail);
  };
  const complete = () => {
    setCompletion(false);
    changeStatus("completed");
  };

  const statusItems: MenuItem[] = STATUS_MENU.map((status) => ({
    key: status,
    label: statusLabel(status),
    checked: status === current,
    onSelect: () => changeStatus(status),
  }));

  return (
    <div className="title">
      <header className="title-hero">
        <div className="title-hero-art" aria-hidden="true">
          {artwork !== null && (
            <img
              className={anime.backdropUrl !== null ? "title-hero-img" : "title-hero-img title-hero-img-soft"}
              src={artwork}
              alt=""
              decoding="async"
            />
          )}
        </div>
        <IconButton className="title-back" label="Назад" icon={<IconBack />} overArt onClick={back} />
        <div className="title-hero-text">
          <h1 className="t-display title-name">{anime.title}</h1>
          {anime.originalTitle !== "" && anime.originalTitle !== anime.title && (
            <p className="t-body title-original">{anime.originalTitle}</p>
          )}
          {facts !== "" && <p className="t-label title-facts">{facts}</p>}
          <div className="title-play">
            {action.enabled ? (
              // Navigation is a link (Task 8); plan 3 puts the player behind it.
              <PrimaryButton to={watchPath(anime.id, action.episode)} icon={<IconPlay />}>
                {action.label}
              </PrimaryButton>
            ) : (
              <PrimaryButton disabled icon={<IconClock />}>
                {action.label}
              </PrimaryButton>
            )}
          </div>
        </div>
      </header>
      <div className="title-body">
        {failed && <ErrorState message={LOAD_FAILED} onRetry={onRetry} align="start" />}
        <div className="title-controls" ref={controls}>
          {entry ? (
            <MenuButton label={statusLabel(entry.rate.status)} items={statusItems} disabled={!known} />
          ) : (
            <SecondaryButton disabled={!known} onClick={addToPlans}>
              Добавить в планы
            </SecondaryButton>
          )}
        </div>
        {description !== null && description !== "" && <About text={description} />}
        <Episodes
          anime={anime}
          entry={entry}
          rows={rows}
          threshold={threshold}
          known={known}
          onPlay={play}
          onMark={markWatched}
          onUnmark={markUnwatched}
        />
      </div>
      <Dialog
        open={completion}
        title="Перевести аниме в завершённые?"
        text={anime.title}
        confirmLabel="Завершить просмотр"
        cancelLabel="Позже"
        onConfirm={complete}
        onCancel={() => setCompletion(false)}
      />
    </div>
  );
}

function About({ text }: { text: string }) {
  const headingId = useId();
  const textId = useId();
  const paragraph = useRef<HTMLParagraphElement>(null);
  const [expanded, setExpanded] = useState(false);
  const [clipped, setClipped] = useState(false);

  useLayoutEffect(() => {
    if (expanded) return;
    const measure = () => {
      const node = paragraph.current;
      if (node) setClipped(node.scrollHeight > node.clientHeight + 1);
    };
    measure();
    window.addEventListener("resize", measure);
    // Manrope arriving late changes how many lines the text takes.
    void document.fonts?.ready.then(measure);
    return () => window.removeEventListener("resize", measure);
  }, [text, expanded]);

  return (
    <section className="title-section" aria-labelledby={headingId}>
      <h2 id={headingId} className="t-title title-section-head">
        Об аниме
      </h2>
      <p
        id={textId}
        ref={paragraph}
        data-clamp="about"
        className={`t-body title-about${expanded ? "" : " title-about-clamped"}`}
      >
        {text}
      </p>
      {(clipped || expanded) && (
        <TextAction aria-expanded={expanded} aria-controls={textId} onClick={() => setExpanded((open) => !open)}>
          {expanded ? "Свернуть" : "Читать полностью"}
        </TextAction>
      )}
    </section>
  );
}

interface EpisodesProps {
  anime: Anime;
  entry: LibraryEntry | undefined;
  rows: readonly EpisodeProgress[];
  threshold: number;
  known: boolean;
  onPlay: (episode: number) => void;
  onMark: (episode: number) => void;
  onUnmark: (episode: number) => void;
}

function Episodes({ anime, entry, rows, threshold, known, onPlay, onMark, onUnmark }: EpisodesProps) {
  const headingId = useId();
  const [visible, setVisible] = useState(EPISODE_PAGE);
  const all = episodeRows(anime, entry ?? null, rows, threshold);

  if (all.length === 0) {
    return (
      <section className="title-section" aria-labelledby={headingId}>
        <h2 id={headingId} className="t-title title-section-head">
          Серии
        </h2>
        <p className="t-title title-empty-title">Серии ещё не вышли</p>
        <p className="t-body title-muted">Добавьте аниме в планы, чтобы вернуться к нему позже.</p>
      </section>
    );
  }

  const rest = all.length - visible;
  // The label counts what pressing it adds, never a promise of «all» (Android EpisodeSection).
  const next = Math.min(EPISODE_PAGE, rest);
  return (
    <section className="title-section" aria-labelledby={headingId}>
      <h2 id={headingId} className="t-title title-section-head">
        Серии
      </h2>
      <p className="t-body title-muted">{`Просмотрено ${entry?.rate.episodes ?? 0} из ${all.length}`}</p>
      <ul className="title-episodes" aria-labelledby={headingId}>
        {all.slice(0, visible).map((row) => (
          <EpisodeItem
            key={row.number}
            animeId={anime.id}
            row={row}
            known={known}
            onPlay={onPlay}
            onMark={onMark}
            onUnmark={onUnmark}
          />
        ))}
      </ul>
      {rest > 0 ? (
        <TextAction onClick={() => setVisible((shown) => shown + next)}>
          {`Показать ещё ${pluralEpisodesAccusative(next)}`}
        </TextAction>
      ) : all.length > EPISODE_PAGE ? (
        <TextAction onClick={() => setVisible(EPISODE_PAGE)}>Свернуть серии</TextAction>
      ) : null}
    </section>
  );
}

interface EpisodeItemProps {
  animeId: number;
  row: EpisodeRow;
  known: boolean;
  onPlay: (episode: number) => void;
  onMark: (episode: number) => void;
  onUnmark: (episode: number) => void;
}

function EpisodeItem({ animeId, row, known, onPlay, onMark, onUnmark }: EpisodeItemProps) {
  const name = `${row.number} серия`;

  if (!row.aired) {
    // Nothing to play or mark yet: plain text, no link and no menu (Android has no menu here either).
    return (
      <li className="title-episode">
        <div className="title-episode-main title-episode-main-off">
          <span className="title-episode-icon" aria-hidden="true">
            <IconClock size={22} />
          </span>
          <span className="title-episode-name" aria-hidden="true">
            {name}
          </span>
          <span className="title-episode-caption" aria-hidden="true">
            Не вышла
          </span>
          <span className="visually-hidden">{`${name}, не вышла`}</span>
        </div>
      </li>
    );
  }

  const caption = row.fraction !== null ? formatTime(row.positionMs) : null;
  // One spoken sentence instead of a number and a fragment read separately.
  const spoken = row.watched
    ? `${name}, просмотрено`
    : caption !== null
      ? `${name}, остановились на ${caption}`
      : name;
  const items: MenuItem[] = [
    { key: "watch", label: "Смотреть", onSelect: () => onPlay(row.number) },
    row.watched
      ? { key: "unmark", label: "Отметить непросмотренной", destructive: true, onSelect: () => onUnmark(row.number) }
      : { key: "mark", label: "Отметить просмотренной", onSelect: () => onMark(row.number) },
  ];
  return (
    <li className="title-episode">
      <Link className="title-episode-main" to={watchPath(animeId, row.number)} aria-label={spoken}>
        <span className={`title-episode-icon${row.watched ? " title-episode-icon-watched" : ""}`} aria-hidden="true">
          {row.watched ? <IconCheckCircle size={22} /> : <IconPlayCircle size={22} />}
        </span>
        <span className="title-episode-name">{name}</span>
        {caption !== null && <span className="title-episode-caption">{caption}</span>}
      </Link>
      <MenuButton
        variant="icon"
        ariaLabel={`Что сделать с серией ${row.number}`}
        label={<IconMore />}
        items={items}
        disabled={!known}
      />
      {row.fraction !== null && (
        <span className="title-episode-progress" aria-hidden="true">
          <span style={{ width: `${Math.round(row.fraction * 100)}%` }} />
        </span>
      )}
    </li>
  );
}

function TitleSkeleton() {
  return (
    <div className="title">
      <SkeletonGroup label="Обновляем информацию…">
        <div className="title-hero">
          <div className="title-hero-text">
            <SkeletonBlock width="min(420px, 80%)" height={40} />
            <SkeletonBlock width="min(300px, 60%)" height={18} />
            <SkeletonBlock width={220} height={52} />
          </div>
        </div>
        <div className="title-body">
          {Array.from({ length: 6 }, (_, index) => (
            <SkeletonBlock key={index} height={44} />
          ))}
        </div>
      </SkeletonGroup>
    </div>
  );
}
