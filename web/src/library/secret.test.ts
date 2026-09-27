import { describe, expect, it } from "vitest";
import type { Anime } from "../domain/models";
import { memoryStorage } from "../test/fakes";
import { SecretStore } from "./secret";

function anime(id: number): Anime {
  return {
    id,
    title: `Аниме ${id}`,
    originalTitle: "",
    posterUrl: null,
    backdropUrl: null,
    status: "released",
    episodes: 12,
    episodesAired: 12,
    year: null,
    score: null,
    kind: null,
    studios: [],
    description: null,
    nextEpisodeAt: null,
  };
}

describe("SecretStore", () => {
  it("keeps the state and the card across instances of the same account", () => {
    const storage = memoryStorage();
    const first = new SecretStore({ storage, accountId: () => 42 });
    first.set(5, { on: true, watched: 3, at: 10 }, anime(5));
    const second = new SecretStore({ storage, accountId: () => 42 });
    expect(second.get(5)).toEqual({ on: true, watched: 3, at: 10, anime: anime(5) });
    expect([...second.all().keys()]).toEqual([5]);
  });

  it("never shows one account's secrets to another", () => {
    const storage = memoryStorage();
    let account: number | null = 42;
    const store = new SecretStore({ storage, accountId: () => account });
    store.set(5, { on: true, watched: 3, at: 10 }, anime(5));
    account = 7;
    expect(store.get(5)).toBeUndefined();
    account = null;
    expect(store.all().size).toBe(0);
    store.set(6, { on: true, watched: 1, at: 10 }, anime(6));
    account = 42;
    expect(store.get(6)).toBeUndefined();
  });

  it("keeps the known card when a later write carries none", () => {
    const store = new SecretStore({ storage: null, accountId: () => 42 });
    store.set(5, { on: true, watched: 0, at: 1 }, anime(5));
    store.set(5, { on: true, watched: 4, at: 2 });
    expect(store.get(5)?.anime).toEqual(anime(5));
    expect(store.get(5)?.watched).toBe(4);
  });

  it("tells listeners of every change and watchers only of local ones", () => {
    const store = new SecretStore({ storage: null, accountId: () => 42 });
    const heard: number[] = [];
    const watched: string[] = [];
    store.subscribe(() => heard.push(1));
    store.watch((animeId, state) => watched.push(`${animeId}:${state.on}:${state.watched}:${state.at}`));
    store.set(5, { on: true, watched: 2, at: 3 });
    store.set(6, { on: true, watched: 1, at: 4 }, null, { quiet: true });
    store.card(6, anime(6));
    expect(heard).toHaveLength(3);
    expect(watched).toEqual(["5:true:2:3"]);
    expect(store.get(6)?.anime).toEqual(anime(6));
  });

  it("reads broken storage as empty", () => {
    const storage = memoryStorage();
    storage.setItem("kaeru.secret", "{not json");
    const store = new SecretStore({ storage, accountId: () => 42 });
    expect(store.all().size).toBe(0);
    storage.setItem("kaeru.secret", JSON.stringify({ "42": { "5": { on: "yes", watched: 1, at: 1 }, x: {} } }));
    expect(new SecretStore({ storage, accountId: () => 42 }).all().size).toBe(0);
  });
});
