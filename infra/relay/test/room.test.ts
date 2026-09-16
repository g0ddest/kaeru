import {
  env,
  evictDurableObject,
  runDurableObjectAlarm,
  runInDurableObject,
  SELF,
} from "cloudflare:test";
import { describe, expect, it } from "vitest";

import type { RoomDO } from "../src/index";

const ORIGIN = "https://relay.test";
const MAX_FRAME_BYTES = 64 * 1024;

let roomCounter = 0;

/** A room id no other test uses, so no two tests share a Durable Object. */
function freshRoom(): string {
  roomCounter += 1;
  return `test-room-${String(roomCounter).padStart(2, "0")}`;
}

interface Peer {
  readonly socket: WebSocket;
  readonly binary: Uint8Array[];
  readonly text: string[];
  readonly closes: Array<{ code: number; reason: string }>;
}

interface Join {
  readonly response: Response;
  readonly peer: Peer | null;
}

async function join(roomId: string): Promise<Join> {
  const response = await SELF.fetch(`${ORIGIN}/w/${roomId}`, {
    headers: { Upgrade: "websocket" },
  });
  const socket = response.webSocket;
  if (socket === null) return { response, peer: null };

  // Without this the runtime hands binary messages over as Blobs.
  socket.binaryType = "arraybuffer";
  const peer: Peer = { socket, binary: [], text: [], closes: [] };
  socket.addEventListener("message", (event) => {
    if (typeof event.data === "string") peer.text.push(event.data);
    else if (event.data instanceof ArrayBuffer) peer.binary.push(new Uint8Array(event.data));
    else throw new Error(`unexpected message payload: ${Object.prototype.toString.call(event.data)}`);
  });
  socket.addEventListener("close", (event) => {
    peer.closes.push({ code: event.code, reason: event.reason });
  });
  socket.accept();
  return { response, peer };
}

/** Joins and fails the test if the upgrade was refused. */
async function joinOrThrow(roomId: string): Promise<Peer> {
  const { response, peer } = await join(roomId);
  if (peer === null) throw new Error(`upgrade refused with ${response.status}`);
  return peer;
}

/** Gives the runtime a moment to deliver frames that are already in flight. */
function settle(ms = 50): Promise<void> {
  return new Promise((resolve) => setTimeout(resolve, ms));
}

function bytes(length: number, fill = 7): Uint8Array {
  return new Uint8Array(length).fill(fill);
}

function roomStub(roomId: string): DurableObjectStub {
  return env.ROOM.get(env.ROOM.idFromName(roomId));
}

describe("routes", () => {
  it("answers /health with ok", async () => {
    const response = await SELF.fetch(`${ORIGIN}/health`);

    expect(response.status).toBe(200);
    expect(await response.text()).toBe("ok");
  });

  it("answers an unknown path with 404", async () => {
    const response = await SELF.fetch(`${ORIGIN}/nope`);

    expect(response.status).toBe(404);
  });

  it("refuses a room id that is not 8 to 16 url-safe characters", async () => {
    const bad = ["short", "0123456789abcdefg", "has.dot", "has%20space", ""];

    for (const roomId of bad) {
      const response = await SELF.fetch(`${ORIGIN}/w/${roomId}`, {
        headers: { Upgrade: "websocket" },
      });
      expect(response.status, `room id ${JSON.stringify(roomId)}`).toBe(400);
    }
  });

  it("accepts the room ids the client actually mints", async () => {
    // 64 random bits in base64url are 11 characters, with - and _ in the alphabet.
    const response = await SELF.fetch(`${ORIGIN}/w/a-Zz09_8xQw`, {
      headers: { Upgrade: "websocket" },
    });

    expect(response.status).toBe(101);
  });

  it("asks a plain GET on a room to upgrade", async () => {
    const response = await SELF.fetch(`${ORIGIN}/w/${freshRoom()}`);

    expect(response.status).toBe(426);
  });
});

describe("room occupancy", () => {
  it("accepts two peers into one room", async () => {
    const roomId = freshRoom();

    const first = await join(roomId);
    const second = await join(roomId);

    expect(first.response.status).toBe(101);
    expect(second.response.status).toBe(101);
    await runInDurableObject(roomStub(roomId), (_instance: RoomDO, state) => {
      expect(state.getWebSockets()).toHaveLength(2);
    });
  });

  it("turns the third peer away with close code 4409", async () => {
    const roomId = freshRoom();
    await joinOrThrow(roomId);
    await joinOrThrow(roomId);

    const third = await join(roomId);
    await settle();

    expect(third.peer?.closes).toEqual([{ code: 4409, reason: "room full" }]);
    await runInDurableObject(roomStub(roomId), (_instance: RoomDO, state) => {
      expect(state.getWebSockets()).toHaveLength(2);
    });
  });

  it("frees the slot once a peer leaves", async () => {
    const roomId = freshRoom();
    const first = await joinOrThrow(roomId);
    await joinOrThrow(roomId);

    first.socket.close(1000, "bye");
    await settle();
    const third = await join(roomId);
    await settle();

    expect(third.response.status).toBe(101);
    expect(third.peer?.closes).toEqual([]);
  });
});

