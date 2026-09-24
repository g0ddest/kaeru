import { errorMessage } from "../api/http";
import type { Shikimori } from "../api/shikimori";
import { availableEpisodes, type Anime } from "../domain/models";
import { DEFAULT_THRESHOLD, progressAt } from "../domain/progress";
import { listKnown, type Library } from "../library/library";
import { autoplayNext, defaultQuality, skipEnding, watchedThreshold } from "../library/prefs";
import type { ProgressStore } from "../library/progress";
import type { AniSkip } from "./aniskip";
import type { Engine } from "./engine";
import { EngineError, failureAction, playerMessage, type EngineFailureKind } from "./errors";
import { KodikError, type Kodik, type KodikStream, type Translation } from "./kodik";
import { dubUsage, rememberDub, rememberedDub } from "./memory";
import {
  NO_MARKS,
  SAVE_EVERY_MS,
  countdown,
  endingDue,
  hasNextEpisode,
  lacksEpisode,
  rankTranslations,
  resumeFrom,
  shouldAutoSkip,
  skipOffer,
  startQuality,
  type SkipMarks,
} from "./rules";

/** The `<video>` as the controller drives it; PlayerScreen implements it. */
export interface MediaPort {
  play(): Promise<void>;
  pause(): void;
  seek(ms: number): void;
}

export interface PlayerDeps {
  kodik: Kodik;
  aniskip: AniSkip;
  library: Library;
  progress: ProgressStore;
  shikimori: Shikimori;
  engine: Engine;
  media: MediaPort;
  toast: (text: string) => void;
  /** Date.now; tests pin it. */
  now?: () => number;
  /** For the dub memory and the playback settings; the browser's own by default. */
  storage?: Storage;
}

export interface PlayerState {
  anime: Anime | null;
  episode: number;
  phase: "loading" | "playing" | "failed";
  failure: { message: string; action: "dub" | "list" } | null;
  /** Ranked; empty until loaded. */
  tracks: Translation[];
  /** The one playing. */
  track: Translation | null;
  qualities: number[];
  quality: number | null;
  positionMs: number;
  durationMs: number;
  paused: boolean;
  buffering: boolean;
  needsGesture: boolean;
  countdown: number | null;
  skip: "opening" | "ending" | null;
  endingDue: boolean;
  hasNext: boolean;
  /** Offer «Перевести в завершённые?». */
  completion: boolean;
  /** The ending skipped itself after the last aired episode: the screen goes back to the title. */
  finished: boolean;
}

const LOAD_FAILED = "Не удалось загрузить аниме. Проверьте соединение и повторите";
const NO_TRACKS = "Источник не предложил ни одной озвучки для этого аниме";
/** How many other dubs the walk asks before it calls the episode missing everywhere. */
const WALK_LIMIT = 5;
/**
 * A reading this close to the start, this far behind the episode with no seek landing there, is an
 * element reloaded under the engine (hls.js recoverMediaError): it reads 0 until hls.js seeks back.
 */
const RELOADED_MS = 1_000;

const INITIAL: PlayerState = {
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
  buffering: false,
  needsGesture: false,
  countdown: null,
  skip: null,
  endingDue: false,
  hasNext: false,
  completion: false,
  finished: false,
};

/** What a resolve came back with: the dub that plays, and the one the viewer has if it is another. */
interface Resolved {
  stream: KodikStream;
  track: Translation;
  /** The dub asked for first; another one playing stands in for it. */
  chosen: Translation;
}

function named(error: unknown, name: string): boolean {
  return typeof error === "object" && error !== null && (error as { name?: unknown }).name === name;
}

function missingInTrack(error: unknown): boolean {
  return error instanceof KodikError && error.kind === "episode";
}

/**
 * One episode on the player's one `<video>` (Android PlaybackController.kt): which dub and quality
 * play, where the episode starts, when the position is written and the episode counted, what comes
 * next. No framework and no timers: the screen forwards the element's events, and every rule runs on
 * the media position they carry.
 */
