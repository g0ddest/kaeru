#!/usr/bin/env node
// Two phones out of one process, against the real relay.
//
// The unit tests on both platforms talk to fakes; this is the one check that talks to the thing
// actually deployed — the room a Durable Object keeps, its `peer-left`, its close codes — with the
// frames the phones put on the wire: AES-128-GCM under the key from the link, `nonce ‖ ciphertext
// ‖ tag`, the room id and the sender's side byte as associated data, JSON with a `t` inside. If
// this does not go green the phones will not either, whatever the unit tests say.
//
// What it walks through, in order: a host opens a room; a guest joins and greets; the host answers
// with a greeting and a ping; both report and ping on the phones' own cadences; a third socket is
// refused with the relay's room-full code; the guest stalls and the host waits for it, then goes
// on; the guest says goodbye and leaves; the relay tells the host so; the host leaves.
//
//   node tools/together-probe/probe.js                 # the relay in local.properties, or the default
//   TOGETHER_RELAY_URL=wss://host/ node tools/together-probe/probe.js
//
// No dependencies: WebSocket and fetch are Node's own from 22 on. Exit code 1 if any step fails.

const crypto = require("crypto");
const fs = require("fs");
const path = require("path");

const relay = (process.env.TOGETHER_RELAY_URL || fromLocalProperties() || "wss://kaeru-relay.vitaliy-velikodniy.workers.dev")
  .replace(/\/+$/, "");
const HOST = 0;
const GUEST = 1;
const STATE_MS = 1_000;
const PING_MS = 5_000;
const ROOM_FULL = 4409;

