/**
 * Kaeru relay — a Cloudflare Worker that forwards opaque frames between exactly
 * two peers watching one episode together.
 *
 * Peers encrypt every frame with AES-GCM under a key that travels only in the
 * fragment of the invite link, so the room id in the path is the whole of what
 * this Worker learns about a session. Nothing is stored and nothing is logged
 * beyond the first four characters of the room id and the kind of event.
 *
 * It also holds the app's Shikimori client secret. The secret used to be compiled into every
 * APK, where anyone could read it; it now exists only as a `wrangler secret` on this Worker,
 * and `POST /oauth/token` is the one route that adds it to a request. See `proxyToken`.
 *
 * Routes
 *   GET  /health       200 "ok"
 *   GET  /w/:roomId    WebSocket upgrade into the room's Durable Object
 *   POST /oauth/token  Shikimori's token endpoint, with the client secret filled in
 *
 * Client to server: binary frames only, at most 64 KiB, forwarded verbatim to the
 * other peer and never echoed back to the sender. Text frames are ignored.
 *
 * Server to client: text frames only, each a JSON control message. There is one,
 * `{"type":"peer-left"}`, sent to the survivor when the other socket goes away.
 *
 * Close codes follow the usual mapping of an HTTP status into the private range,
 * status + 4000:
 *   4408  the room sat idle for six hours and its alarm closed it
 *   4409  the room already holds two peers
 *   4413  the sender pushed a frame larger than 64 KiB
 */

export interface Env extends Cloudflare.Env {}

/**
 * Room ids are 64 random bits in base64url, which is 11 characters. The range is
 * widened a little so the client can change its encoding without a redeploy here.
 */
const ROOM_ID_PATTERN = /^[A-Za-z0-9_-]{8,16}$/;

/** Only `/w/<something>` with no further slashes is a room. */
const ROOM_PATH_PATTERN = /^\/w\/([^/]*)$/;

/** Voice clips are chunked below this by the client; the spec caps frames here. */
const MAX_FRAME_BYTES = 64 * 1024;

/** A room is a pair. */
const MAX_PEERS = 2;

/** A room with no frames for this long is closed. */
const IDLE_MS = 6 * 60 * 60 * 1000;

/**
 * The idle alarm is rewritten at most this often. Rewriting it on every frame
 * would mean a storage write every second, so the room closes somewhere between
 * six hours and six hours five minutes after its last frame.
 */
const ALARM_REFRESH_MS = 5 * 60 * 1000;

const CLOSE_IDLE = 4408;
const CLOSE_ROOM_FULL = 4409;
const CLOSE_FRAME_TOO_LARGE = 4413;

/** The app's token exchange: the one route that holds a credential. */
const TOKEN_PATH = "/oauth/token";

/**
 * Shikimori's own token endpoint — the only address the client secret is ever sent to.
 *
 * `shikimori.io`, matching every other call the app makes. `.one` answers this path only through
 * a DDoS-Guard 308 across to `.io`: the redirect works today, but it doubles the latency of every
 * sign-in and every refresh and is one challenge page away from breaking both.
 */
const SHIKIMORI_TOKEN_URL = "https://shikimori.io/oauth/token";

/** A token request is a few hundred bytes. Anything this size is not one. */
const MAX_TOKEN_BODY_BYTES = 8 * 1024;

/** What one address may ask of the token route inside [RATE_WINDOW_MS]. */
const RATE_LIMIT = 30;
const RATE_WINDOW_MS = 60 * 1000;

/** The bucket every request that arrives without `CF-Connecting-IP` shares. */
const SHARED_BUCKET = "no-ip";

/** The single storage key a rate bucket writes. */
const WINDOW_KEY = "window";

/**
 * The fields each grant may carry, in the order they are forwarded. A request naming anything
 * else is refused rather than forwarded: `client_secret` included, because that one is this
 * Worker's to add and a caller's copy of it could only be a guess.
 */