export class PlayerController {
  private readonly deps: PlayerDeps;
  private readonly now: () => number;
  private readonly listeners = new Set<() => void>();
  private state: PlayerState = INITIAL;

  /** Bumped by everything that resolves: an answer to an older question is dropped. */
  private op = 0;
  /** Bumped by every engine.load: what play() says belongs to the source it was asked for. */
  private source = 0;
  private animeId = 0;
  private stream: KodikStream | null = null;
  /** The dub the viewer has while another one stands in for it this episode. */
  private standingInFor: Translation | null = null;
  /** From one engine.load until the first seeked or playing: currentTime reads 0 then. */
  private swapping = false;
  /** The position last written for this episode; the next write waits for 5 s of change. */
  private lastSavedMs = 0;
  /** Read once per episode, as Android does: a countdown under way keeps its rules. */
  private threshold = DEFAULT_THRESHOLD;
  private autoplay = true;
  private skipEnding = false;
  /** This episode has been sent to Shikimori as watched (or is waiting for the list to be). */
  private marked = false;
  /** That mark once it has gone out, settling when Shikimori has answered it either way. */
  private marking: Promise<void> | null = null;
  /** «Отмена» on this episode's countdown, or a next episode that would not open. */
  private cancelled = false;
  /** The ending already skipped itself once this episode. */
  private autoSkipped = false;
  /** This episode's one silent re-resolve after a failed link has been spent. */
  private reResolved = false;
  /** The op of the silent re-resolve under way; while it is the current op the link playing is dead. */
  private reResolving: number | null = null;
  /** A quality picked while that re-resolve was fetching: the fresh link opens in it. */
  private wantedQuality: number | null = null;
  /** Left from inside its ending: the episode is over, and its row says so. */
  private finishedHere = false;
  private ended = false;
  /** The op of the next episode being resolved; the current one plays on meanwhile. */
  private advancing: number | null = null;
  /** AniSkip's answer for the file playing; another dub or quality is another file. */
  private marks: SkipMarks = NO_MARKS;
  private marksAsked = false;
  /** Bumped per file, so a late AniSkip answer lands on the file it was asked about or nowhere. */
  private file = 0;
  /** The position of the tick before, and where the last seek landed (SkipRules' drag guard). */
  private previousMs = -1;
  private seekedAtMs = Number.NEGATIVE_INFINITY;
  /** This open has asked for the title to join the list. */
  private listAsked = false;
  /** Library writes waiting for the list to be read; they outlive an episode, and the screen. */
  private waiting: (() => void)[] = [];
  private stopListening: (() => void) | null = null;

  constructor(deps: PlayerDeps) {
    this.deps = deps;
    this.now = deps.now ?? Date.now;
  }

  // Arrow properties, so useSyncExternalStore can hold them detached.
  readonly getState = (): PlayerState => this.state;

  readonly subscribe = (listener: () => void): (() => void) => {
    this.listeners.add(listener);
    return () => {
      this.listeners.delete(listener);
    };
  };

