import { ApiError, NetworkError } from "../api/http";
import { KodikError } from "./kodik";

/** How the video element or hls.js gave up on a stream it had a link for. */
export type EngineFailureKind = "network" | "offline" | "media" | "unsupported";

export class EngineError extends Error {
  readonly kind: EngineFailureKind;

  constructor(kind: EngineFailureKind) {
    super(`Playback ${kind}`);
    this.name = "EngineError";
    this.kind = kind;
  }
}

const OFFLINE = "Нет соединения. Проверьте интернет";
const SIGNED_OUT = "Сессия истекла, войдите снова";
const THROTTLED = "Слишком много запросов, попробуйте позже";
const SOURCE_NO_KEY = "Kodik недоступен: не удалось получить ключ";
const SOURCE_REJECTED = "Kodik временно недоступен, попробуйте позже";
const EPISODE_MISSING = "Серия ещё не появилась в Kodik";
const EPISODE_NOT_IN_TRACK = "Этой серии ещё нет в выбранной озвучке";
const EPISODE_NOWHERE = "Серия пока не вышла ни в одной озвучке";
const SOURCE_CHANGED = "Источник обновился, ждите обновления приложения";
const UNSUPPORTED = "Этот браузер не умеет показывать это видео";
const UNKNOWN = "Что-то пошло не так. Повторите попытку";

function kodikMessage(error: KodikError, episode: number | undefined): string {
  switch (error.kind) {
    case "title":
      return EPISODE_MISSING;
    // The number goes in wherever it is known (ErrorMessages.kt episodeMissingCopy).
    case "episode":
      return episode === undefined ? EPISODE_NOT_IN_TRACK : `Серии ${episode} ещё нет в этой озвучке`;
    case "nowhere":
      return episode === undefined ? EPISODE_NOWHERE : `Серия ${episode} пока не вышла ни в одной озвучке`;
    case "token":
      return SOURCE_NO_KEY;
    case "upstream":
    case "unavailable":
      return SOURCE_REJECTED;
    case "parser":
      return SOURCE_CHANGED;
    case "throttled":
      return THROTTLED;
    case "offline":
      return OFFLINE;
    default:
      return UNKNOWN;
  }
}

function engineMessage(error: EngineError): string {
  switch (error.kind) {
    // A playlist or segment the CDN refused, as Android's engine reports a bad HTTP status.
    case "network":
      return SOURCE_REJECTED;
    case "offline":
      return OFFLINE;
    case "unsupported":
      return UNSUPPORTED;
    default:
      return UNKNOWN;
  }
}

/**
 * The player's one place where a failure becomes copy (ErrorMessages.kt). Not `errorMessage` from
 * api/http: that one blames Shikimori for a 5xx. Exception text is never shown.
 */
export function playerMessage(error: unknown, episode?: number): string {
  if (error instanceof KodikError) return kodikMessage(error, episode);
  if (error instanceof EngineError) return engineMessage(error);
  if (error instanceof NetworkError) return OFFLINE;
  if (error instanceof ApiError && error.status === 401) return SIGNED_OUT;
  // A token refresh the worker throttled, rethrown by authorized.
  if (error instanceof ApiError && error.status === 429) return THROTTLED;
  return UNKNOWN;
}

/**
 * The failure screen's second button (PlayerFailure.kt): back to the episode list when no dub has
 * the episode or Kodik lacks the title, «Сменить озвучку» otherwise.
 */
export function failureAction(error: unknown): "dub" | "list" {
  return error instanceof KodikError && (error.kind === "title" || error.kind === "nowhere") ? "list" : "dub";
}