const GRANT_FIELDS = new Map<string, readonly string[]>([
  ["authorization_code", ["grant_type", "client_id", "code", "redirect_uri"]],
  ["refresh_token", ["grant_type", "client_id", "refresh_token"]],
]);

/**
 * The redirect URIs this app uses, and the only two an exchange may name: `MOBILE_REDIRECT` and
 * `OOB_REDIRECT` in `domain/repository/AuthRepository.kt`. Shikimori bounds `redirect_uri` to the
 * URIs registered for the client id anyway; holding the same line here means this Worker cannot be
 * talked into redeeming a code on behalf of a redirect the app would never have asked for.
 */
const ALLOWED_REDIRECTS = new Set(["kaeru://oauth", "urn:ietf:wg:oauth:2.0:oob"]);

/** The field whose absence leaves each grant with nothing to exchange. */
const GRANT_REQUIRED = new Map<string, string>([
  ["authorization_code", "code"],
  ["refresh_token", "refresh_token"],
]);

/** A rate bucket: when the current window opened, and what has been counted into it. */
interface RateWindow {
  readonly start: number;
  readonly count: number;
}

const PEER_LEFT = JSON.stringify({ type: "peer-left" });

/** What each socket carries across hibernation: enough to write a log line. */
interface SocketTag {
  readonly room: string;
}

function plain(body: string, status: number): Response {
  return new Response(body, {
    status,
    headers: { "content-type": "text/plain; charset=utf-8" },
  });
}

/** The first four characters of a room id: enough to correlate, not enough to join. */
function roomTag(roomId: string): string {
  return roomId.slice(0, 4);
}

function log(room: string, event: string, detail?: string): void {
  console.log(detail === undefined ? `room=${room} ${event}` : `room=${room} ${event} ${detail}`);
}

// Only the handler and the Durable Object classes may be exported: workerd refuses to
// start a Worker whose module exports a value that is not a handler or an entrypoint
// class, so the constants above stay module-private. `wrangler deploy --dry-run` does
// not catch that — it only bundles — but `wrangler dev` and a real deploy do.
export default {
  async fetch(request: Request, env: Env): Promise<Response> {
    const url = new URL(request.url);

    if (url.pathname === "/health") {
      if (request.method !== "GET" && request.method !== "HEAD") {
        return plain("method not allowed", 405);
      }
      return plain("ok", 200);
    }

    if (url.pathname === TOKEN_PATH) return proxyToken(request, env);

    const path = ROOM_PATH_PATTERN.exec(url.pathname);
    if (path === null) return plain("not found", 404);
    if (request.method !== "GET") return plain("method not allowed", 405);

    const roomId = path[1];
    if (!ROOM_ID_PATTERN.test(roomId)) return plain("bad room id", 400);

    if (request.headers.get("Upgrade")?.toLowerCase() !== "websocket") {
      return plain("expected websocket upgrade", 426);
    }

    return env.ROOM.get(env.ROOM.idFromName(roomId)).fetch(request);
  },
} satisfies ExportedHandler<Env>;

/**
 * Shikimori's token endpoint with the client secret filled in.
 *
 * The app sends the form it would have sent Shikimori, minus the secret it no longer has; this
 * adds the secret and hands back Shikimori's own status and body untouched, so every error the
 * app already knows how to read still reaches it verbatim.
 *
 * Three things are checked before anything is forwarded: the caller is this app (`client_id`
 * against the public id in wrangler.toml), the form carries exactly the fields its grant names,
 * and the address has not spent its quota. Nothing of the request is logged or echoed — a `code`
 * and a `refresh_token` are as good as a session while they last.
 */