  async open(animeId: number, episode: number): Promise<void> {
    this.save();
    // The episode being left stops now, not once the next one has resolved.
    if (this.stream !== null) this.deps.media.pause();
    const op = ++this.op;
    this.source++;
    this.animeId = animeId;
    this.stream = null;
    this.standingInFor = null;
    this.swapping = false;
    this.listAsked = false;
    this.freshEpisode();
    this.set({ ...INITIAL, episode, buffering: true });
    const { library } = this.deps;
    // The signed-in shell starts the list too; whoever comes first starts it once. A read that failed
    // is tried again: marks waiting for the list would otherwise wait for another screen to read it.
    const list = library.state();
    if (!listKnown(list) && list.kind !== "loading") void library.load().catch(() => undefined);

    // Asked side by side: neither answer depends on the other.
    const listing = this.deps.kodik.translations(animeId);
    listing.catch(() => undefined);
    let anime: Anime;
    try {
      anime = await this.deps.shikimori.details(animeId);
    } catch {
      // The list's card is enough to play by: it has the title and the episode counts.
      const known = library.entry(animeId)?.anime;
      if (known === undefined) {
        if (op === this.op) this.fail(LOAD_FAILED, "list");
        return;
      }
      anime = known;
    }
    if (op !== this.op) return;
    const startMs = resumeFrom(progressAt(this.deps.progress.of(animeId), episode), this.threshold);
    this.set({ anime, hasNext: hasNextEpisode(anime, episode), positionMs: startMs });

    let listed: Translation[];
    try {
      listed = await listing;
    } catch (error) {
      if (op === this.op) this.failWith(error, episode);
      return;
    }
    if (op !== this.op) return;
    if (listed.length === 0) {
      this.fail(NO_TRACKS, "list");
      return;
    }
    const tracks = this.rank(listed, null);
    this.set({ tracks });

    let resolved: Resolved;
    try {
      resolved = await this.resolveWalking(episode, tracks);
    } catch (error) {
      if (op === this.op) this.failWith(error, episode);
      return;
    }
    if (op !== this.op) return;
    this.adopt(resolved, episode);
    await this.start(resolved, startMs, null);
  }

  onTime(positionMs: number, durationMs: number): void {
    // Mid-swap the element reads 0 whatever the episode's position: nothing it says counts yet.
    if (this.state.phase !== "playing" || this.swapping) return;
    if (!Number.isFinite(durationMs) || durationMs <= 0 || !Number.isFinite(positionMs)) return;
    const duration = Math.round(durationMs);
    const position = Math.min(duration, Math.max(0, Math.round(positionMs)));
    // Not the episode's position but a reloaded element's 0; the seek back lands through onSeeked. An
    // element that really plays on from the start is followed again from its second second.
    if (position < RELOADED_MS && this.state.positionMs - position >= RELOADED_MS) return;
    if (position < duration) this.ended = false;
    this.tick(position, duration);
  }

  onPlaying(): void {
    if (this.state.phase !== "playing") return;
    this.swapping = false;
    this.set({ paused: false, buffering: false, needsGesture: false });
    this.addToList();
  }

  onPause(): void {
    // A new source pauses the element on its own; that is not the viewer pausing.
    if (this.state.phase !== "playing" || this.swapping) return;
    // A paused episode waits for nothing: play says `waiting` again if the data is still not there.
    this.set({ paused: true, buffering: false });
    this.save();
  }

  onWaiting(): void {
    if (this.state.phase === "playing") this.set({ buffering: true });
  }

  onSeeked(positionMs: number): void {
    if (this.state.phase !== "playing" || !Number.isFinite(positionMs)) return;
    this.swapping = false;
    // A seek lands once its frame is decoded, which is all a paused episode shows. A playing one may
    // still lack the data to go on, and says so with `playing`.
    if (this.state.paused) this.set({ buffering: false });
    this.landed(Math.max(0, Math.round(positionMs)));
  }

  /**
   * `canplay`: enough data to play. The one readiness a paused source that starts at 0 gives, since
   * nothing seeks it; a playing one follows with `playing`.
   */
  onCanPlay(): void {
    if (this.state.phase === "playing") this.set({ buffering: false });
  }

  onEnded(): void {
    const { phase, durationMs } = this.state;
    if (phase !== "playing" || this.swapping || durationMs <= 0) return;
    this.ended = true;
    this.tick(durationMs, durationMs);
    this.save();
    // Nothing is counting down: the episode stays on its last frame, and play starts it over.
    if (this.state.countdown === null) this.set({ paused: true });
  }

  /**
   * Kodik's links expire within hours, and an expired link looks exactly like a broken one: the first
   * refused link of an episode is resolved again at the same position and quality without a word.
   * Only a second one, or no network at all, is a failure worth showing (PlaybackController).
   */
  onEngineFailure(kind: EngineFailureKind): void {
    if (this.state.phase !== "playing") return;
    if (kind === "network" && !this.reResolved) {
      this.reResolved = true;
      void this.reResolve();
      return;
    }
    this.failWith(new EngineError(kind));
  }