const b64 = (bytes) => Buffer.from(bytes).toString("base64").replace(/\+/g, "-").replace(/\//g, "_").replace(/=+$/, "");
const room = b64(crypto.randomBytes(8));
const key = crypto.randomBytes(16);
const aad = (side) => Buffer.concat([Buffer.from(room, "utf8"), Buffer.from([side])]);

function seal(message, side) {
  const nonce = crypto.randomBytes(12);
  const cipher = crypto.createCipheriv("aes-128-gcm", key, nonce);
  cipher.setAAD(aad(side));
  const body = Buffer.concat([cipher.update(Buffer.from(JSON.stringify(message), "utf8")), cipher.final()]);
  return Buffer.concat([nonce, body, cipher.getAuthTag()]);
}

function open(frame, side) {
  const nonce = frame.subarray(0, 12);
  const tag = frame.subarray(frame.length - 16);
  const body = frame.subarray(12, frame.length - 16);
  const decipher = crypto.createDecipheriv("aes-128-gcm", key, nonce);
  decipher.setAAD(aad(side));
  decipher.setAuthTag(tag);
  return JSON.parse(Buffer.concat([decipher.update(body), decipher.final()]).toString("utf8"));
}

const started = Date.now();
const stamp = () => `${((Date.now() - started) / 1000).toFixed(2)}s`;
const failures = [];
function check(condition, what) {
  console.log(`${stamp()} ${condition ? "ok  " : "FAIL"} ${what}`);
  if (!condition) failures.push(what);
}

/** One phone's worth of the protocol: the count, the reports, the pings, the answers. */
class Phone {
  constructor(name, side) {
    this.name = name;
    this.side = side;
    this.seq = 0;
    this.peerSeq = 0;
    this.positionMs = 60_000;
    this.playing = true;
    this.buffering = false;
    this.peerName = null;
    this.offsets = [];
    this.heard = [];
    this.timers = [];
    this.heldForPeer = false;
    this.peerLoading = false;
    this.socket = null;
    this.closeCode = null;
    this.peerLeft = false;
  }

  log(line) { console.log(`${stamp()} ${this.name.padEnd(5)} ${line}`); }

  connect() {
    return new Promise((resolve, reject) => {
      const socket = new WebSocket(`${relay}/w/${room}`);
      socket.binaryType = "arraybuffer";
      this.socket = socket;
      socket.onopen = () => { this.log("socket open"); resolve(); };
      socket.onerror = (event) => reject(new Error(`socket error: ${event.message || event.type}`));
      socket.onclose = (event) => {
        this.closeCode = event.code;
        this.log(`socket closed code=${event.code}${event.reason ? ` reason=${event.reason}` : ""}`);
      };
      socket.onmessage = (event) => this.receive(event.data);
    });
  }

  next() { return ++this.seq; }

  send(message) {
    if (this.socket?.readyState !== WebSocket.OPEN) return;
    message.seq = this.next();
    this.socket.send(seal(message, this.side));
  }

  hello() {
    this.send({ t: "hello", name: this.name, animeId: 21, episode: 5, positionMs: this.positionMs, playing: this.playing });
  }

  ping() { this.send({ t: "ping", sentAt: Date.now() }); }

  state() {
    this.send({ t: "state", positionMs: this.positionMs, playing: this.playing, buffering: this.buffering, sentAt: Date.now() });
  }

  start() {
    this.timers.push(setInterval(() => {
      if (this.playing && !this.buffering) this.positionMs += STATE_MS;
      if (this.peerName !== null) this.state();
    }, STATE_MS));
    this.timers.push(setInterval(() => this.ping(), PING_MS));
  }

  receive(data) {
    if (typeof data === "string") {
      this.log(`relay says ${data}`);
      if (data.includes("peer-left")) this.peerLeft = true;
      return;
    }
    let message;
    try {
      message = open(Buffer.from(data), this.side === HOST ? GUEST : HOST);
    } catch (error) {
      this.log(`frame refused: ${error.message}`);
      this.heard.push({ t: "<garbled>" });
      return;
    }
    // The replay guard the sessions keep: nothing at or below the highest count already applied.
    if (message.seq <= this.peerSeq) { this.log(`replay refused ${message.t} seq=${message.seq}`); return; }
    this.peerSeq = message.seq;
    this.seq = Math.max(this.seq, message.seq);
    this.heard.push(message);
    switch (message.t) {
      case "hello":
        this.log(`heard hello from «${message.name}» episode=${message.episode} at ${message.positionMs}`);
        this.peerName = message.name;
        // The host answers a greeting with its own and a round trip for the clocks — Android's `arrived`.
        if (this.side === HOST) { this.hello(); this.ping(); }
        break;
      case "ping": {
        const at = Date.now();
        this.send({ t: "pong", pingSentAt: message.sentAt, receivedAt: at, sentAt: at });
        break;
      }
      case "pong": {
        const now = Date.now();
        const offset = ((message.receivedAt - message.pingSentAt) + (message.sentAt - now)) / 2;
        const rtt = (now - message.pingSentAt) - (message.sentAt - message.receivedAt);
        this.offsets.push({ offset, rtt });
        this.log(`pong: offset=${offset}ms rtt=${rtt}ms`);
        break;
      }
      case "state": {
        // `peerIsLoading`, as both sessions keep it: a friend who means to play and is loading is waited for.
        const loading = message.buffering && message.playing;
        if (loading !== this.peerLoading) {
          this.peerLoading = loading;
          if (loading && this.playing) { this.heldForPeer = true; this.playing = false; this.log("holds for a friend who is loading"); }
          else if (!loading && this.heldForPeer) { this.heldForPeer = false; this.playing = true; this.log("goes on: the friend is ready"); }
        }
        break;
      }
      case "bye":
        this.log("heard goodbye");
        break;
      default:
        this.log(`heard ${message.t}`);
    }
  }

  close() {
    this.timers.forEach(clearInterval);
    this.timers = [];
    this.socket?.close(1000);
  }
}

function fromLocalProperties() {
  try {
    const file = fs.readFileSync(path.join(__dirname, "..", "..", "local.properties"), "utf8");
    const line = file.split("\n").find((entry) => entry.startsWith("TOGETHER_RELAY_URL="));
    return line ? line.slice("TOGETHER_RELAY_URL=".length).trim() : null;
  } catch {
    return null;
  }
}

const sleep = (ms) => new Promise((resolve) => setTimeout(resolve, ms));

/** A third phone knocking on a room that already holds two: the relay must say so in a close code. */
function thirdWheel() {
  return new Promise((resolve) => {
    const socket = new WebSocket(`${relay}/w/${room}`);
    socket.binaryType = "arraybuffer";
    socket.onclose = (event) => resolve(event.code);
    socket.onerror = () => {};
    setTimeout(() => { try { socket.close(); } catch {} resolve(null); }, 5_000);
  });
}

async function main() {
  console.log(`relay ${relay}`);
  console.log(`room  ${room}`);
  console.log(`link  https://kaeru.vitaliy.velikodniy.name/w/${room}#${b64(key)}`);

  const health = await fetch(relay.replace(/^wss:/, "https:").replace(/^ws:/, "http:") + "/health").then((r) => r.text()).catch((e) => `error: ${e.message}`);
  check(health === "ok", `GET /health answers ok (got «${health}»)`);

  const host = new Phone("Host", HOST);
  const guest = new Phone("Guest", GUEST);
  await host.connect();
  host.start();
  await sleep(1_000);

  await guest.connect();
  // The guest's first frames are its greeting and a ping, before anything else it could send.
  guest.hello();
  guest.ping();
  guest.start();

  await sleep(2_000);
  check(host.peerName === "Guest", "host heard the guest's hello");
  check(guest.peerName === "Host", "guest heard the host's hello back");
  check(host.heard.some((m) => m.t === "pong"), "host got a pong for its ping");
  check(guest.heard.some((m) => m.t === "pong"), "guest got a pong for its ping");
  check(guest.heard.some((m) => m.t === "state") && host.heard.some((m) => m.t === "state"), "reports flow both ways");
  check(!host.heard.some((m) => m.t === "<garbled>") && !guest.heard.some((m) => m.t === "<garbled>"), "every frame authenticated");

  const refused = await thirdWheel();
  check(refused === ROOM_FULL, `a third socket is closed with ${ROOM_FULL} (got ${refused})`);

  // The guest stalls on a segment. It says so at once, as the phones do, and keeps saying so.
  guest.buffering = true;
  guest.state();
  await sleep(1_500);
  check(host.heldForPeer && host.playing === false, "host holds while the guest loads");
  guest.buffering = false;
  guest.state();
  await sleep(1_500);
  check(!host.heldForPeer && host.playing === true, "host goes on once the guest is ready");

  const hostOffsets = host.offsets.map((o) => o.offset);
  const rtts = host.offsets.map((o) => o.rtt);
  check(hostOffsets.length >= 1 && hostOffsets.every((o) => Math.abs(o) < 1_000), `one clock reads itself within a second (offsets ${hostOffsets.join(", ")})`);
  check(rtts.every((r) => r >= 0 && r < 3_000), `round trips are round trips (${rtts.join(", ")} ms)`);

  guest.send({ t: "bye" });
  await sleep(300);
  guest.close();
  await sleep(2_000);
  check(host.heard.some((m) => m.t === "bye"), "host heard the guest's goodbye");
  check(host.peerLeft, "relay told the host the guest left");

  host.close();
  // Said rather than checked: the relay does not always answer the last peer's close frame, and
  // both phones cut the socket after a second of waiting for exactly this reason.
  for (let waited = 0; host.closeCode === null && waited < 3_000; waited += 100) await sleep(100);
  console.log(`${stamp()} note the relay ${host.closeCode === null ? "did not answer" : `answered code=${host.closeCode} to`} the last peer's close frame`);

  console.log(failures.length === 0 ? "\nall green" : `\n${failures.length} failed:\n  ${failures.join("\n  ")}`);
  process.exit(failures.length === 0 ? 0 : 1);
}

main().catch((error) => {
  console.error(`${stamp()} probe crashed: ${error.stack || error}`);
  process.exit(1);
});
