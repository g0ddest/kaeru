# Kaeru Web — Plan 3: the player

> **For agentic workers:** REQUIRED SUB-SKILL: superpowers:test-driven-development for every task.
> This plan is deliberately compact (the user asked for short plans): each task gives exact files,
> interfaces, values, copy and the tests to write — not the code. Write each listed test first,
> watch it fail for the right reason, then implement. Anything the task leaves open is your call:
> decide it the way the Android app does (paths below) and say so in your report.

**Goal:** `/watch/:id/:episode` plays the episode in the browser: Kodik HLS through the worker, dub and
quality choice, resume, marks on Shikimori, auto-next, opening/ending skip, keyboard, fullscreen, PiP.

**Architecture:** pure rules (`player/rules.ts`) + small adapters (worker client, AniSkip, playback
engine over hls.js / native HLS) + one framework-free `PlayerController` that owns the episode and is
driven by media events; `PlayerScreen` renders its state and forwards `<video>` events. Android is the
behavioural reference (`android/src/main/java/app/kaeru/player/PlaybackController.kt`,
`EpisodeQueue.kt`, `domain/playback/*`).

**Tech stack:** React 19.3, react-router-dom 7.18.4, Vite 8.3, TS 5.9.3 (`strict`,
`erasableSyntaxOnly`: no enums, no constructor parameter properties), Vitest 5.0.1 + jsdom 30.1.1,
**hls.js 1.7.3** (new runtime dependency, exact pin).

**Spec:** `docs/superpowers/specs/2026-09-24-kaeru-web-design.md` (§2, §5, §8). Groundwork maps (not in
git): scratchpad `player-map/1-apps.md`, `2-web-worker.md`, `3-browser.md`.

## Global Constraints

- Runtime deps only `react`, `react-dom`, `react-router-dom`, `hls.js@1.7.3`. No CDN, no third-party
  script: hls.js is bundled, loaded lazily (`import("hls.js/light")`), its transmuxer worker served from
  our own assets (`import workerPath from "hls.js/dist/hls.worker.js?url"`).