  togglePlay(): void {
    const s = this.state;
    if (s.phase !== "playing") return;
    if (!s.needsGesture && !s.paused) {
      this.deps.media.pause();
      // Said here too: mid-swap the element's own pause event is not listened to.
      this.set({ paused: true });
      return;
    }
    // An episode that ran out plays again from the top rather than sitting on its last frame.
    if (this.ended || (s.durationMs > 0 && s.positionMs >= s.durationMs)) this.seekTo(0);
    this.playMedia(this.source);
  }

  seekBy(ms: number): void {
    this.seekTo(this.state.positionMs + ms);
  }

  /** Inside the episode; with no length known yet only the start bounds it (EpisodeQueue.clampSeek). */
  seekTo(ms: number): void {
    const s = this.state;
    if (s.phase !== "playing") return;
    const clamped = Math.max(0, s.durationMs > 0 ? Math.min(ms, s.durationMs) : ms);
    this.deps.media.seek(clamped);
    this.landed(clamped);
  }

  /**
   * The next episode, resolved before anything switches: the current one plays on meanwhile, and a
   * next episode that will not open is a toast, not the failure surface (PlaybackController.openNext).
   */
  async next(): Promise<void> {
    const s = this.state;
    if (this.advancing !== null || s.phase !== "playing" || s.anime === null || !s.hasNext) return;
    const op = this.op;
    const episode = s.episode + 1;
    this.leaveEpisode();
    this.advancing = op;
    // The viewer's own dub first, not the one that stood in for it this episode.
    const tracks = this.rank(s.tracks, (this.standingInFor ?? s.track)?.id ?? null);
    let resolved: Resolved;
    try {
      resolved = await this.resolveWalking(episode, tracks);
    } catch (error) {
      if (op !== this.op) return;
      this.finishedHere = false;
      // Or the countdown would run down to the same failure again on the next tick.
      this.cancelled = true;
      this.set({ countdown: null });
      this.deps.toast(playerMessage(error, episode));
      return;
    } finally {
      // Whatever overtook this resolve, the next «Следующая серия» must still work.
      if (this.advancing === op) this.advancing = null;
    }
    if (op !== this.op) return;
    this.save();
    this.adopt(resolved, episode);
    this.freshEpisode();
    const startMs = resumeFrom(progressAt(this.deps.progress.of(this.animeId), episode), this.threshold);
    this.set({ episode, durationMs: 0, hasNext: hasNextEpisode(s.anime, episode) });
    await this.start(resolved, startMs, null);
  }

  cancelCountdown(): void {
    this.cancelled = true;
    this.set({ countdown: null });
  }

  pressSkip(): void {
    const { skip } = this.state;
    if (skip === "opening" && this.marks.opening !== null) this.seekTo(this.marks.opening.endMs);
    else if (skip === "ending") void this.next();
  }

  /** Another playlist of the same stream at the same position, playing or paused as it was. */
  async changeQuality(q: number): Promise<void> {
    const s = this.state;
    if (s.phase !== "playing" || this.stream === null || s.track === null) return;
    if (!this.stream.links.some((link) => link.quality === q)) return;
    if (this.reResolving === this.op) {
      // The link playing is the one that failed: the fresh one the re-resolve brings opens in q.
      this.wantedQuality = q;
      return;
    }
    if (q === s.quality) return;
    this.save();
    const chosen = this.standingInFor ?? s.track;
    await this.start({ stream: this.stream, track: s.track, chosen }, s.positionMs, q, !s.paused && !s.needsGesture);
  }

