import { SHIKIMORI_URL } from "../config";

/** Shikimori answered, and not with a success. `body` is the parsed JSON, or null. */
export class ApiError extends Error {
  readonly status: number;
  readonly body: unknown;

  constructor(status: number, body: unknown = null) {
    super(`HTTP ${status}`);
    this.name = "ApiError";
    this.status = status;
    this.body = body;
  }
}

/** No answer at all: offline, DNS, a CORS refusal, a timeout. */
export class NetworkError extends Error {
  constructor(message = "Network request failed") {
    super(message);
    this.name = "NetworkError";
  }
}

const SECOND_MS = 1_000;
const MINUTE_MS = 60_000;
const RETRY_AFTER_MS = 1_000;
const TIMEOUT_MS = 30_000;

const defaultSleep = (ms: number): Promise<void> => new Promise((resolve) => setTimeout(resolve, ms));

function abortReason(signal: AbortSignal): unknown {
  return signal.reason ?? new DOMException("The operation was aborted.", "AbortError");
}

/** [wait], cut short with the abort reason as soon as [signal] aborts. */
function abortable(wait: Promise<void>, signal?: AbortSignal): Promise<void> {
  if (signal === undefined) return wait;
  if (signal.aborted) return Promise.reject(abortReason(signal));
  return new Promise<void>((resolve, reject) => {
    const onAbort = (): void => reject(abortReason(signal));
    signal.addEventListener("abort", onAbort, { once: true });
    wait.then(
      () => {
        signal.removeEventListener("abort", onAbort);
        resolve();
      },
      (error: unknown) => {
        signal.removeEventListener("abort", onAbort);
        reject(error);
      },
    );
  });
}

function dropOlder(stamps: number[], now: number, span: number): void {
  for (let oldest = stamps[0]; oldest !== undefined && now - oldest >= span; oldest = stamps[0]) stamps.shift();
}

function waitFor(stamps: readonly number[], limit: number, now: number, span: number): number {
  const oldest = stamps[0];
  return stamps.length >= limit && oldest !== undefined ? span - (now - oldest) : 0;
}

/**
 * Shikimori's budget as the apps keep it (ShikimoriRateLimiter.kt): at most 5 requests in any
 * second and 90 in any minute. Callers are served in order; an aborted caller gives up its turn.
 */
export class RateLimiter {
  private readonly perSecond: number;
  private readonly perMinute: number;
  private readonly now: () => number;
  private readonly sleep: (ms: number) => Promise<void>;
  private readonly second: number[] = [];
  private readonly minute: number[] = [];
  private tail: Promise<void> = Promise.resolve();

  constructor(opts: { perSecond?: number; perMinute?: number; now?: () => number; sleep?: (ms: number) => Promise<void> } = {}) {
    this.perSecond = opts.perSecond ?? 5;
    this.perMinute = opts.perMinute ?? 90;
    // Monotonic: a clock change must not open or close the windows.
    this.now = opts.now ?? (() => performance.now());
    this.sleep = opts.sleep ?? defaultSleep;
  }

  acquire(signal?: AbortSignal): Promise<void> {
    const turn = this.tail.then(() => this.take(signal));
    // The next caller waits for this one whether it got its slot or gave up.
    this.tail = turn.catch(() => undefined);
    return turn;
  }

  private async take(signal?: AbortSignal): Promise<void> {
    for (;;) {
      if (signal?.aborted) throw abortReason(signal);
      const now = this.now();
      dropOlder(this.second, now, SECOND_MS);
      dropOlder(this.minute, now, MINUTE_MS);
      const wait = Math.max(
        waitFor(this.second, this.perSecond, now, SECOND_MS),
        waitFor(this.minute, this.perMinute, now, MINUTE_MS),
      );
      if (wait <= 0) {
        this.second.push(now);
        this.minute.push(now);
        return;
      }
      await abortable(this.sleep(wait), signal);
    }
  }
}

