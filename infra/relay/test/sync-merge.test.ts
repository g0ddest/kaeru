import { describe, expect, it } from "vitest";
import { EMPTY, merge, TOMBSTONE_TTL_MS, type SyncDocument } from "../src/sync";

const NOW = 1_790_000_000_000;
const doc = (titles: SyncDocument["titles"]): SyncDocument => ({ v: 1, titles });

describe("merge", () => {
  it("keeps the newer of each field, and episodes one by one", () => {
    const stored = doc({ "7": {
      dub: { id: 1, title: "A", at: 200 },
      eps: { "1": { p: 100, d: 1000, at: 100 }, "2": { p: 500, d: 1000, at: 300 } },
    } });
    const merged = merge(stored, { "7": {
      dub: { id: 2, title: "B", at: 100 },
      eps: { "1": { p: 900, d: 1000, at: 400 }, "2": { p: 50, d: 1000, at: 250 } },
    } }, NOW);
    expect(merged.titles["7"].dub).toEqual({ id: 1, title: "A", at: 200 });
    expect(merged.titles["7"].eps).toEqual({ "1": { p: 900, d: 1000, at: 400 }, "2": { p: 500, d: 1000, at: 300 } });
  });

  it("adds a title it has not seen, with its secret state", () => {
    const merged = merge(EMPTY, { "9": { secret: { on: true, watched: 3, at: 10 } } }, NOW);
    expect(merged.titles["9"]).toEqual({ secret: { on: true, watched: 3, at: 10 } });
  });

  it("drops a finished title to a tombstone that an older write cannot bring back", () => {
    const t = NOW - 10_000;
    const stored = doc({ "7": { eps: { "1": { p: 100, d: 1000, at: t + 100 } } } });
    const gone = merge(stored, { "7": { gone: t + 500 } }, NOW);
    expect(gone.titles["7"]).toEqual({ gone: t + 500 });
    const stale = merge(gone, { "7": { eps: { "2": { p: 10, d: 1000, at: t + 400 } } } }, NOW);
    expect(stale.titles["7"]).toEqual({ gone: t + 500 });
    const fresh = merge(gone, { "7": { eps: { "2": { p: 10, d: 1000, at: t + 600 } } } }, NOW);
    expect(fresh.titles["7"]).toEqual({ eps: { "2": { p: 10, d: 1000, at: t + 600 } } });
  });

  it("forgets tombstones older than thirty days", () => {
    const stored = doc({ "7": { gone: NOW - TOMBSTONE_TTL_MS - 1 }, "8": { gone: NOW - 1000 } });
    expect(Object.keys(merge(stored, {}, NOW).titles)).toEqual(["8"]);
  });

  it("keeps the thirty newest episodes of a title", () => {
    const eps: Record<string, { p: number; d: number; at: number }> = {};
    for (let n = 1; n <= 35; n += 1) eps[String(n)] = { p: 1, d: 2, at: n };
    const merged = merge(EMPTY, { "7": { eps } }, NOW);
    const kept = Object.keys(merged.titles["7"].eps ?? {}).map(Number).sort((a, b) => a - b);
    expect(kept).toEqual(Array.from({ length: 30 }, (_, i) => i + 6));
  });
});