  /**
   * A dub the viewer picked, pinned: no stand-in walks in for it, and one that lacks the episode is
   * an honest failure (PlaybackController.changeTranslation). Same position, same quality if offered.
   */
  async changeDub(trackId: number): Promise<void> {
    const s = this.state;
    const track = s.tracks.find((candidate) => candidate.id === trackId);
    if (track === undefined || s.anime === null) return;
    if (s.phase === "playing" && track.id === s.track?.id) {
      // Picking the stand-in that is already playing makes it the viewer's dub; nothing reloads.
      if (this.standingInFor !== null) rememberDub(this.animeId, track, this.deps.storage);
      this.standingInFor = null;
      return;
    }
    this.save();
    const op = ++this.op;
    this.set(s.phase === "failed" ? { phase: "loading", failure: null, buffering: true } : { buffering: true });
    let stream: KodikStream;
    try {
      // Its own count already says no: Kodik would only say it again.
      if (lacksEpisode(track, s.episode)) throw new KodikError("episode");
      stream = await this.resolveTrack(track, s.episode);
    } catch (error) {
      if (op === this.op) this.failWith(error, s.episode);
      return;
    }
    if (op !== this.op) return;
    rememberDub(this.animeId, track, this.deps.storage);
    this.standingInFor = null;
    await this.start({ stream, track, chosen: track }, this.state.positionMs, this.state.quality);
  }

  /**
   * «Повторить»: the episode resolved again, stand-ins allowed, from where it stopped and in the
   * quality it had. An open that never got as far as the dubs runs again from the top.
   */
  async retry(): Promise<void> {
    const s = this.state;
    this.reResolved = false;
    if (s.anime === null || s.tracks.length === 0) {
      await this.open(this.animeId, s.episode);
      return;
    }
    this.save();
    const op = ++this.op;
    this.set({ phase: "loading", failure: null, buffering: true });
    const tracks = this.rank(s.tracks, this.standingInFor?.id ?? null);
    let resolved: Resolved;
    try {
      resolved = await this.resolveWalking(s.episode, tracks);
    } catch (error) {
      if (op === this.op) this.failWith(error, s.episode);
      return;
    }
    if (op !== this.op) return;
    this.adopt(resolved, s.episode);
    await this.start(resolved, s.positionMs, s.quality);
  }

  async confirmCompletion(): Promise<void> {
    const { anime } = this.state;
    this.set({ completion: false });
    if (anime === null) return;
    try {
      await this.deps.library.setStatus(anime, "completed");
    } catch (error) {
      this.deps.toast(errorMessage(error));
    }
  }

  dismissCompletion(): void {
    this.set({ completion: false });
  }

  /** Writes the position now: pagehide, unmount, and before every switch. */
  flush(): void {
    this.save();
  }

  dispose(): void {
    this.flush();
    this.op++;
    this.source++;
    this.deps.engine.destroy();
    // A mark still waiting for the list is kept: it goes out once the list is read.
    if (this.waiting.length === 0) this.unlisten();
  }

  /** The same dub again for a fresh link, behind the viewer's back: memory and stand-in stay as they are. */
  private async reResolve(): Promise<void> {
    const s = this.state;
    if (s.track === null) return;
    const op = ++this.op;
    const track = s.track;
    this.reResolving = op;
    this.wantedQuality = null;
    this.set({ buffering: true });
    let stream: KodikStream;
    try {
      stream = await this.resolveTrack(track, s.episode);
    } catch (error) {
      if (op === this.op) this.failWith(error, s.episode);
      return;
    } finally {
      if (this.reResolving === op) this.reResolving = null;
    }
    if (op !== this.op) return;
    // As things stand now, not as they did before the request: a pause or a quality picked meanwhile stands.
    const { positionMs, quality, paused, needsGesture } = this.state;
    const chosen = this.standingInFor ?? track;
    await this.start({ stream, track, chosen }, positionMs, this.wantedQuality ?? quality, !paused && !needsGesture);
  }

  /** Everything that belongs to one episode rather than to one file of it. */
  private freshEpisode(): void {
    const { storage } = this.deps;
    this.threshold = watchedThreshold(storage);
    this.autoplay = autoplayNext(storage);
    this.skipEnding = skipEnding(storage);
    this.marked = false;
    this.marking = null;
    this.cancelled = false;
    this.autoSkipped = false;
    this.reResolved = false;
    this.finishedHere = false;
    this.advancing = null;
    this.newFile();
  }

  private newFile(): void {
    this.file++;
    this.marks = NO_MARKS;
    this.marksAsked = false;
  }