describe("frame forwarding", () => {
  it("hands a binary frame to the other peer and not back to the sender", async () => {
    const roomId = freshRoom();
    const a = await joinOrThrow(roomId);
    const b = await joinOrThrow(roomId);

    a.socket.send(bytes(32, 0xab));
    await settle();

    expect(b.binary).toEqual([bytes(32, 0xab)]);
    expect(a.binary).toEqual([]);
  });

  it("forwards in both directions", async () => {
    const roomId = freshRoom();
    const a = await joinOrThrow(roomId);
    const b = await joinOrThrow(roomId);

    b.socket.send(bytes(8, 0x01));
    await settle();

    expect(a.binary).toEqual([bytes(8, 0x01)]);
    expect(b.binary).toEqual([]);
  });

  it("forwards a frame of exactly 64 KiB", async () => {
    const roomId = freshRoom();
    const a = await joinOrThrow(roomId);
    const b = await joinOrThrow(roomId);

    a.socket.send(bytes(MAX_FRAME_BYTES, 0x5a));
    await settle(200);

    expect(b.binary).toHaveLength(1);
    expect(b.binary[0]).toHaveLength(MAX_FRAME_BYTES);
    expect(a.closes).toEqual([]);
  });

  it("closes the sender of an oversize frame with 4413", async () => {
    const roomId = freshRoom();
    const a = await joinOrThrow(roomId);
    const b = await joinOrThrow(roomId);

    a.socket.send(bytes(MAX_FRAME_BYTES + 1));
    await settle(200);

    expect(a.closes).toEqual([{ code: 4413, reason: "frame too large" }]);
    expect(b.binary).toEqual([]);
  });

  it("ignores text frames from a peer", async () => {
    const roomId = freshRoom();
    const a = await joinOrThrow(roomId);
    const b = await joinOrThrow(roomId);

    a.socket.send('{"type":"peer-left"}');
    await settle();

    expect(b.text).toEqual([]);
    expect(b.binary).toEqual([]);
    expect(a.closes).toEqual([]);
  });

  it("drops a frame when the room holds only one peer", async () => {
    const roomId = freshRoom();
    const a = await joinOrThrow(roomId);

    a.socket.send(bytes(16));
    await settle();

    expect(a.binary).toEqual([]);
    expect(a.closes).toEqual([]);
  });
});

describe("peer departure", () => {
  it("tells the survivor that the peer left", async () => {
    const roomId = freshRoom();
    const a = await joinOrThrow(roomId);
    const b = await joinOrThrow(roomId);

    a.socket.close(1000, "bye");
    await settle();

    expect(b.text).toEqual(['{"type":"peer-left"}']);
    expect(b.closes).toEqual([]);
  });

  it("tells the survivor when a peer is dropped for an oversize frame", async () => {
    const roomId = freshRoom();
    const a = await joinOrThrow(roomId);
    const b = await joinOrThrow(roomId);

    a.socket.send(bytes(MAX_FRAME_BYTES + 1));
    await settle(200);

    expect(b.text).toEqual(['{"type":"peer-left"}']);
  });

  it("says nothing to a peer that is already gone", async () => {
    const roomId = freshRoom();
    const a = await joinOrThrow(roomId);
    const b = await joinOrThrow(roomId);

    a.socket.close(1000, "bye");
    await settle();
    b.socket.close(1000, "bye");
    await settle();

    expect(a.text).toEqual([]);
  });
});

describe("hibernation", () => {
  it("keeps forwarding after the room is evicted from memory", async () => {
    const roomId = freshRoom();
    const a = await joinOrThrow(roomId);
    const b = await joinOrThrow(roomId);

    await evictDurableObject(roomStub(roomId));
    a.socket.send(bytes(24, 0x3c));
    await settle(200);

    expect(b.binary).toEqual([bytes(24, 0x3c)]);
    expect(a.binary).toEqual([]);
  });

  it("still counts both peers after the room is evicted", async () => {
    const roomId = freshRoom();
    await joinOrThrow(roomId);
    await joinOrThrow(roomId);

    await evictDurableObject(roomStub(roomId));
    const third = await join(roomId);
    await settle();

    expect(third.peer?.closes).toEqual([{ code: 4409, reason: "room full" }]);
  });

  it("still tells the survivor the peer left after the room is evicted", async () => {
    const roomId = freshRoom();
    const a = await joinOrThrow(roomId);
    const b = await joinOrThrow(roomId);

    await evictDurableObject(roomStub(roomId));
    a.socket.close(1000, "bye");
    await settle(200);

    expect(b.text).toEqual(['{"type":"peer-left"}']);
  });
});

describe("idle expiry", () => {
  it("closes the room when the idle alarm fires", async () => {
    const roomId = freshRoom();
    const a = await joinOrThrow(roomId);
    const b = await joinOrThrow(roomId);

    const ran = await runDurableObjectAlarm(roomStub(roomId));
    await settle();

    expect(ran).toBe(true);
    expect(a.closes).toEqual([{ code: 4408, reason: "idle" }]);
    expect(b.closes).toEqual([{ code: 4408, reason: "idle" }]);
  });

  it("schedules the alarm as soon as the first peer joins", async () => {
    const roomId = freshRoom();
    await joinOrThrow(roomId);

    const alarm = await runInDurableObject(roomStub(roomId), (_instance: RoomDO, state) =>
      state.storage.getAlarm(),
    );

    expect(alarm).not.toBeNull();
  });
});