/** One budget for the whole tab: the limit is Shikimori's per address, not per screen. */
const sharedLimiter = new RateLimiter();

export interface ShikimoriRequest {
  method?: "GET" | "POST" | "PATCH";
  token?: string | null;
  json?: unknown;
  signal?: AbortSignal;
}

interface Answer {
  status: number;
  text: string;
}

async function exchange(
  send: typeof fetch,
  url: string,
  init: RequestInit,
  signal: AbortSignal | undefined,
  timeoutMs: number,
): Promise<Answer> {
  if (signal?.aborted) throw abortReason(signal);
  const controller = new AbortController();
  const timer = setTimeout(() => controller.abort(), timeoutMs);
  const forward = (): void => controller.abort();
  signal?.addEventListener("abort", forward, { once: true });
  try {
    const response = await send(url, { ...init, signal: controller.signal });
    return { status: response.status, text: await response.text() };
  } catch {
    // The caller's own abort is a cancellation, not a network failure.
    if (signal?.aborted) throw abortReason(signal);
    throw new NetworkError(controller.signal.aborted ? "Request timed out" : "Network request failed");
  } finally {
    clearTimeout(timer);
    signal?.removeEventListener("abort", forward);
  }
}

function parseJson(text: string): { ok: true; value: unknown } | { ok: false } {
  if (text.trim() === "") return { ok: true, value: null };
  try {
    return { ok: true, value: JSON.parse(text) as unknown };
  } catch {
    return { ok: false };
  }
}

function read<T>(answer: Answer): T {
  const body = parseJson(answer.text);
  if (answer.status < 200 || answer.status > 299) throw new ApiError(answer.status, body.ok ? body.value : null);
  if (!body.ok) throw new Error("Invalid API response");
  return body.value as T;
}

export function createShikimoriHttp(
  deps: { fetch?: typeof fetch; limiter?: RateLimiter; sleep?: (ms: number) => Promise<void>; timeoutMs?: number } = {},
): <T>(path: string, request?: ShikimoriRequest) => Promise<T> {
  const send: typeof fetch = deps.fetch ?? ((input, init) => fetch(input, init));
  const limiter = deps.limiter ?? sharedLimiter;
  const sleep = deps.sleep ?? defaultSleep;
  const timeoutMs = deps.timeoutMs ?? TIMEOUT_MS;

  return async function request<T>(path: string, options: ShikimoriRequest = {}): Promise<T> {
    // X-Requested-With stands in for the User-Agent a page cannot set; Shikimori's preflight allows it.
    const headers: Record<string, string> = { Accept: "application/json", "X-Requested-With": "Kaeru" };
    if (options.token != null && options.token.trim() !== "") headers["Authorization"] = `Bearer ${options.token}`;
    const init: RequestInit = { method: options.method ?? "GET", headers };
    if (options.json !== undefined) {
      headers["Content-Type"] = "application/json";
      init.body = JSON.stringify(options.json);
    }
    for (let attempt = 0; ; attempt += 1) {
      await limiter.acquire(options.signal);
      const answer = await exchange(send, `${SHIKIMORI_URL}/${path}`, init, options.signal, timeoutMs);
      if (answer.status === 429 && attempt === 0) {
        // Retry-After is not exposed to scripts, so the apps' default of one second.
        await abortable(sleep(RETRY_AFTER_MS), options.signal);
        continue;
      }
      return read<T>(answer);
    }
  };
}

/** The one place a failure becomes copy (ErrorMessages.kt); exception text is never shown. */
export function errorMessage(error: unknown): string {
  if (error instanceof NetworkError) return "Нет соединения. Проверьте интернет";
  if (error instanceof ApiError) {
    if (error.status === 401 || error.status === 403) return "Сессия истекла, войдите снова";
    if (error.status === 429) return "Слишком много запросов, попробуйте позже";
    if (error.status >= 500) return "Shikimori недоступен, попробуйте позже";
  }
  return "Что-то пошло не так. Повторите попытку";
}