  /** One position report with a known length: every rule of the episode runs on it. */
  private tick(position: number, duration: number): void {
    if (!this.marksAsked) this.askMarks(duration);
    this.set({ positionMs: position, durationMs: duration, ...this.derived(position, duration) });
    if (Math.abs(position - this.lastSavedMs) >= SAVE_EVERY_MS) this.save();
    if (position / duration >= this.threshold) this.markWatched();
    const due = shouldAutoSkip({
      enabled: this.skipEnding,
      done: this.autoSkipped,
      ending: this.marks.ending,
      positionMs: position,
      previousMs: this.previousMs,
      playedSinceSeekMs: position - this.seekedAtMs,
    });
    this.previousMs = position;
    if (due) this.skipEndingNow();
    else if (this.state.countdown === 0) void this.next();
  }

  /** Countdown, skip button and the no-next card for this position. */
  private derived(position: number, duration: number): Pick<PlayerState, "countdown" | "skip" | "endingDue"> {
    const { hasNext } = this.state;
    const left = countdown({
      positionMs: position,
      durationMs: duration,
      ended: this.ended,
      autoplay: this.autoplay,
      cancelled: this.cancelled,
      hasNext,
    });
    const offer = skipOffer(this.marks, position);
    // «Следующая серия» over the ending only where the countdown card is not already saying it.
    const skip = offer === "ending" && !(hasNext && left === null) ? null : offer;
    return { countdown: left, skip, endingDue: endingDue(position, duration, this.ended) };
  }

  /** Where a seek put the episode; a second has to play from here before the ending may skip itself. */
  private landed(position: number): void {
    this.previousMs = position;
    this.seekedAtMs = position;
    const { durationMs } = this.state;
    if (position < durationMs) this.ended = false;
    this.set({ positionMs: position, ...(durationMs > 0 ? this.derived(position, durationMs) : {}) });
  }

  /** Asked once per file, as soon as its length is known: the length is the question. */
  private askMarks(duration: number): void {
    this.marksAsked = true;
    const file = this.file;
    this.deps.aniskip.marks(this.animeId, this.state.episode, duration).then(
      (marks) => {
        if (file !== this.file) return;
        this.marks = marks;
        const { positionMs, durationMs } = this.state;
        if (durationMs > 0) this.set(this.derived(positionMs, durationMs));
      },
      () => undefined,
    );
  }

  /**
   * Leaving from inside the ending counts the episode, as if played to the end: the 30-s zone, or
   * anywhere in its marked ending (PlaybackController.countWatchedIfLeavingTheEnding).
   */
  private leaveEpisode(): void {
    const { positionMs, durationMs } = this.state;
    const ending = this.marks.ending;
    const inEnding = ending !== null && positionMs >= ending.startMs && positionMs < ending.endMs;
    if (durationMs > 0 && (endingDue(positionMs, durationMs, this.ended) || inEnding)) {
      this.finishedHere = true;
      this.markWatched();
    }
    this.save();
  }

  /**
   * «Пропускать эндинг»: the next episode where there is one; after the last aired one the episode is
   * finished and the screen goes back to the title. With no episode count the ending plays out.
   */
  private skipEndingNow(): void {
    const { anime, hasNext } = this.state;
    if (anime === null) return;
    this.autoSkipped = true;
    if (hasNext) {
      void this.next();
      return;
    }
    if (availableEpisodes(anime) <= 0) return;
    this.finishedHere = true;
    this.markWatched();
    this.save();
    this.deps.media.pause();
    this.set({ countdown: null, skip: null });
    // The screen leaves on `finished`: not before the mark's answer, which may ask to complete the
    // title. A mark still waiting for the list does not hold it; it goes out after the screen has gone.
    const { episode } = this.state;
    void (this.marking ?? Promise.resolve()).then(() => {
      if (this.finishedHere && this.state.episode === episode) this.set({ finished: true });
    });
  }

