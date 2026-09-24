import { describe, expect, it } from "vitest";
import { parseAllowed, whoami } from "../src/whitelist";

describe("parseAllowed", () => {
  it("reads ids separated by commas and spaces", () => {
    expect([...parseAllowed(" 1, 22 ,333 ")]).toEqual([1, 22, 333]);
  });
  it("is empty for nothing, and ignores what is not an id", () => {
    expect(parseAllowed(undefined).size).toBe(0);
    expect(parseAllowed("").size).toBe(0);
    expect([...parseAllowed("5, abc, -3, 7")]).toEqual([5, 7]);
  });
});

describe("whoami", () => {
  const answering = (status: number, body: unknown) => {
    let calls = 0;
    const fetcher = (async () => { calls += 1; return Response.json(body, { status }); }) as typeof fetch;
    return { fetcher, calls: () => calls };
  };

  it("names the owner of a token", async () => {
    const shikimori = answering(200, { id: 42, nickname: "vitaliy" });
    expect(await whoami("token-a", shikimori.fetcher, () => 0)).toEqual({ id: 42, nickname: "vitaliy" });
  });

  it("remembers the answer for ten minutes", async () => {
    const shikimori = answering(200, { id: 42, nickname: "vitaliy" });
    let now = 1_000_000;
    await whoami("token-b", shikimori.fetcher, () => now);
    now += 9 * 60 * 1000;
    await whoami("token-b", shikimori.fetcher, () => now);
    expect(shikimori.calls()).toBe(1);
    now += 2 * 60 * 1000;
    await whoami("token-b", shikimori.fetcher, () => now);
    expect(shikimori.calls()).toBe(2);
  });

  it("is null for a token Shikimori refuses", async () => {
    expect(await whoami("token-c", answering(401, {}).fetcher, () => 0)).toBeNull();
  });

  it("is unavailable when Shikimori cannot be asked", async () => {
    const broken = (async () => { throw new Error("down"); }) as typeof fetch;
    expect(await whoami("token-d", broken, () => 0)).toBe("unavailable");
    expect(await whoami("token-e", answering(503, {}).fetcher, () => 0)).toBe("unavailable");
  });
});