async function proxyToken(request: Request, env: Env): Promise<Response> {
  if (request.method !== "POST") return plain("method not allowed", 405);

  // A Worker deployed before `wrangler secret put` has nothing to add to the form.
  // `URLSearchParams.set` would stringify the missing value and send Shikimori the literal
  // "undefined", earning an `invalid_client` that reads exactly like a rejected refresh token.
  const secret = env.SHIKIMORI_CLIENT_SECRET;
  if (!secret) {
    console.log("oauth not configured");
    return plain("not configured", 503);
  }

  // Before the body is read, so a flood costs this Worker one storage read apiece.
  const retryAfter = await rateLimit(request, env);
  if (retryAfter > 0) {
    return new Response("too many requests", {
      status: 429,
      headers: {
        "content-type": "text/plain; charset=utf-8",
        "retry-after": String(retryAfter),
      },
    });
  }

  // An honest Content-Length is required before a byte is read. Reading a body to find out how
  // big it is means buffering whatever the caller sends — Cloudflare's platform cap is 100 MB
  // against a 128 MB Worker — and a chunked body declares nothing at all. OkHttp sets the header
  // on every FormBody, so the app never meets this.
  const declared = Number(request.headers.get("content-length"));
  if (!Number.isInteger(declared) || declared <= 0) return plain("missing content-length", 400);
  if (declared > MAX_TOKEN_BODY_BYTES) return plain("body too large", 400);
  // Read as bytes and decoded here: the header above is a claim, this is the fact, and the
  // runtime warns about `.text()` on a body whose content type is not a text one.
  const raw = await request.arrayBuffer();
  if (raw.byteLength > MAX_TOKEN_BODY_BYTES) return plain("body too large", 400);

  const form = new URLSearchParams(new TextDecoder().decode(raw));
  const refusal = refuseToken(form, env);
  if (refusal !== null) {
    console.log(`oauth refused ${refusal}`);
    return plain(refusal, 400);
  }

  const grant = form.get("grant_type") ?? "";
  let status: number;
  let answer: string;
  try {
    const upstream = await fetch(SHIKIMORI_TOKEN_URL, {
      method: "POST",
      headers: {
        "content-type": "application/x-www-form-urlencoded",
        "user-agent": "Kaeru",
        accept: "application/json",
      },
      body: upstreamForm(form, grant, secret).toString(),
    });
    status = upstream.status;
    answer = await upstream.text();
  } catch {
    // Nothing of the failure is repeated: its message carries the request that caused it.
    console.log("oauth upstream unreachable");
    return plain("upstream unavailable", 502);
  }

  // The grant and the status only; the body is a token or the reason there is none.
  console.log(`oauth ${grant} upstream=${status}`);
  return new Response(answer.length === 0 ? null : answer, {
    status,
    headers: { "content-type": "application/json" },
  });
}

/** The reason to refuse this form, or null when there is none. Never quotes what was sent. */
function refuseToken(form: URLSearchParams, env: Env): string | null {
  const fields = GRANT_FIELDS.get(form.get("grant_type") ?? "");
  if (fields === undefined) return "unsupported grant_type";
  for (const name of form.keys()) {
    if (!fields.includes(name)) return "unexpected field";
  }
  for (const name of fields) {
    // Two values for one field is an attempt to have this Worker and Shikimori read it differently.
    if (form.getAll(name).length > 1) return "repeated field";
  }
  if (form.get("client_id") !== env.SHIKIMORI_CLIENT_ID) return "unknown client";
  const required = GRANT_REQUIRED.get(form.get("grant_type") ?? "") ?? "";
  if ((form.get(required) ?? "") === "") return `missing ${required}`;
  if (
    form.get("grant_type") === "authorization_code" &&
    !ALLOWED_REDIRECTS.has(form.get("redirect_uri") ?? "")
  ) {
    return "unexpected redirect_uri";
  }
  return null;
}

/** The form Shikimori is asked: the grant's own fields, and the secret the app never had. */
function upstreamForm(form: URLSearchParams, grant: string, secret: string): URLSearchParams {
  const upstream = new URLSearchParams();
  for (const name of GRANT_FIELDS.get(grant) ?? []) {
    const value = form.get(name);
    if (value !== null) upstream.set(name, value);
  }
  upstream.set("client_secret", secret);
  return upstream;
}

/**
 * Seconds the caller must wait, or 0 when this request is within its address's quota.
 *
 * Fails open. A Durable Object can be overloaded, or its migration not yet applied, and a limiter
 * in that state must not take sign-in down with it: the route is still bounded by Cloudflare's own
 * limits, and letting the exception escape would answer a refresh with a 500.
 */