  /**
   * MarkEpisodeWatched, once per episode. Only against a list that has been read: before that the
   * library cannot know the title has a rate, and would create a second one.
   */
  private markWatched(): void {
    const { anime, episode } = this.state;
    if (this.marked || anime === null) return;
    this.marked = true;
    const { library } = this.deps;
    this.whenListKnown(() => {
      const sent = library.markWatched(anime, episode).then(
        ({ suggestCompleted }) => {
          // Only ever a suggestion; an already completed title has nothing to ask.
          if (!suggestCompleted || library.entry(anime.id)?.rate.status === "completed") return;
          if (this.animeId === anime.id) this.set({ completion: true });
        },
        (error: unknown) => this.deps.toast(errorMessage(error)),
      );
      // A mark that waited for the list may go out after the episode has changed; it is not this one's.
      if (this.animeId === anime.id && this.state.episode === episode) this.marking = sent;
    });
  }

  /**
   * AddStartedTitleToList: a title that starts playing and sits in no list becomes «Смотрю», so it can
   * be found again. A status the viewer chose is theirs. Silent: playback never waits on the list.
   */
  private addToList(): void {
    const { anime } = this.state;
    if (this.listAsked || anime === null) return;
    this.listAsked = true;
    const { library } = this.deps;
    this.whenListKnown(() => {
      if (library.entry(anime.id) === undefined) library.setStatus(anime, "watching").catch(() => undefined);
    });
  }

  private whenListKnown(task: () => void): void {
    const { library } = this.deps;
    if (listKnown(library.state())) {
      task();
      return;
    }
    this.waiting.push(task);
    this.stopListening ??= library.subscribe(() => {
      if (!listKnown(library.state())) return;
      const tasks = this.waiting;
      this.waiting = [];
      this.unlisten();
      for (const run of tasks) run();
    });
  }

  private unlisten(): void {
    this.stopListening?.();
    this.stopListening = null;
  }

  /** Best first, with `voice` (or else the remembered dub) at the head. */
  private rank(listed: readonly Translation[], voice: number | null): Translation[] {
    const { storage } = this.deps;
    return rankTranslations(listed, {
      remembered: voice ?? rememberedDub(this.animeId, storage)?.id ?? null,
      usage: dubUsage(storage),
    });
  }

  private resolveTrack(track: Translation, episode: number): Promise<KodikStream> {
    return this.deps.kodik.resolve(this.animeId, track.id, episode);
  }

  /**
   * The head of `tracks`, or the first of the others that has the episode (ResolveEpisodeStream
   * standIn). Only «not in this dub» walks on: a source that failed is that failure, not a missing
   * episode.
   */
  private async resolveWalking(episode: number, tracks: readonly Translation[]): Promise<Resolved> {
    const [chosen, ...others] = tracks;
    if (chosen === undefined) throw new KodikError("nowhere");
    // A count that already says no is not worth a request.
    if (!lacksEpisode(chosen, episode)) {
      try {
        return { stream: await this.resolveTrack(chosen, episode), track: chosen, chosen };
      } catch (error) {
        if (!missingInTrack(error)) throw error;
      }
    }
    let asked = 0;
    for (const track of others) {
      if (lacksEpisode(track, episode)) continue;
      if (asked === WALK_LIMIT) break;
      asked += 1;
      try {
        return { stream: await this.resolveTrack(track, episode), track, chosen };
      } catch (error) {
        if (!missingInTrack(error)) throw error;
      }
    }
    throw new KodikError("nowhere");
  }

  /**
   * The dub memory after a resolve the viewer did not pin (ResolveEpisodeStream.adopted, remember):
   * what played is remembered, unless it stood in for a dub the viewer has. With nothing remembered
   * the opening guess was nobody's choice, so the stand-in simply becomes the dub, without a word.
   */
  private adopt(resolved: Resolved, episode: number): void {
    const { storage } = this.deps;
    const standIn = resolved.track.id !== resolved.chosen.id;
    const before = this.state;
    const wasStandingIn =
      before.episode === episode && before.track?.id === resolved.track.id && this.standingInFor?.id === resolved.chosen.id;
    if (standIn && rememberedDub(this.animeId, storage) !== null) {
      this.standingInFor = resolved.chosen;
      // Once per stand-in: a retry arriving at the same one again is no news.
      if (!wasStandingIn) {
        this.deps.toast(`В озвучке ${resolved.chosen.title} серии ${episode} нет — включена ${resolved.track.title}`);
      }
      return;
    }
    this.standingInFor = null;
    rememberDub(this.animeId, resolved.track, storage);
  }

