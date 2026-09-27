import { describe, expect, it } from "vitest";
import text from "../../../shared/src/commonTest/resources/sync-vectors.json?raw";
import { merge, mergeFields, parseTitles, type SyncDocument, type Title } from "../src/sync";

// The shared sync vectors, the same file SyncVectorsTest runs on the apps' rules and
// web/src/sync/vectors.test.ts on the web's, run through the worker's own code where the worker has
// the rule: `merge` and, for a POST body, `wireParse`. `without`, `newer`, `seed` and the «украдкой»
// groups are the clients' alone; the worker writes its answer as it stores it, so `wireSerialize`
// has nothing of the worker's to check either.

interface Named {
  name: string;
}

interface Vectors {
  merge: (Named & { base: Title | null; patch: Title; expected: Title })[];
  wireParse: (Named & { text: string; expected: Record<string, Title> | null })[];
}

const vectors = JSON.parse(text) as Vectors;

/** One `it` per case, so a failure names the case. */
function each<T extends Named>(group: string, cases: readonly T[], run: (c: T) => void): void {
  describe(group, () => {
    it("has cases", () => expect(cases.length).toBeGreaterThan(0));
    for (const c of cases) it(c.name, () => run(structuredClone(c)));
  });
}

each("merge", vectors.merge, (c) => {
  expect(mergeFields(c.base ?? undefined, c.patch)).toStrictEqual(c.expected);
});

/**
 * The same cases through the whole of `merge`, with `now` at 0 so that no tombstone is old enough
 * to go. On top of the field merge the worker lets a tombstone take everything stamped at or before
 * it and give way to anything after it, and drops an empty episode map (the vectors say so: «the
 * worker, not merge, drops them»); where the expected title has neither, that adds nothing.
 */
describe("merge, the whole document", () => {
  const NOW = 0;
  const plain = vectors.merge.filter(
    (c) => c.expected.gone === undefined && (c.expected.eps === undefined || Object.keys(c.expected.eps).length > 0),
  );

  it("has cases", () => expect(plain.length).toBeGreaterThan(0));
  for (const c of plain) {
    it(c.name, () => {
      const stored: SyncDocument = { v: 1, titles: c.base === null ? {} : { "5": structuredClone(c.base) } };
      const merged = merge(stored, { "5": structuredClone(c.patch) }, NOW);
      expect(merged.titles["5"]).toStrictEqual(c.expected);
    });
  }
});

/** What the worker makes of a POST body, as `handleSync` reads it: JSON first, then its own check. */
function readBody(body: string): Record<string, Title> | null {
  let raw: unknown;
  try {
    raw = JSON.parse(body);
  } catch {
    return null;
  }
  return parseTitles(raw);
}

/**
 * Where a client skips what it cannot read and keeps the rest, the worker refuses the whole body
 * with 400: a client that sent it has a bug, and nothing is half-stored. These cases describe the
 * clients' reading of an answer, not the worker's of a request; here they are only checked to be
 * refused.
 */
const REFUSED = new Set([
  "read as far as it can be, the rest skipped",
  "a secret whose on is not a boolean is skipped",
  "ids of 1-9 digits and episodes of 1-5 digits only",
  "numbers are rounded half up; strings, booleans, null and 9e15 or more are not numbers",
  "a dub needs a numeric id, a string title and a stamp",
  "an episode map with nothing readable reads as none",
]);

each("wireParse", vectors.wireParse, (c) => {
  if (REFUSED.has(c.name)) expect(readBody(c.text)).toBeNull();
  else expect(readBody(c.text)).toStrictEqual(c.expected);
});

it("refuses by name only wireParse cases that exist", () => {
  const names = new Set(vectors.wireParse.map((c) => c.name));
  for (const name of REFUSED) expect(names).toContain(name);
});