async function rateLimit(request: Request, env: Env): Promise<number> {
  const ip = request.headers.get("CF-Connecting-IP") ?? SHARED_BUCKET;
  try {
    const response = await env.RATE.get(env.RATE.idFromName(ip)).fetch("https://rate.invalid/");
    const { retryAfter } = (await response.json()) as { retryAfter: number };
    return retryAfter;
  } catch {
    console.log("oauth rate unavailable");
    return 0;
  }
}

/**
 * One instance per client address, addressed by `idFromName(ip)`.
 *
 * A fixed window, not a rolling one: a count and the instant the window opened, both in storage,
 * compared against the clock on every call. The honest consequence is that a span of sixty seconds
 * straddling a boundary can carry twice the limit — the tail of one window plus the head of the
 * next — which for this route is fine, and the simplicity is worth it.
 *
 * The window arms an alarm at its own end, and the alarm wipes the bucket. Without that, every
 * address that ever posted here would be a stored row forever: one instance per address, and an
 * IPv6 /64 is millions of addresses anyone may rotate through for free.
 */
export class RateDO implements DurableObject {
  readonly #state: DurableObjectState;

  constructor(state: DurableObjectState, _env: Env) {
    this.#state = state;
  }

  async fetch(_request: Request): Promise<Response> {
    const now = Date.now();
    const stored = await this.#state.storage.get<RateWindow>(WINDOW_KEY);
    const open = stored !== undefined && now - stored.start < RATE_WINDOW_MS ? stored : null;

    if (open !== null && open.count >= RATE_LIMIT) {
      // Not written back: hammering a spent bucket must not push its window further out.
      const retryAfter = Math.max(1, Math.ceil((open.start + RATE_WINDOW_MS - now) / 1000));
      return Response.json({ retryAfter });
    }

    const start = open?.start ?? now;
    await this.#state.storage.put<RateWindow>(WINDOW_KEY, { start, count: (open?.count ?? 0) + 1 });
    if (open === null) {
      // A fresh window: the one alarm it needs, at the instant it stops counting. Reopening later
      // overwrites this alarm rather than adding one, so a busy address still holds exactly one.
      await this.#state.storage.setAlarm(start + RATE_WINDOW_MS);
    }
    return Response.json({ retryAfter: 0 });
  }

  async alarm(): Promise<void> {
    // The window has closed and nothing here is worth keeping: the next request from this address
    // would open a fresh one over it anyway. A bucket that is never asked about again costs
    // nothing from here on.
    await this.#state.storage.deleteAll();
  }
}

/**
 * One instance per room, addressed by `idFromName(roomId)`.
 *
 * Sockets are accepted through the hibernation API, so a room with two idle peers
 * costs nothing while nobody is sending: the instance is evicted and woken by the
 * runtime for each frame. Nothing may therefore live in memory across frames
 * except hints that are safe to lose, which is why the peer list comes from
 * `getWebSockets()` and the room tag from each socket's attachment.
 */
export class RoomDO implements DurableObject {
  readonly #state: DurableObjectState;

  /**
   * The deadline of the alarm currently in storage, or 0 when this instance has not
   * looked yet. A fresh instance starts at 0 after every hibernation wake, which is
   * why `#armIdleAlarm` reads the real deadline back rather than assuming none.
   */
  #alarmDeadline = 0;

  constructor(state: DurableObjectState, _env: Env) {
    this.#state = state;
  }

  async fetch(request: Request): Promise<Response> {
    const path = ROOM_PATH_PATTERN.exec(new URL(request.url).pathname);
    const room = roomTag(path === null ? "" : path[1]);
    const pair = new WebSocketPair();
    const client = pair[0];
    const server = pair[1];

    const peers = this.#state.getWebSockets();
    if (peers.length >= MAX_PEERS) {
      // Accepted without hibernation and closed at once, so the client learns why
      // from a close code instead of a failed handshake.
      log(room, "refused", "room full");
      server.accept();
      server.close(CLOSE_ROOM_FULL, "room full");
      return new Response(null, { status: 101, webSocket: client });
    }

    this.#state.acceptWebSocket(server);
    server.serializeAttachment({ room } satisfies SocketTag);
    await this.#armIdleAlarm(Date.now());
    log(room, "join", `peers=${peers.length + 1}`);
    return new Response(null, { status: 101, webSocket: client });
  }