- Requests to the worker carry only `Authorization: Bearer <token>` — never `X-Requested-With` or any
  other custom header (the worker's CORS allows `authorization, content-type` only).
- Never log or put in state/UI: tokens, raw worker bodies with `mediaHash`.
- Every `localStorage` access is wrapped in try/catch; a throwing or full storage never breaks playback.
- New storage keys: `kaeru.dubs` (per-title dub), `kaeru.autoplay`, `kaeru.skipEnding`, `kaeru.quality`,
  `kaeru.aniskip`. Existing: `kaeru.progress`, `kaeru.threshold`, `kaeru.session`.
- Timing rules run on **media position** (`timeupdate` values), not wall-clock timers; the only
  wall-clock timers are UI (controls auto-hide) and request timeouts.
- One `<video>` element for the life of the player screen; changing episode or quality never remounts
  it (no `key` on the route element).
- Russian copy is verbatim from this plan (Android `ui/common/ErrorMessages.kt` and player files).
- Each task ends green: `npm test`, `npm run typecheck`, `npm run build` in `web/`. Commit per task,
  message in Russian, `feat(web): …` / `fix(web): …`, ending with the repo's Co-Authored-By line if the
  harness gives one.

## Review Focus

1. **Source swaps write 0.** Between `engine.load()` and the first `seeked`/`playing`, `currentTime`
   reads 0; nothing may be saved or counted toward the threshold then (Task 5 test).
2. **Mark before the list is read.** The threshold mark must wait for `listKnown`; writing earlier can
   create a second rate (Task 5 test).
3. **Expired link mid-episode.** First fatal network failure of an episode → one silent re-resolve at the
   same position and quality; only a second one shows the error (Task 5 test).
4. **Autoplay refused.** `play()` rejecting with `NotAllowedError` shows the «Смотреть» overlay, not an
   error; `AbortError` is ignored (Task 5 and 6 tests).
5. **Shortcuts.** Letters match on `KeyboardEvent.code` (Russian layout), never fire inside inputs,
   menus or dialogs, or with Ctrl/Cmd/Alt (Task 6 test).

---

### Task 1: Worker client and player error copy

**Files:** create `web/src/player/kodik.ts`, `kodik.test.ts`, `web/src/player/errors.ts`, `errors.test.ts`.

**Interfaces (produces):**
```ts
export interface Translation { id: number; title: string; type: "voice" | "subtitles"; episodesCount: number | null }
export interface StreamLink { quality: number; url: string }
export interface KodikStream { links: StreamLink[]; translationId: number } // links sorted by quality desc
export type KodikFailure =
  | "title" | "episode" | "nowhere" | "token" | "upstream" | "parser"
  | "unavailable" | "throttled" | "offline" | "unknown";
export class KodikError extends Error { readonly kind: KodikFailure; constructor(kind: KodikFailure) }
export interface Kodik {
  translations(animeId: number): Promise<Translation[]>;
  resolve(animeId: number, translationId: number, episode: number): Promise<KodikStream>;
}
export function createKodik(deps: {
  authorized: typeof import("../auth/session").authorized;
  onClosed: (nickname: string) => void;   // wired to sessionStore.setClosed in Task 6
  fetch?: typeof fetch;
}): Kodik;
// errors.ts
export type EngineFailureKind = "network" | "offline" | "media" | "unsupported";
export function playerMessage(error: unknown, episode?: number): string;
export function failureAction(error: unknown): "dub" | "list";
```

**Behaviour:**
- `GET ${RELAY_URL}/kodik/translations?anime=<id>` and
  `GET ${RELAY_URL}/kodik/resolve?anime=<id>&translation=<id>&episode=<n>` (no `season`; ids may be
  negative for a sole-track film). Each call runs inside `authorized((token) => …)`.
- Timeouts (AbortController): translations 20 s, resolve 25 s → `KodikError("upstream")`.
- `fetch` rejects → `KodikError("offline")`. HTTP 401 → throw `new ApiError(401, body)` so `authorized`
  refreshes and retries once. 403 with `{error:"not_allowed", nickname}` → `onClosed(nickname)` then
  `KodikError("unavailable")`. 429 (plain text) → `"throttled"`. Other statuses: read JSON `error`:
  `title`, `episode`, `token`, `upstream`, `parser`, `unavailable` map to the same kind; `sign_in` →
  `ApiError(401)`; anything else → `"unknown"`.
- Translations: keep `id`, `title`, `type`, `episodesCount` only (drop `mediaId`/`mediaHash`).
- Resolve: map `urls` → `links`; rewrite `http://…` and `//…` to `https://…`; drop entries with a
  non-positive quality or empty url; dedupe by quality; sort desc. Empty → `KodikError("parser")`.

**Copy (`playerMessage`)**, exact strings:
| error | text |
|---|---|
| `KodikError title` | `Серия ещё не появилась в Kodik` |
| `KodikError episode` | with episode `Серии ${n} ещё нет в этой озвучке`, without `Этой серии ещё нет в выбранной озвучке` |
| `KodikError nowhere` | with `Серия ${n} пока не вышла ни в одной озвучке`, without `Серия пока не вышла ни в одной озвучке` |
| `token` | `Kodik недоступен: не удалось получить ключ` |
| `upstream`, `unavailable`, engine `network` | `Kodik временно недоступен, попробуйте позже` |
| `parser` | `Источник обновился, ждите обновления приложения` |
| `throttled` | `Слишком много запросов, попробуйте позже` |
| `offline`, engine `offline`, `NetworkError` | `Нет соединения. Проверьте интернет` |
| `ApiError` 401 | `Сессия истекла, войдите снова` |
| engine `unsupported` | `Этот браузер не умеет показывать это видео` |
| engine `media`, anything else | `Что-то пошло не так. Повторите попытку` |

Engine failures reach `playerMessage` as `EngineError` instances — define
`export class EngineError extends Error { readonly kind: EngineFailureKind; constructor(kind: EngineFailureKind) }` in `errors.ts`.
`failureAction`: `title` and `nowhere` → `"list"` (button «К списку серий»); everything else → `"dub"`
(«Сменить озвучку»).

**Tests (kodik.test.ts, fake fetch recording requests):** sends only the Authorization header (assert the
exact header set); builds both URLs (negative translation id kept); translations strip media fields;
resolve upgrades `http:` and `//` links to `https:` and sorts/dedupes; 401 goes through `authorized`
retry (use a fake `authorized` that retries once on `ApiError(401)` and assert 2 fetches); 403
`not_allowed` calls `onClosed("nick")`; 404 `episode` → kind `episode`; 404 `title`; 502 `token`/
`parser`; 429 → `throttled`; fetch rejection → `offline`; timeout → `upstream` (fake timers).
**errors.test.ts:** one row per table line, plus `failureAction` for `title`, `nowhere`, `episode`.

**Commit:** `feat(web): клиент Kodik через воркер и тексты ошибок плеера`

---

### Task 2: Playback rules, dub memory and player settings

**Files:** create `web/src/player/rules.ts`, `rules.test.ts`, `web/src/player/memory.ts`,
`memory.test.ts`; modify `web/src/library/prefs.ts`, `prefs.test.ts`.

**Interfaces (produces):**
```ts
// rules.ts — all times in ms
export const DEFAULT_STUDIOS: readonly string[]; // "AniLibria","AniDUB","Crunchyroll","Amazing Dubbing","AniBaza","AniMaunt","JAM","Dream Cast","SHIZA Project"
export const SEEK_STEP_MS = 10_000, JUMP_MS = 85_000, SAVE_EVERY_MS = 5_000, ENDING_ZONE_MS = 30_000,
  COUNTDOWN_S = 10, SKIP_OFFER_MS = 10_000, OPENING_WITHIN_MS = 300_000, ENDING_WITHIN_MS = 180_000,
  INTERVAL_MIN_MS = 60_000, INTERVAL_MAX_MS = 150_000, SEEK_SETTLE_MS = 1_000, CONTROLS_HIDE_MS = 3_000;
export function rankTranslations(tracks: readonly Translation[], ctx: { remembered: number | null; usage: ReadonlyMap<number, number> }): Translation[];
export function lacksEpisode(track: Translation, episode: number): boolean;
export function resumeFrom(row: EpisodeProgress | undefined, threshold: number): number;
export function startQuality(offered: readonly number[], preferred: number | null): number;
export function hasNextEpisode(anime: Anime, episode: number): boolean;
export function endingDue(positionMs: number, durationMs: number, ended: boolean): boolean;
export function countdown(s: { positionMs: number; durationMs: number; ended: boolean; autoplay: boolean; cancelled: boolean; hasNext: boolean }): number | null;
export interface Interval { startMs: number; endMs: number }
export interface SkipMarks { opening: Interval | null; ending: Interval | null }
export const NO_MARKS: SkipMarks;
export function plausibleMarks(raw: readonly { kind: "op" | "ed"; startMs: number; endMs: number }[], durationMs: number): SkipMarks;
export function skipOffer(marks: SkipMarks, positionMs: number): "opening" | "ending" | null;
export function shouldAutoSkip(s: { enabled: boolean; done: boolean; ending: Interval | null; positionMs: number; previousMs: number; playedSinceSeekMs: number }): boolean;
// memory.ts — key "kaeru.dubs": { [animeId]: { id, title } }
export function rememberedDub(animeId: number, storage?: Storage): { id: number; title: string } | null;
export function rememberDub(animeId: number, track: { id: number; title: string }, storage?: Storage): void;
export function dubUsage(storage?: Storage): Map<number, number>; // track id → how many titles remember it
// prefs.ts additions
export function autoplayNext(storage?: Storage): boolean;          // "kaeru.autoplay", default true
export function setAutoplayNext(on: boolean, storage?: Storage): void;
export function skipEnding(storage?: Storage): boolean;            // "kaeru.skipEnding", default false
export function setSkipEnding(on: boolean, storage?: Storage): void;
export const QUALITY_CHOICES: readonly (number | null)[];          // [null, 360, 480, 720, 1080]; null = «Авто»
export function defaultQuality(storage?: Storage): number | null;  // "kaeru.quality", default null
export function setDefaultQuality(q: number | null, storage?: Storage): void;
```

**Rules (Android `domain/playback/TranslationRanker.kt`, `EpisodeQueue.kt`, `SkipMarks.kt`,
`ui/common/player/PlayerViewModel.kt:368-392`):**
- `rankTranslations`: stable sort by, strongest first: (1) `id === remembered`; (2) usage count desc;
  (3) index of the first DEFAULT_STUDIOS entry that is a case-insensitive substring of the title (no
  match ranks after all matches); (4) voice before subtitles; (5) for voices, larger `episodesCount`
  first (null = 0); (6) source order.
- `lacksEpisode`: `episodesCount !== null && episodesCount > 0 && episodesCount < episode`.
- `resumeFrom`: no row → 0; not started (`positionMs < 60_000 && positionMs / durationMs < 0.02`, reuse
  `domain/progress.ts` `isStarted`) → 0; finished (`isFinished(row, threshold)`) → 0; else `positionMs`.
- `startQuality`: `preferred` if offered, else the highest offered.
- `hasNextEpisode`: `availableEpisodes(anime) > 0 && episode < availableEpisodes(anime)`.
- `endingDue`: `ended || (durationMs > 0 && durationMs - positionMs <= ENDING_ZONE_MS)`.
- `countdown`: `null` unless `autoplay && !cancelled && hasNext && durationMs > 0`; `ended` → 0;
  remaining `> 10_000` → null; else `clamp(ceil(remaining / 1000), 0, 10)`.
- `plausibleMarks`: first plausible interval of each kind; plausible = `0 ≤ start`, `end ≤ duration`,
  `60 s ≤ end − start ≤ 150 s`; opening also `start ≤ 300 s`; ending also `end ≥ duration − 180 s`.
- `skipOffer`: opening first; `start ≤ pos < start + 10 s`.
- `shouldAutoSkip`: `enabled && !done && ending && ending.startMs + 10_000 ≤ pos < ending.endMs &&
  ending.startMs ≤ previousMs < ending.endMs && playedSinceSeekMs ≥ 1_000`.
- Storage readers: bad JSON / wrong types → defaults; `setDefaultQuality(null)` removes the key.

**Tests:** ranking — remembered beats a default studio; usage beats a default studio; «AniLibria.TV»
matches AniLibria case-insensitively; earlier default studio wins; voice before subtitles; more
episodes first; stable for ties. `lacksEpisode` for null/0/short/enough. `resumeFrom` for the four
cases (59 s of 24 min → 0; 90 % → 0; 14:20 → 14:20). `startQuality` preferred offered / not offered.
`hasNextEpisode` ongoing (aired 8, ep 7 → true, ep 8 → false), anons → false, released uses episodes.
`countdown` table: 11 s left → null, 10 s → 10, 9.2 s → 10, 0.4 s → 1, ended → 0, autoplay off →
null, cancelled → null, no next → null. `plausibleMarks` drops too short/long/late intervals and keeps
the first plausible. `skipOffer` inside/outside the 10 s window. `shouldAutoSkip` fires only after 10 s
into the ending with the previous tick already inside and ≥ 1 s since a seek. memory round-trip, usage
counts, corrupt JSON → null. prefs defaults, round-trips, corrupt values.

**Commit:** `feat(web): правила плеера, память озвучки и настройки воспроизведения`

---

### Task 3: AniSkip marks

**Files:** create `web/src/player/aniskip.ts`, `aniskip.test.ts`.

**Interface:**
```ts
export interface AniSkip { marks(animeId: number, episode: number, durationMs: number): Promise<SkipMarks> }
export function createAniSkip(deps?: { fetch?: typeof fetch; storage?: Storage; now?: () => number }): AniSkip;
```

**Behaviour (Android `data/skip/AniSkipApi.kt`, `AniSkipMarks.kt`):**
- `GET https://api.aniskip.com/v2/skip-times/${animeId}/${episode}?types[]=op&types[]=ed&types[]=mixed-op&types[]=mixed-ed&episodeLength=${Math.round(durationMs / 1000)}`
  (literal `[]`, no headers, the Shikimori id is used as the MAL id). Timeout 5 s.
- Body `{ found, results: [{ interval: { startTime, endTime }, skipType }] }` (seconds). `op`/`mixed-op`
  → `op`, `ed`/`mixed-ed` → `ed`; pass to `plausibleMarks(raw, durationMs)`.
- 404 or `found: false` → `NO_MARKS`, cached. Other failures (network, 5xx, bad JSON, timeout) →
  cached answer if any, else `NO_MARKS`, nothing written. Never throws.
- Cache in `kaeru.aniskip`: `{ "<anime>:<episode>": { lengthS, at, marks } }`; a hit needs
  `|lengthS − round(durationMs/1000)| ≤ 2` and `now − at < 7 days`; keep the 200 newest entries.

**Tests:** URL exactly as above; results mapped and filtered through `plausibleMarks`; 404 cached (second
call makes no request); length within ±2 s hits the cache, 3 s off refetches; 8-day-old entry refetches;
500 with a cached entry returns it, without one returns NO_MARKS; timeout (fake timers) → NO_MARKS;
storage that throws still works.

**Commit:** `feat(web): метки опенинга и эндинга из AniSkip`

---

### Task 4: Playback engine (hls.js / native HLS)

**Files:** modify `web/package.json` (+`"hls.js": "1.7.3"` in `dependencies`, run `npm install` so
`package-lock.json` updates); create `web/src/player/hls-light.d.ts`, `web/src/player/engine.ts`,
`engine.test.ts`.

**Interfaces:**
```ts
// hls-light.d.ts
declare module "hls.js/light" { export * from "hls.js"; export { default } from "hls.js"; }
// engine.ts
export interface Engine {
  /** Replaces the current source; playback position becomes startMs once loaded. */
  load(url: string, startMs: number): Promise<void>;
  destroy(): void;
}
export type EngineKind = "native" | "hls" | "none";
export function chooseEngine(env: { canPlayHls: boolean; hasManagedMediaSource: boolean; mseSupported: boolean }): EngineKind;
export function classifyHlsError(d: { type: string; details: string; fatal: boolean; response?: { code: number } }, online: boolean): "ignore" | "recover" | EngineFailureKind;
export type EngineFactory = (video: HTMLVideoElement, onFailure: (kind: EngineFailureKind) => void) => Engine;
export const createEngine: EngineFactory;
```

**Behaviour (map 3 §2–3):**
- `chooseEngine`: `canPlayHls && (hasManagedMediaSource || !mseSupported)` → native; else
  `mseSupported` → hls; else native if `canPlayHls`, otherwise `"none"`. Detection: `canPlayHls =
  video.canPlayType("application/vnd.apple.mpegurl") !== ""`, `hasManagedMediaSource = "ManagedMediaSource" in window`,
  `mseSupported = !!(window.ManagedMediaSource ?? window.MediaSource)?.isTypeSupported('video/mp4; codecs="avc1.42E01E,mp4a.40.2"')`.
- hls path: every `load` destroys the previous instance and creates `new Hls({ workerPath, startPosition:
  startMs / 1000, backBufferLength: 90, maxBufferLength: 30 })`, `attachMedia(video)`, `loadSource(url)`.
  hls.js is imported lazily once. `Hls.Events.ERROR` → `classifyHlsError`: non-fatal → ignore; fatal
  `mediaError` → `recoverMediaError()` unless one ran < 5 s ago, then `"media"`; fatal `networkError`
  → `"offline"` if `!online`, else `"network"`; other fatal → `"media"`.
- native path: `video.src = url`; on the next `loadedmetadata` set `currentTime = startMs / 1000`;
  `error` event → `navigator.onLine ? "network" : "offline"`. `destroy()` → `removeAttribute("src")`,
  `load()`.
- `"none"` → `load()` calls `onFailure("unsupported")`.
- No `crossorigin` attribute, no `xhrSetup`.

**Tests (`vi.mock("hls.js/light")` with a hoisted fake Hls class, map 3 §9.2; restore prototype spies in
`afterEach`):** `chooseEngine` table (Chrome: maybe+MSE → hls; Safari: maybe+MMS → native; old iPhone:
maybe, no MSE → native; Firefox: "" + MSE → hls; nothing → none); hls load passes `startPosition` in
seconds and `workerPath`; a second load destroys the first instance; fatal mediaError → recover, a second
within 5 s → onFailure("media"); fatal network 403 online → "network", offline → "offline"; non-fatal →
nothing; native load sets `src` and applies `currentTime` on `loadedmetadata`; native error → "network";
`destroy` on both paths.

**Commit:** `feat(web): движок воспроизведения — hls.js и встроенный HLS`

---

### Task 5: PlayerController

**Files:** create `web/src/player/controller.ts`, `controller.test.ts`.

**Interface:**
```ts
export interface MediaPort {          // implemented by PlayerScreen over <video>
  play(): Promise<void>;
  pause(): void;
  seek(ms: number): void;
}
export interface PlayerDeps {
  kodik: Kodik; aniskip: AniSkip; library: Library; progress: ProgressStore; shikimori: Shikimori;
  engine: Engine; media: MediaPort;
  toast: (text: string) => void;
  now?: () => number;              // Date.now
  storage?: Storage;               // for memory.ts / prefs.ts
}
export interface PlayerState {
  anime: Anime | null; episode: number;
  phase: "loading" | "playing" | "failed";
  failure: { message: string; action: "dub" | "list" } | null;
  tracks: Translation[];           // ranked; empty until loaded
  track: Translation | null;       // the one playing
  qualities: number[]; quality: number | null;
  positionMs: number; durationMs: number;
  paused: boolean; buffering: boolean; needsGesture: boolean;
  countdown: number | null; skip: "opening" | "ending" | null;
  endingDue: boolean; hasNext: boolean;
  completion: boolean;             // offer «Перевести в завершённые?»
  finished: boolean;               // auto-skip after the last aired episode: screen goes to the title
}
export class PlayerController {
  constructor(deps: PlayerDeps);
  getState(): PlayerState; subscribe(listener: () => void): () => void;
  open(animeId: number, episode: number): Promise<void>;
  // media events, forwarded by the screen
  onTime(positionMs: number, durationMs: number): void;
  onPlaying(): void; onPause(): void; onWaiting(): void; onSeeked(positionMs: number): void; onEnded(): void;
  onEngineFailure(kind: EngineFailureKind): void;
  // viewer actions
  togglePlay(): void; seekBy(ms: number): void; seekTo(ms: number): void;
  next(): Promise<void>; cancelCountdown(): void; pressSkip(): void;
  changeQuality(q: number): Promise<void>; changeDub(trackId: number): Promise<void>;
  retry(): Promise<void>; confirmCompletion(): Promise<void>; dismissCompletion(): void;
  flush(): void;                   // save the position now (pagehide, unmount)
  dispose(): void;                 // flush + engine.destroy()
}
```

**Behaviour (Android `PlaybackController.kt`, `ResolveEpisodeStream.kt`, `MarkEpisodeWatched.kt`,
`AddStartedTitleToList.kt`):**
- **open(anime, episode):** flush the previous episode; reset per-episode state (marked, cancelled,
  reResolved, autoSkipDone, marks); `phase: loading`. Anime = `shikimori.details(id)`, falling back to
  `library.entry(id)?.anime`; neither → failed with `Не удалось загрузить аниме. Проверьте соединение и повторите`
  (action `list`). Tracks = `rankTranslations(await kodik.translations(id), { remembered:
  rememberedDub(id)?.id ?? null, usage: dubUsage() })`; empty → failed `Источник не предложил ни одной озвучки для этого аниме`.
- **Choosing the dub (substitution walk):** chosen = tracks[0]. If `!lacksEpisode(chosen)` resolve it; a
  `KodikError("episode")` starts the walk, any other error fails the episode. Walk = the remaining ranked
  tracks without those that `lacksEpisode`, at most 5 resolves; first success plays as a stand-in. None →
  `KodikError("nowhere")`. Stand-in → toast `В озвучке ${chosen.title} серии ${episode} нет — включена ${playing.title}`.
  Memory: success without stand-in → `rememberDub(chosen)`; stand-in and nothing remembered → remember the
  stand-in; stand-in with a memory → keep the memory.
- **Start:** quality = `startQuality(links, defaultQuality())`; start = `resumeFrom(progress.of(id) row
  for the episode, watchedThreshold())`; set `swapping`; `await engine.load(url, start)`; `media.play()`:
  `NotAllowedError` → `needsGesture: true`; `AbortError` → ignore; other → media failure.
  Once playing and `listKnown(library.state())` with no entry → `library.setStatus(anime, "watching")`
  (at most once per open; if the list is not known yet, do it when it becomes known).
- **Swap gate:** from every `engine.load` until the next `onSeeked` or `onPlaying`, `onTime` updates
  nothing but ignores the values (no save, no mark, no countdown).
- **Progress:** on `onTime` with `durationMs > 0`: save `progress.put({ animeId, episode, positionMs,
  durationMs, updatedAt: now() })` when `|pos − lastSaved| ≥ 5 000`; also on `onPause`, `onEnded`,
  `flush()`, before any switch (next, quality, dub, retry).
- **Mark:** first time `pos / dur ≥ watchedThreshold()` in an episode: if `listKnown` →
  `library.markWatched(anime, episode)`; else wait for the list, then mark. Success with
  `suggestCompleted` and entry status ≠ `completed` → `completion: true`. Failure → toast
  `errorMessage(error)` (`api/http.ts`). `next()`/`pressSkip()` while `endingDue` or skip offer is
  `ending` also marks (same once-guard) and saves `positionMs = durationMs`.
- **Countdown / next:** `countdown(...)` recomputed on each `onTime`; reaching 0 → `next()`.
  `cancelCountdown()` → cancelled for this episode. `next()`: resolve episode + 1 **before** switching
  (current keeps playing); success → state.episode = n + 1 and load it at `resumeFrom` (quality =
  `startQuality(links, defaultQuality())`); failure → toast `playerMessage(err, n + 1)`, countdown
  cancelled, stay. `onEnded` with no countdown → paused at the end (pressing play replays from 0).
- **Skip:** when `durationMs` first becomes known for a loaded file, `aniskip.marks(id, episode, dur)`;
  `skip` = `skipOffer(marks, pos)`, but `"ending"` only if `hasNext && countdown === null`.
  `pressSkip()`: opening → `media.seek(opening.endMs)`; ending → `next()`. Auto-skip when
  `skipEnding()` and `shouldAutoSkip`: next if `hasNext`; else if `availableEpisodes > 0` mark, pause,
  `finished: true`; else nothing.
- **Quality / dub:** `changeQuality(q)` → flush, reload the same stream's link for `q` at the current
  position (swap gate), keep playing/paused state. `changeDub(id)` → flush, resolve that track pinned (no
  walk); `episode` error → failed `playerMessage(err, episode)`; success → `rememberDub`, load at current
  position, quality kept if offered else `startQuality`.
- **Failures and retry:** `onEngineFailure("network")`: first time this episode → silently resolve the same
  track again and reload at the last good position and quality (`reResolved = true`); second time → failed
  `Kodik временно недоступен, попробуйте позже`. `"offline"`, `"media"`, `"unsupported"` → failed with
  `playerMessage(new EngineError(kind))`. `retry()` → reset `reResolved`, re-run open's resolve (walk
  allowed) from the current position with the current quality. Failed state keeps `anime`, `tracks` and
  `track` so the top bar and dub menu work.
