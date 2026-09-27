// The shared sync vectors (shared/src/commonTest/resources/sync-vectors.json), the same file
// SyncVectorsTest runs on the shared rules, run through the web's own implementation: rules.ts for
// merge, without, newer and seed, client.ts for the wire, library/secret.ts for «украдкой». Every case
// is named by its group and name when it fails.
import { describe, expect, it } from "vitest";
import vectors from "../../../shared/src/commonTest/resources/sync-vectors.json";
import type { EpisodeProgress } from "../domain/models";
import {
  episodesToSendWhenTurnedOff,
  secretFinished,
  watchedAfterMark,
  watchedWhenTurnedOn,
  type SecretState,
} from "../library/secret";
import type { RememberedDub } from "../player/memory";
import { parseTitles, syncBody, type SyncTitle, type SyncTitles } from "./client";
import {
  EPISODES_PER_TITLE,
  MAX_DUB_TITLE,
  merge,
  newer,
  seed,
  without,
  type LocalSyncState,
  type TitleSecret,
} from "./rules";

interface Named {
  name: string;
}

interface VectorPosition {
  animeId: number;
  episode: number;
  positionMs: number;
  durationMs: number;
  at: number;
}

interface VectorLocal {
  positions: VectorPosition[];
  dubs: Record<string, { id: number; title: string | null }>;
  dubStamps: Record<string, number>;
  secrets: Record<string, SecretState>;
}

interface SyncVectors {
  constants: { episodesPerTitle: number; maxDubTitle: number };
  merge: (Named & { base: SyncTitle | null; patch: SyncTitle; expected: SyncTitle })[];
  without: (Named & { title: SyncTitle; covered: SyncTitle; expected: SyncTitle })[];
  newer: (Named & {
    remote: SyncTitles;
    local: VectorLocal;
    expected: {
      positions: VectorPosition[];
      tombstones: Record<string, number>;
      dubs: Record<string, RememberedDub>;
      dubStamps: Record<string, number>;
      secrets: TitleSecret[];
    };
  })[];
  seed: (Named & { local: VectorLocal; finished: number[]; remote: SyncTitles; expected: SyncTitles })[];
  wireParse: (Named & { text: string; expected: SyncTitles | null })[];
  wireSerialize: (Named & { titles: SyncTitles; expected: { titles: SyncTitles }; text: string })[];
  secretFinished: (Named & {
    released: boolean;
    announcedEpisodes: number;
    watched: number;
    nextEpisodeAtMs: number | null;
    nowMs: number;
    expected: boolean;
  })[];
  secretTurnedOn: (Named & { counted: number | null; expected: number })[];
  secretTurnedOff: (Named & { watched: number; counted: number | null; expected: number | null })[];
  secretAfterMark: (Named & { watched: number; episodes: number; expected: number | null })[];
}

// The inferred JSON type gives each case the keys the others have as `?: undefined`, which no map of
// episodes accepts; the shapes above are the file's, as the "about" line states them.
const cases = vectors as unknown as SyncVectors;

/** One `it` per case, so a failure names the case. Inputs are copies: nothing a case does leaks into the next. */
function each<T extends Named>(group: string, list: readonly T[], run: (c: T) => void): void {
  describe(group, () => {
    it("has cases", () => expect(list.length).toBeGreaterThan(0));
    for (const c of list) it(c.name, () => run(structuredClone(c)));
  });
}

function byId<T>(record: Record<string, T>): Map<number, T> {
  return new Map(Object.entries(record).map(([id, value]) => [Number(id), value]));
}

function progress(p: VectorPosition): EpisodeProgress {
  return { animeId: p.animeId, episode: p.episode, positionMs: p.positionMs, durationMs: p.durationMs, updatedAt: p.at };
}

function local(state: VectorLocal): LocalSyncState {
  const dubs = new Map<number, RememberedDub>();
  for (const [id, dub] of Object.entries(state.dubs)) {
    // This browser never remembers a dub without its name (player/memory.ts reads such an entry as
    // none), so a nameless one in the vectors is no dub here. Their answers come out the same.
    if (dub.title !== null) dubs.set(Number(id), { id: dub.id, title: dub.title });
  }
  return {
    positions: state.positions.map(progress),
    dubs,
    dubStamps: byId(state.dubStamps),
    secrets: byId(state.secrets),
  };
}

const byEpisode = (a: EpisodeProgress, b: EpisodeProgress): number => a.animeId - b.animeId || a.episode - b.episode;
const byTitle = (a: TitleSecret, b: TitleSecret): number => a.animeId - b.animeId;

describe("constants", () => {
  it("match the shared ones", () => {
    expect(EPISODES_PER_TITLE).toBe(cases.constants.episodesPerTitle);
    expect(MAX_DUB_TITLE).toBe(cases.constants.maxDubTitle);
  });
});

each("merge", cases.merge, (c) => {
  expect(merge(c.base ?? undefined, c.patch)).toStrictEqual(c.expected);
});

each("without", cases.without, (c) => {
  expect(without(c.title, c.covered)).toStrictEqual(c.expected);
});

each("newer", cases.newer, (c) => {
  const actual = newer(c.remote, local(c.local));
  // Positions and secrets compare without regard to order.
  expect([...actual.positions].sort(byEpisode)).toStrictEqual(c.expected.positions.map(progress).sort(byEpisode));
  expect(actual.tombstones).toStrictEqual(byId(c.expected.tombstones));
  expect(actual.dubs).toStrictEqual(byId(c.expected.dubs));
  expect(actual.dubStamps).toStrictEqual(byId(c.expected.dubStamps));
  expect([...actual.secrets].sort(byTitle)).toStrictEqual([...c.expected.secrets].sort(byTitle));
});

each("seed", cases.seed, (c) => {
  const finished = new Set(c.finished);
  expect(seed(local(c.local), (animeId) => finished.has(animeId), c.remote)).toStrictEqual(c.expected);
});

each("wireParse", cases.wireParse, (c) => {
  expect(parseTitles(c.text)).toStrictEqual(c.expected);
});

each("wireSerialize", cases.wireSerialize, (c) => {
  const body = syncBody(c.titles);
  expect(JSON.parse(body)).toStrictEqual(c.expected);
  // Kotlin writes titles and fields in an order of its own; the document is the same.
  expect(JSON.parse(c.text)).toStrictEqual(JSON.parse(body));
  // What goes out comes back as it went.
  expect(parseTitles(body)).toStrictEqual(c.titles);
});

each("secretFinished", cases.secretFinished, (c) => {
  expect(secretFinished(c.released, c.announcedEpisodes, c.watched, c.nextEpisodeAtMs, c.nowMs)).toBe(c.expected);
});

each("secretTurnedOn", cases.secretTurnedOn, (c) => {
  expect(watchedWhenTurnedOn(c.counted)).toBe(c.expected);
});

each("secretTurnedOff", cases.secretTurnedOff, (c) => {
  expect(episodesToSendWhenTurnedOff(c.watched, c.counted)).toBe(c.expected);
});

each("secretAfterMark", cases.secretAfterMark, (c) => {
  expect(watchedAfterMark(c.watched, c.episodes)).toBe(c.expected);
});