  async webSocketMessage(ws: WebSocket, message: string | ArrayBuffer): Promise<void> {
    // Only the server sends text, and it sends control messages; a peer's text is noise.
    if (typeof message === "string") return;

    if (message.byteLength > MAX_FRAME_BYTES) {
      log(this.#roomOf(ws), "oversize", `${message.byteLength}b`);
      // The runtime calls webSocketClose for this too, so the peer is told there.
      tryClose(ws, CLOSE_FRAME_TOO_LARGE, "frame too large");
      return;
    }

    for (const peer of this.#state.getWebSockets()) {
      if (peer === ws) continue;
      trySend(peer, message);
    }
    await this.#armIdleAlarm(Date.now());
  }

  async webSocketClose(ws: WebSocket, code: number, _reason: string, wasClean: boolean): Promise<void> {
    log(this.#roomOf(ws), "close", `code=${code} clean=${wasClean}`);
    this.#tellPeerLeft(ws);
  }

  async webSocketError(ws: WebSocket, _error: unknown): Promise<void> {
    log(this.#roomOf(ws), "error");
    this.#tellPeerLeft(ws);
  }

  async alarm(): Promise<void> {
    const peers = this.#state.getWebSockets();
    log(peers.length === 0 ? "" : this.#roomOf(peers[0]), "idle-close", `peers=${peers.length}`);
    for (const peer of peers) {
      tryClose(peer, CLOSE_IDLE, "idle");
    }
    // Nothing is deleted here. The runtime drops the alarm that fired before calling
    // this handler, and the alarm is the only key a room ever writes, so by now this
    // room owns nothing. Anything in storage belongs to a join that landed in the same
    // turn, and wiping that would leave the newcomer with no idle expiry at all.
    this.#alarmDeadline = 0;
  }

  /** Sends the one server-originated control message to whoever is left. */
  #tellPeerLeft(gone: WebSocket): void {
    for (const peer of this.#state.getWebSockets()) {
      if (peer === gone) continue;
      trySend(peer, PEER_LEFT);
    }
  }

  /**
   * Pushes the idle deadline out, writing the alarm only when it has drifted more
   * than `ALARM_REFRESH_MS` behind. The throttle has to survive hibernation, so on
   * the first call of an instance's life the scheduled deadline is read back from
   * storage instead of assumed absent: a wake per frame would otherwise write an
   * alarm per frame, which is exactly the cost the hibernation API exists to avoid.
   */
  async #armIdleAlarm(now: number): Promise<void> {
    if (this.#alarmDeadline === 0) {
      this.#alarmDeadline = (await this.#state.storage.getAlarm()) ?? 0;
    }
    const deadline = now + IDLE_MS;
    if (deadline - this.#alarmDeadline < ALARM_REFRESH_MS) return;
    this.#alarmDeadline = deadline;
    await this.#state.storage.setAlarm(deadline);
  }

  #roomOf(ws: WebSocket): string {
    try {
      const tag = ws.deserializeAttachment() as SocketTag | null;
      return tag?.room ?? "";
    } catch {
      return "";
    }
  }
}

/** A socket can close between the peer list and the send; that is not an error here. */
function trySend(ws: WebSocket, payload: string | ArrayBuffer): void {
  try {
    ws.send(payload);
  } catch {
    // The peer is on its way out; its own close event will do the rest.
  }
}

/** Likewise a socket can already be gone by the time there is a reason to close it. */
function tryClose(ws: WebSocket, code: number, reason: string): void {
  try {
    ws.close(code, reason);
  } catch {
    // Already closed; nothing to do.
  }
}