- **togglePlay:** if `needsGesture` or paused → `media.play()` (clear `needsGesture` on success; at the
  very end seek 0 first); else pause. `seekBy` clamps to `[0, duration]`.
- **confirmCompletion:** `library.setStatus(anime, "completed")`, failure → toast; both clear `completion`.

**Tests (fake Kodik/AniSkip/Engine/MediaPort, real `Library` with a fake Shikimori like
`TitleScreen.test.tsx`, `ProgressStore(memoryStorage())`):** opens at the resume position with the
remembered dub; ranks dubs (no memory → AniLibria before an unknown studio); remembered dub lacking the
episode by `episodesCount` plays the next dub, toasts the exact text, keeps the memory; `episode` error
from resolve also walks; nothing has it → failed `Серия 7 пока не вышла ни в одной озвучке` with
action `list`; other resolve error → failed without walking; `NotAllowedError` → `needsGesture`, not
failed; `AbortError` ignored; **swap gate: after `changeQuality` an `onTime(0, dur)` before `onSeeked`
saves nothing and does not mark (Review Focus 1)**; saves every 5 s of position and on pause; marks once at
90 % and calls `markWatched` once; **with the list still loading the mark waits and then happens once
(Review Focus 2)**; `suggestCompleted` → `completion`; countdown 10…1 then `next()` loads episode 8 at
0 (or its resume point); cancel stops it; `next()` failure toasts and stays on 7; `next()` inside the
ending zone marks the current episode; opening offer + `pressSkip` seeks to its end; auto-skip ending on
the last aired episode sets `finished`; **first `network` failure re-resolves silently at the same
position/quality, the second shows the error (Review Focus 3)**; `changeDub` to a track without the episode
→ failed with the dub copy; starting a title not in the list sets `watching` once the list is known;
`flush` on dispose.