  /** Hands the stream to the engine at `startMs`, in `preferred` when this stream offers it. */
  private async start(resolved: Resolved, startMs: number, preferred: number | null, play = true): Promise<void> {
    const { stream, track } = resolved;
    const offered = stream.links.map((link) => link.quality);
    const quality =
      preferred !== null && offered.includes(preferred) ? preferred : startQuality(offered, defaultQuality(this.deps.storage));
    const url = stream.links.find((link) => link.quality === quality)?.url ?? stream.links[0]?.url ?? "";
    // Another dub or quality is another file, with its own length and its own marks.
    if (track.id !== this.state.track?.id || quality !== this.state.quality) this.newFile();
    this.stream = stream;
    this.swapping = true;
    this.ended = false;
    this.lastSavedMs = startMs;
    this.previousMs = -1;
    this.seekedAtMs = Number.NEGATIVE_INFINITY;
    const mine = ++this.source;
    this.set({
      phase: "playing",
      failure: null,
      track,
      qualities: offered,
      quality,
      positionMs: startMs,
      buffering: true,
      paused: !play,
      needsGesture: play ? false : this.state.needsGesture,
      countdown: null,
      skip: null,
      endingDue: false,
      finished: false,
    });
    await this.deps.engine.load(url, startMs);
    // A pause pressed while the source was loading stands.
    if (mine !== this.source || !play || this.state.paused) return;
    this.playMedia(mine);
  }

  /**
   * play() as browsers answer it: refused autoplay is a state the «Смотреть» overlay answers, and an
   * interruption by a newer load is nothing at all.
   */
  private playMedia(mine: number): void {
    this.deps.media.play().then(
      () => {
        if (mine === this.source) this.set({ needsGesture: false, paused: false });
      },
      (error: unknown) => {
        if (mine !== this.source || named(error, "AbortError")) return;
        if (named(error, "NotAllowedError")) {
          this.set({ needsGesture: true, paused: true, buffering: false });
          return;
        }
        this.failWith(new EngineError("media"));
      },
    );
  }

  /**
   * This episode's row in `kaeru.progress`, from the state rather than the element: the state never
   * holds the 0 a swapping element reads. Nothing is written before the length is known.
   */
  private save(): void {
    const s = this.state;
    if (s.anime === null || s.durationMs <= 0) return;
    const positionMs = this.finishedHere ? s.durationMs : s.positionMs;
    this.deps.progress.put({
      animeId: this.animeId,
      episode: s.episode,
      positionMs,
      durationMs: s.durationMs,
      updatedAt: this.now(),
    });
    this.lastSavedMs = positionMs;
  }

  private failWith(error: unknown, episode?: number): void {
    this.fail(playerMessage(error, episode), failureAction(error));
  }

  /** The failure surface. Anime, dubs and the playing dub stay, so the top bar and dub menu work. */
  private fail(message: string, action: "dub" | "list"): void {
    this.swapping = false;
    // Nothing plays on under it: a dub that could not be switched to leaves the old one loaded.
    if (this.stream !== null) this.deps.media.pause();
    this.set({
      phase: "failed",
      failure: { message, action },
      buffering: false,
      paused: true,
      needsGesture: false,
      countdown: null,
      skip: null,
    });
  }

  private set(patch: Partial<PlayerState>): void {
    const current = this.state;
    const keys = Object.keys(patch) as (keyof PlayerState)[];
    if (keys.every((key) => Object.is(current[key], patch[key]))) return;
    this.state = { ...current, ...patch };
    for (const listener of [...this.listeners]) listener();
  }
}
