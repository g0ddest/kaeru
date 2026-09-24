import {
  useCallback,
  useEffect,
  useRef,
  useState,
  useSyncExternalStore,
  type CSSProperties,
  type PointerEvent as ReactPointerEvent,
} from "react";
import { Navigate, useLocation, useNavigate, useParams } from "react-router-dom";
import { useServices } from "../app/services";
import { waitingLabel } from "../domain/actions";
import { episodeBadge, formatTime } from "../domain/format";
import { availableEpisodes } from "../domain/models";
import { PlayerController, type MediaPort, type PlayerState } from "../player/controller";
import { playerKeyAction, type PlayerKeyAction } from "../player/keys";
import { clearMediaSession, showInMediaSession, showMediaPosition } from "../player/mediaSession";
import { CONTROLS_HIDE_MS, COUNTDOWN_S, JUMP_MS, SEEK_STEP_MS, lacksEpisode } from "../player/rules";
import { IconButton, PrimaryButton, SecondaryButton, TextAction } from "../ui/Button";
import { Dialog } from "../ui/Dialog";
import {
  IconBack,
  IconForward10,
  IconFullscreen,
  IconFullscreenExit,
  IconPause,
  IconPictureInPicture,
  IconPlay,
  IconReplay10,
  IconSkipNext,
  IconVolumeOff,
  IconVolumeUp,
} from "../ui/icons";
import { MenuButton, type MenuItem } from "../ui/Menu";
import { useToast } from "../ui/Toast";
import "./PlayerScreen.css";

/** What the screen draws before its controller exists: the first render, with the <video> already in it. */
const BEFORE: PlayerState = {
  anime: null,
  episode: 0,
  phase: "loading",
  failure: null,
  tracks: [],
  track: null,
  qualities: [],
  quality: null,
  positionMs: 0,
  durationMs: 0,
  paused: false,
  buffering: true,
  needsGesture: false,
  countdown: null,
  skip: null,
  endingDue: false,
  hasNext: false,
  completion: false,
  finished: false,
};
const noSubscribe = () => () => undefined;
const before = () => BEFORE;

/** Safari's prefixed fullscreen (before 16.4) and the iPhone's video-only one. */
interface WebkitDocument {
  webkitFullscreenEnabled?: boolean;
  webkitFullscreenElement?: Element | null;
  webkitExitFullscreen?: () => void;
}
interface WebkitElement {
  webkitRequestFullscreen?: () => void;
}
interface WebkitVideo {
  webkitEnterFullscreen?: () => void;
}

function fullscreenElement(): Element | null {
  const doc = document as Document & WebkitDocument;
  return doc.fullscreenElement ?? doc.webkitFullscreenElement ?? null;
}

/** Element fullscreen: desktops, Android and the iPad; never the iPhone. */
function elementFullscreen(): boolean {
  const doc = document as Document & WebkitDocument;
  return doc.fullscreenEnabled === true || doc.webkitFullscreenEnabled === true;
}

/** /watch/:id/:episode — a malformed address has nothing to play and goes where it can. */
export function PlayerScreen() {
  const { id = "", episode = "" } = useParams();
  const animeId = Number(id);
  const number = Number(episode);
  if (!Number.isInteger(animeId) || animeId <= 0) return <Navigate to="/" replace />;
  if (!Number.isInteger(number) || number <= 0) return <Navigate to={`/anime/${animeId}`} replace />;
  // No key: the next episode is the same player, the same <video>, fullscreen and all.
  return <Player animeId={animeId} episode={number} />;
}

/**
 * The full-window player (Android ui/mobile/player/PlayerScreen.kt, PlayerControls.kt). It renders the
 * controller's state and forwards the element's events; every rule lives in PlayerController.
 */