**Commit:** `feat(web): контроллер плеера — озвучка, продолжение, отметки, автопереход`

---

### Task 6: Player screen

**Files:** create `web/src/screens/PlayerScreen.tsx`, `PlayerScreen.css`, `PlayerScreen.test.tsx`,
`web/src/player/keys.ts`, `keys.test.ts`, `web/src/player/mediaSession.ts`, `web/src/test/fakes.ts`;
modify `web/src/app/services.tsx` (add `kodik: Kodik`, `aniskip: AniSkip`, `engine: EngineFactory`;
`createServices` builds them — `onClosed` → the chosen store's `setClosed`), `web/src/app/App.tsx`
(route element → `PlayerScreen`, keep it outside `Layout`, inside the Gate), `web/src/app/App.test.tsx`,
the five tests that build `Services` literals (`TitleScreen`, `HomeScreen`, `LibraryScreen`,
`SearchScreen`, `SettingsScreen` — use helpers from `test/fakes.ts` that reject if called),
`web/src/ui/icons.tsx` (Material paths: pause, replay_10, forward_10, volume_up, volume_off, fullscreen,
fullscreen_exit, picture_in_picture_alt, skip_next), `web/src/ui/Menu.tsx` (+ optional `disabled?:
boolean` and `note?: string` on `MenuItem`: disabled items are skipped by arrows and not selectable, the
note is a second line), `web/src/ui/components.css` (toast region centred and above the bottom bar while
`body.is-player`); delete `WatchPlaceholderScreen.tsx` and its test.

**Keys (`keys.ts`):** `playerKeyAction(e: KeyboardEvent): "toggle" | "back10" | "fwd10" | "fullscreen" |
"mute" | "next" | null` using `e.code`: `Space`, `ArrowLeft`, `ArrowRight`, `KeyF`, `KeyM`, `KeyN`; null
when `defaultPrevented`, `isComposing`, Ctrl/Meta/Alt, the target is editable (`input, textarea, select,
[contenteditable]`) or inside `[role=menu]` or `[role=dialog]`; `Space` on a focused
`button, a, [role=button]` → null; repeats allowed only for arrows. The screen listens on `window`,
calls `preventDefault()` only when an action is returned, and shows the controls on any handled key.

**Screen layout (full window, `.player`, dark):**
- `<video playsInline>` without `controls`; forwards `timeupdate`, `durationchange`, `playing`, `pause`,
  `waiting`, `seeked`, `ended` to the controller; `MediaPort` over it (`play()` returns the promise, or
  `Promise.resolve()` when the browser returns undefined).
- Top bar: IconButton «Назад» (history back when the router has a previous entry, else
  `/anime/:id`), title and `episodeBadge(n)`, dub `MenuButton` (label = playing track title; items =
  ranked tracks, `checked` = playing, `disabled` + note `нет серии ${n}` when `lacksEpisode`, note
  `Субтитры` for subtitles), quality `MenuButton` (`${q}p`, checked), PiP IconButton «Картинка в картинке»
  (only if `document.pictureInPictureEnabled`), fullscreen IconButton «Во весь экран» / «Выйти из
  полноэкранного режима».
- Centre: spinner (`role=status`, «Загружаем») while loading or buffering; big play button «Смотреть»
  when `needsGesture`, else «Продолжить»/«Пауза» on click of the video area.
- Bottom bar: `formatTime(pos) / formatTime(dur)`; `<input type="range">` «Перемотка» (seconds, disabled
  until duration); IconButtons «Назад на 10 секунд», «Вперёд на 10 секунд»; TextAction `+85 с`; «Следующая
  серия» when `hasNext && countdown === null`; mute IconButton «Выключить звук» / «Включить звук».
- Countdown card: `Следующая серия через ${n}`, `${episode + 1} серия`, PrimaryButton «Смотреть сейчас»
  (focused when it appears) and SecondaryButton «Отмена».
- No-next card when `endingDue && !hasNext && availableEpisodes(anime) > 0 && anime.status !== "released"`:
  `waitingLabel(anime, episode + 1, Date.now())` and `Пока это последняя вышедшая серия`.
- Skip button bottom-right: «Пропустить опенинг» / «Следующая серия».
- Failure surface (`role=alert`): message, PrimaryButton «Повторить», and SecondaryButton «Сменить
  озвучку» (opens the dub menu) or «К списку серий» (`/anime/:id`); top bar stays usable, bottom bar hidden.
- Completion `Dialog`: title `Перевести «${anime.title}» в завершённые?`, text `Серия была последней из вышедших.`,
  confirm «Да», cancel «Позже». `finished` → after the dialog (or at once) `navigate(/anime/:id, { replace: true })`.
- Controls auto-hide after `CONTROLS_HIDE_MS` of playback with no pointer move/touch/key; never while
  paused, loading, a menu, card, failure or dialog is up; the cursor hides with them.
- Episode changes from the controller update the URL with `navigate(/watch/:id/:n, { replace: true })`;
  a route param change the controller did not cause calls `controller.open`. The same controller and
  `<video>` live for the whole screen.
- Fullscreen: `container.requestFullscreen()` (fallback `webkitRequestFullscreen`); state from
  `fullscreenchange`. When `document.fullscreenEnabled` is false and `video.webkitEnterFullscreen` is a
  function (iPhone), use that. PiP: `video.requestPictureInPicture()` / `document.exitPictureInPicture()`.
- `mediaSession.ts`: metadata `{ title: episodeBadge(n), artist: anime.title, album: track title,
  artwork: poster }`; handlers play, pause, seekbackward/seekforward (10 s), seekto, nexttrack (null
  without a next); `setPositionState` only with a finite duration; everything cleared on unmount.
  Guard every call (`"mediaSession" in navigator`, try/catch per handler).
- `document.title = ${episodeBadge(n)} — ${anime.title}`, restored on unmount. `body.is-player` while
  mounted. `pagehide` and `visibilitychange` (hidden) → `controller.flush()`. Unmount → `dispose()`.

**Tests:** `keys.test.ts` — every mapping with Russian-layout `key` values (`а`, `ь`, `т`) and codes;
ignored in an input, a menu, a dialog, with Ctrl/Meta/Alt, while composing; Space on a focused button →
null; repeat only for arrows (Review Focus 5). `PlayerScreen.test.tsx` (fake engine factory recording
loads, fake Kodik, `HTMLMediaElement.prototype.play/pause/load` spied and restored) — renders the episode
title and dub/quality menus after load; clicking «Пауза» pauses; `NotAllowedError` shows «Смотреть» and a
click plays (Review Focus 4); quality menu reloads at the current position; countdown card appears at 10 s
left and «Отмена» hides it; failure shows the copy with «Повторить» and «К списку серий» for `nowhere`;
the completion dialog confirms `completed`; keyboard `ArrowRight` seeks +10 s and `KeyN` goes next;
next episode replaces the URL without remounting `<video>` (same element instance); unmount saves the
position. `App.test.tsx`: `/watch/7/3` renders the player (not the placeholder).

**Commit:** `feat(web): экран плеера`

---

### Task 7: «Озвучка» on the title page and playback settings

**Files:** modify `web/src/screens/TitleScreen.tsx`, `TitleScreen.test.tsx`,
`web/src/screens/SettingsScreen.tsx`, `SettingsScreen.test.tsx`, `web/src/screens/HomeScreen.tsx`;
create `web/src/ui/Switch.tsx` (+ test) if no switch exists; move `watchPath` to
`web/src/domain/actions.ts` and use it in `HomeScreen`.

**Title page:** in `.title-controls`, after the status control, a dub `MenuButton` when
`availableEpisodes(anime) > 0`: translations load once on mount through `kodik.translations` (errors →
the control is not shown); label `Озвучка: ${title}` for the remembered (or top-ranked) track, `Озвучка`
while loading (disabled); items = ranked tracks, checked = remembered, note `Субтитры` for subtitles;
choosing one calls `rememberDub` (the player then starts with it). Empty list → disabled pill `Нет
озвучек`.

**Settings, section «Воспроизведение» (after «Порог просмотра»):**
- Switch «Следующая серия автоматически» (`autoplayNext`, default on).
- Switch «Пропускать эндинг» with note `Через 10 секунд начнётся следующая серия. После последней плеер закроется и вернёт на карточку`.
- «Качество по умолчанию»: PillGroup radio `Авто`, `360p`, `480p`, `720p`, `1080p` → `setDefaultQuality`.
- `Switch`: `<button role="switch" aria-checked>` with its label, keyboard Space/Enter, styled like the
  app (accent when on).

**Tests:** title page shows `Озвучка: AniLibria` (ranked) and choosing another dub stores it
(`rememberedDub`); no control for an anons title; translation failure hides it. Settings switches and
quality pills write their prefs and reflect stored values. Home hero still links to `/watch/…`.

**Commit:** `feat(web): выбор озвучки в карточке и настройки воспроизведения`

---

## After the tasks (controller, not a task)

1. Whole-branch review, fix Critical/Important with a failing test first.
2. Browser smoke without real Kodik: `vite preview` + a local Kodik-shaped HLS stub (302, `./…:hls:seg-N`
   names, ACAO `*`), `/kodik/*` answered by CDP request interception, headless Chrome over CDP — play from a
   resume point, switch quality, next episode, keyboard; WebKit via Playwright is optional.
3. Merge to master, publish with `web/scripts/publish-site.sh`, live check that `/watch/…` loads the player.
   Real Kodik playback needs the owner's signed-in browser: ask the user to try one episode.
</content>
</invoke>
