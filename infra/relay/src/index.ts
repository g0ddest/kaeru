/**
 * Kaeru relay — a Cloudflare Worker that forwards opaque frames between exactly
 * two peers watching one episode together.
 *
 * Peers encrypt every frame with AES-GCM under a key that travels only in the
 * fragment of the invite link, so the room id in the path is the whole of what
 * this Worker learns about a session. Nothing is stored and nothing is logged
 * beyond the first four characters of the room id and the kind of event.
 *
 * Routes
 *   GET /health     200 "ok"
 *   GET /w/:roomId  WebSocket upgrade into the room's Durable Object
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

// Only the handler and the Durable Object class may be exported: workerd refuses to
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