function Player({ animeId, episode }: { animeId: number; episode: number }) {
  const services = useServices();
  const toast = useToast();
  const navigate = useNavigate();
  const location = useLocation();
  // Taken on arrival: episodes replace the address later, and «Назад» must still know where it came from.
  const [fromApp] = useState(() => location.key !== "default");
  const root = useRef<HTMLElement>(null);
  const videoRef = useRef<HTMLVideoElement>(null);
  const seekRef = useRef<HTMLInputElement>(null);
  const countdownRef = useRef<HTMLElement>(null);
  const toastRef = useRef(toast);
  const [controller, setController] = useState<PlayerController | null>(null);
  const state = useSyncExternalStore(controller?.subscribe ?? noSubscribe, controller?.getState ?? before);

  const [muted, setMuted] = useState(false);
  const [fullscreen, setFullscreen] = useState(false);
  const [canFullscreen, setCanFullscreen] = useState(false);
  const [canPip, setCanPip] = useState(false);
  const [pip, setPip] = useState(false);
  const [dubOpen, setDubOpen] = useState(false);
  const [qualityOpen, setQualityOpen] = useState(false);
  /** Where the seek slider is being dragged; the episode moves there on release. */
  const [scrub, setScrub] = useState<number | null>(null);
  const [awake, setAwake] = useState(true);

  useEffect(() => {
    toastRef.current = toast;
  }, [toast]);

  // One controller and one engine for the screen's one <video>.
  useEffect(() => {
    const video = videoRef.current;
    if (video === null) return;
    let created: PlayerController | null = null;
    // The engine reports to whichever controller owns it; this closure is that link.
    const engine = services.engine(video, (kind) => created?.onEngineFailure(kind));
    const media: MediaPort = {
      // Old browsers return nothing from play(); a refusal then simply never arrives.
      play: () => (video.play() as Promise<void> | undefined) ?? Promise.resolve(),
      pause: () => video.pause(),
      seek: (ms) => {
        video.currentTime = ms / 1_000;
      },
    };
    created = new PlayerController({
      kodik: services.kodik,
      aniskip: services.aniskip,
      library: services.library,
      progress: services.progress,
      shikimori: services.shikimori,
      engine,
      media,
      toast: (text) => toastRef.current.show(text),
    });
    setController(created);
    const owned = created;
    return () => owned.dispose();
  }, [services]);

  // The address names the episode. One the controller moved to itself is already open; any other
  // (Back and Forward between episodes) is opened here.
  const opened = useRef<{ controller: PlayerController; key: string } | null>(null);
  useEffect(() => {
    if (controller === null) return;
    const key = `${animeId}/${episode}`;
    if (opened.current?.controller === controller && opened.current.key === key) return;
    opened.current = { controller, key };
    void controller.open(animeId, episode);
  }, [controller, animeId, episode]);

  // The next episode replaces the address rather than stacking a history entry per episode.
  const shownEpisode = state.episode;
  useEffect(() => {
    if (controller === null || shownEpisode <= 0) return;
    // Read now, not from this render: an open() started above has already moved the controller.
    const now = controller.getState().episode;
    const key = `${animeId}/${now}`;
    if (opened.current?.controller !== controller || opened.current.key === key) return;
    opened.current = { controller, key };
    void navigate(`/watch/${animeId}/${now}`, { replace: true });
  }, [controller, shownEpisode, animeId, navigate]);

  const { anime, track, hasNext, finished, completion } = state;
  const failed = state.phase === "failed";
  const loading = state.phase === "loading" || (state.buffering && state.durationMs === 0);
  const spinner = !failed && !state.needsGesture && (loading || state.buffering);
  const countdownUp = state.countdown !== null && hasNext;
  const waitingUp =
    !countdownUp &&
    state.endingDue &&
    !hasNext &&
    anime !== null &&
    availableEpisodes(anime) > 0 &&
    anime.status !== "released";
  const hold =
    state.phase !== "playing" ||
    state.paused ||
    state.needsGesture ||
    loading ||
    dubOpen ||
    qualityOpen ||
    countdownUp ||
    waitingUp ||
    completion ||
    scrub !== null;

  // Controls go after 3 s of playback with nothing touched, and never while anything asks for them.
  const hideTimer = useRef<ReturnType<typeof setTimeout> | null>(null);
  const holding = useRef(hold);
  const schedule = useCallback(() => {
    if (hideTimer.current !== null) clearTimeout(hideTimer.current);
    hideTimer.current = null;
    if (holding.current) return;
    hideTimer.current = setTimeout(() => {
      hideTimer.current = null;
      setAwake(false);
    }, CONTROLS_HIDE_MS);
  }, []);
  const wake = useCallback(() => {
    setAwake(true);
    schedule();
  }, [schedule]);
  const sleep = useCallback(() => {
    if (hideTimer.current !== null) clearTimeout(hideTimer.current);
    hideTimer.current = null;
    setAwake(false);
  }, []);
  useEffect(() => {
    holding.current = hold;
    if (hold) setAwake(true);
    schedule();
  }, [hold, schedule]);
  useEffect(
    () => () => {
      if (hideTimer.current !== null) clearTimeout(hideTimer.current);
    },
    [],
  );

  const toggleMute = useCallback(() => {
    const video = videoRef.current;
    if (video === null) return;
    video.muted = !video.muted;
    setMuted(video.muted);
  }, []);

  const toggleFullscreen = useCallback(() => {
    const doc = document as Document & WebkitDocument;
    try {
      if (fullscreenElement() !== null) {
        if (typeof doc.exitFullscreen === "function") void doc.exitFullscreen().catch(() => undefined);
        else doc.webkitExitFullscreen?.();
        return;
      }
      const container = root.current as (HTMLElement & WebkitElement) | null;
      if (elementFullscreen() && container !== null) {
        // The whole player, not the bare video: its controls stay on screen.
        if (typeof container.requestFullscreen === "function") void container.requestFullscreen().catch(() => undefined);
        else container.webkitRequestFullscreen?.();
        return;
      }
      // An iPhone has only the system's own video player.
      (videoRef.current as (HTMLVideoElement & WebkitVideo) | null)?.webkitEnterFullscreen?.();
    } catch {
      // Refused (no gesture, or the metadata not there yet): the player stays as it is.
    }
  }, []);

  const togglePip = () => {
    const video = videoRef.current;
    if (video === null) return;
    const done = document.pictureInPictureElement ? document.exitPictureInPicture() : video.requestPictureInPicture();
    void done.catch(() => undefined);
  };

  const run = useCallback(
    (action: PlayerKeyAction) => {
      switch (action) {
        case "toggle":
          controller?.togglePlay();
          break;
        case "back10":
          controller?.seekBy(-SEEK_STEP_MS);
          break;
        case "fwd10":
          controller?.seekBy(SEEK_STEP_MS);
          break;
        case "fullscreen":
          toggleFullscreen();
          break;
        case "mute":
          toggleMute();
          break;
        case "next":
          void controller?.next();
          break;
      }
    },
    [controller, toggleFullscreen, toggleMute],
  );

  useEffect(() => {
    const onKey = (event: KeyboardEvent) => {
      wake();
      const action = playerKeyAction(event);
      if (action === null) return;
      event.preventDefault();
      run(action);
    };
    window.addEventListener("keydown", onKey);
    return () => window.removeEventListener("keydown", onKey);
  }, [wake, run]);

  // What this browser can do, and the state of it, from the events rather than from the buttons.
  useEffect(() => {
    const video = videoRef.current as (HTMLVideoElement & WebkitVideo) | null;
    if (video === null) return;
    setCanFullscreen(elementFullscreen() || typeof video.webkitEnterFullscreen === "function");
    setCanPip(document.pictureInPictureEnabled === true && video.disablePictureInPicture !== true);
    const syncFullscreen = () => setFullscreen(fullscreenElement() !== null);
    const begin = () => setFullscreen(true);
    const end = () => setFullscreen(false);
    const enterPip = () => setPip(true);
    const leavePip = () => setPip(false);
    document.addEventListener("fullscreenchange", syncFullscreen);
    document.addEventListener("webkitfullscreenchange", syncFullscreen);
    video.addEventListener("webkitbeginfullscreen", begin);
    video.addEventListener("webkitendfullscreen", end);
    video.addEventListener("enterpictureinpicture", enterPip);
    video.addEventListener("leavepictureinpicture", leavePip);
    return () => {
      document.removeEventListener("fullscreenchange", syncFullscreen);
      document.removeEventListener("webkitfullscreenchange", syncFullscreen);
      video.removeEventListener("webkitbeginfullscreen", begin);
      video.removeEventListener("webkitendfullscreen", end);
      video.removeEventListener("enterpictureinpicture", enterPip);
      video.removeEventListener("leavepictureinpicture", leavePip);
    };
  }, []);

  // The lock screen, headset buttons and Chrome's picture-in-picture window.
  useEffect(() => {
    if (controller === null || anime === null) return;
    showInMediaSession(
      { title: episodeBadge(shownEpisode), artist: anime.title, album: track?.title ?? "", artwork: anime.posterUrl },
      {
        play: () => {
          const now = controller.getState();
          if (now.paused || now.needsGesture) controller.togglePlay();
        },
        pause: () => {
          const now = controller.getState();
          if (!now.paused && !now.needsGesture) controller.togglePlay();
        },
        seekBy: (ms) => controller.seekBy(ms),
        seekTo: (ms) => controller.seekTo(ms),
        next: hasNext ? () => void controller.next() : null,
      },
    );
  }, [controller, anime, track, hasNext, shownEpisode]);
  useEffect(() => () => clearMediaSession(), []);

  useEffect(() => {
    const title = document.title;
    // The page itself is the player: the toast moves above its bottom bar.
    document.body.classList.add("is-player");
    return () => {
      document.body.classList.remove("is-player");
      document.title = title;
    };
  }, []);
  useEffect(() => {
    if (anime !== null && shownEpisode > 0) document.title = `${episodeBadge(shownEpisode)} — ${anime.title}`;
  }, [anime, shownEpisode]);

  // A tab closed or put away is the last chance to write where the episode stopped.
  useEffect(() => {
    if (controller === null) return;
    const flush = () => controller.flush();
    const onVisibility = () => {
      if (document.visibilityState === "hidden") controller.flush();
    };
    window.addEventListener("pagehide", flush);
    document.addEventListener("visibilitychange", onVisibility);
    return () => {
      window.removeEventListener("pagehide", flush);
      document.removeEventListener("visibilitychange", onVisibility);
    };
  }, [controller]);

  // The seek slider moves the episode once it is let go (the native `change`); dragging only previews.
  useEffect(() => {
    const input = seekRef.current;
    if (input === null || controller === null) return;
    const commit = () => {
      setScrub(null);
      controller.seekTo(Number(input.value) * 1_000);
    };
    input.addEventListener("change", commit);
    return () => input.removeEventListener("change", commit);
  }, [controller]);

  // The ending skipped itself after the last aired episode: back to the title, once the viewer has
  // answered the completion question if there is one.
  useEffect(() => {
    if (finished && !completion) void navigate(`/anime/${animeId}`, { replace: true });
  }, [finished, completion, animeId, navigate]);

  // The card takes the focus, so Enter watches on at once (the TV card does the same).
  useEffect(() => {
    if (countdownUp) countdownRef.current?.querySelector<HTMLElement>(".btn--primary")?.focus();
  }, [countdownUp]);

  const back = () => {
    // A deep link has no page of ours behind it.
    if (fromApp) void navigate(-1);
    else void navigate(`/anime/${animeId}`);
  };

  const report = () => {
    const video = videoRef.current;
    if (video !== null) controller?.onTime(video.currentTime * 1_000, video.duration * 1_000);
  };
  const reportPosition = () => {
    const video = videoRef.current;
    if (video !== null) showMediaPosition(video.currentTime * 1_000, video.duration * 1_000, video.playbackRate);
  };

  // A mouse click on the picture plays or pauses; a tap shows or hides the controls (Android's tap).
  // Either one that lands while a menu is open only closes the menu.
  const tap = useRef({ touch: false, wasAwake: true, menu: false });
  const onSurfaceDown = (event: ReactPointerEvent) => {
    tap.current = { touch: event.pointerType === "touch", wasAwake: awake, menu: dubOpen || qualityOpen };
  };
  const onSurfaceClick = () => {
    if (tap.current.menu) return;
    if (!tap.current.touch) {
      controller?.togglePlay();
      wake();
      return;
    }
    if (tap.current.wasAwake && !holding.current) sleep();
    else wake();
  };

  const number = shownEpisode > 0 ? shownEpisode : episode;
  const dubItems: MenuItem[] = state.tracks.map((candidate) => {
    const lacking = lacksEpisode(candidate, number);
    return {
      key: String(candidate.id),
      label: candidate.title,
      checked: candidate.id === track?.id,
      disabled: lacking,
      note: lacking ? `нет серии ${number}` : candidate.type === "subtitles" ? "Субтитры" : undefined,
      onSelect: () => void controller?.changeDub(candidate.id),
    };
  });
  const qualityItems: MenuItem[] = state.qualities.map((quality) => ({
    key: String(quality),
    label: `${quality}p`,
    checked: quality === state.quality,
    onSelect: () => void controller?.changeQuality(quality),
  }));

  const shownMs = scrub ?? state.positionMs;
  const durationMs = state.durationMs;
  const played = durationMs > 0 ? Math.min(100, (shownMs / durationMs) * 100) : 0;
  // «Следующая серия» over the ending says it already; one button of that name at a time.
  const nextButton = hasNext && state.countdown === null && state.skip !== "ending";
  const centreButton = state.phase === "playing" && !state.needsGesture && !spinner;

  return (
    <main
      ref={root}
      className="player"
      data-idle={!awake}
      onPointerMove={(event) => {
        if (event.pointerType !== "touch") wake();
      }}
      onFocus={wake}
    >
      <video
        ref={videoRef}
        className="player-video"
        playsInline
        onTimeUpdate={report}
        onDurationChange={() => {
          report();
          reportPosition();
        }}
        onPlaying={() => {
          controller?.onPlaying();
          reportPosition();
        }}
        onPause={() => {
          controller?.onPause();
          reportPosition();
        }}
        onWaiting={() => controller?.onWaiting()}
        onSeeked={(event) => {
          controller?.onSeeked(event.currentTarget.currentTime * 1_000);
          reportPosition();
        }}
        onCanPlay={() => controller?.onCanPlay()}
        onEnded={() => controller?.onEnded()}
        onRateChange={reportPosition}
        onVolumeChange={(event) => setMuted(event.currentTarget.muted)}
      />
      <div className="player-surface" aria-hidden="true" onPointerDown={onSurfaceDown} onClick={onSurfaceClick} />

      {spinner && (
        <div className="player-spinner" role="status">
          <span className="player-spinner-ring" aria-hidden="true" />
          <span className="visually-hidden">Загружаем</span>
        </div>
      )}
      {state.needsGesture && !failed && (
        <div className="player-gesture">
          <PrimaryButton icon={<IconPlay />} onClick={() => controller?.togglePlay()}>
            Смотреть
          </PrimaryButton>
        </div>
      )}

      <div className="player-chrome">
        <header className="player-top">
          <IconButton label="Назад" icon={<IconBack />} onClick={back} />
          <div className="player-heading">
            {anime !== null && <h1 className="t-title player-title">{anime.title}</h1>}
            {shownEpisode > 0 && <p className="t-label player-episode">{episodeBadge(shownEpisode)}</p>}
          </div>
          <div className="player-top-actions">
            {state.tracks.length > 0 && (
              <MenuButton
                label={track?.title ?? "Озвучка"}
                ariaLabel={track === null ? "Озвучка" : `Озвучка: ${track.title}`}
                items={dubItems}
                open={dubOpen}
                onOpenChange={setDubOpen}
              />
            )}
            {state.quality !== null && qualityItems.length > 0 && (
              <MenuButton
                label={`${state.quality}p`}
                ariaLabel={`Качество: ${state.quality}p`}
                items={qualityItems}
                open={qualityOpen}
                onOpenChange={setQualityOpen}
              />
            )}
            {canPip && (
              <IconButton label="Картинка в картинке" aria-pressed={pip} icon={<IconPictureInPicture />} onClick={togglePip} />
            )}
            {canFullscreen && (
              <IconButton
                label={fullscreen ? "Выйти из полноэкранного режима" : "Во весь экран"}
                icon={fullscreen ? <IconFullscreenExit /> : <IconFullscreen />}
                onClick={toggleFullscreen}
              />
            )}
          </div>
        </header>

        {centreButton && (
          <div className="player-centre">
            <IconButton
              className="player-toggle"
              overArt
              label={state.paused ? "Продолжить" : "Пауза"}
              icon={state.paused ? <IconPlay /> : <IconPause />}
              onClick={() => controller?.togglePlay()}
            />
          </div>
        )}

        <div className="player-bottom" hidden={failed}>
          <div className="player-seek-row">
            <input
              ref={seekRef}
              type="range"
              className="player-seek"
              aria-label="Перемотка"
              aria-valuetext={`${formatTime(shownMs)} из ${formatTime(durationMs)}`}
              min={0}
              max={Math.floor(durationMs / 1_000)}
              step={1}
              value={Math.floor(shownMs / 1_000)}
              disabled={durationMs <= 0}
              style={{ "--played": `${played}%` } as CSSProperties}
              onChange={(event) => {
                // The release arrives as the native change above; React also calls this for it.
                if (event.nativeEvent.type !== "change") setScrub(Number(event.currentTarget.value) * 1_000);
              }}
              onBlur={() => setScrub(null)}
            />
            <span className="player-time t-label tabular">{`${formatTime(shownMs)} / ${formatTime(durationMs)}`}</span>
          </div>
          <div className="player-bottom-row">
            <IconButton label="Назад на 10 секунд" icon={<IconReplay10 />} onClick={() => controller?.seekBy(-SEEK_STEP_MS)} />
            <IconButton label="Вперёд на 10 секунд" icon={<IconForward10 />} onClick={() => controller?.seekBy(SEEK_STEP_MS)} />
            <TextAction onClick={() => controller?.seekBy(JUMP_MS)}>+85 с</TextAction>
            <span className="player-spacer" />
            {nextButton && (
              <TextAction className="player-next" icon={<IconSkipNext />} onClick={() => void controller?.next()}>
                Следующая серия
              </TextAction>
            )}
            <IconButton
              label={muted ? "Включить звук" : "Выключить звук"}
              icon={muted ? <IconVolumeOff /> : <IconVolumeUp />}
              onClick={toggleMute}
            />
          </div>
        </div>
      </div>

      {state.skip !== null && !failed && (
        <div className="player-skip">
          <SecondaryButton onClick={() => controller?.pressSkip()}>
            {state.skip === "opening" ? "Пропустить опенинг" : "Следующая серия"}
          </SecondaryButton>
        </div>
      )}

      {countdownUp && state.countdown !== null && (
        <section ref={countdownRef} className="player-card" aria-label="Следующая серия">
          <p className="t-title">{`Следующая серия через ${state.countdown}`}</p>
          <p className="t-body player-card-text">{`${number + 1} серия`}</p>
          <span className="player-card-drain" aria-hidden="true">
            <span style={{ width: `${(state.countdown / COUNTDOWN_S) * 100}%` }} />
          </span>
          <div className="player-card-actions">
            <PrimaryButton onClick={() => void controller?.next()}>Смотреть сейчас</PrimaryButton>
            <SecondaryButton onClick={() => controller?.cancelCountdown()}>Отмена</SecondaryButton>
          </div>
        </section>
      )}
      {waitingUp && anime !== null && (
        <section className="player-card" aria-label="Следующая серия">
          <p className="t-title">{waitingLabel(anime, number + 1, Date.now())}</p>
          <p className="t-body player-card-text">Пока это последняя вышедшая серия</p>
        </section>
      )}

      {failed && state.failure !== null && (
        <div className="player-failure" role="alert">
          <p className="t-body-lg player-failure-message">{state.failure.message}</p>
          <div className="player-failure-actions">
            <PrimaryButton onClick={() => void controller?.retry()}>Повторить</PrimaryButton>
            {state.failure.action === "list" ? (
              <SecondaryButton to={`/anime/${animeId}`}>К списку серий</SecondaryButton>
            ) : state.tracks.length > 0 ? (
              <SecondaryButton onClick={() => setDubOpen(true)}>Сменить озвучку</SecondaryButton>
            ) : null}
          </div>
        </div>
      )}

      <Dialog
        open={completion && anime !== null}
        title={`Перевести «${anime?.title ?? ""}» в завершённые?`}
        text="Серия была последней из вышедших."
        confirmLabel="Да"
        cancelLabel="Позже"
        onConfirm={() => void controller?.confirmCompletion()}
        onCancel={() => controller?.dismissCompletion()}
      />
    </main>
  );
}
