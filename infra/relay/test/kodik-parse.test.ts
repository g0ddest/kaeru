import { describe, expect, it } from "vitest";
import player from "../../../android/src/test/resources/kodik/player.html?raw";
import movie from "../../../android/src/test/resources/kodik/movie.html?raw";
import addPlayers from "../../../android/src/test/resources/kodik/add-players.js?raw";
import { KodikError } from "../src/kodik/errors";
import { decodeHtmlEntities, extractPublicToken, parsePlayerPage } from "../src/kodik/parse";

// The same fixtures and the same expected values as shared/src/commonTest/.../KodikHtmlParserTest.kt:
// the TypeScript port has to read Kodik's pages exactly as the apps do.
describe("parsePlayerPage", () => {
  it("reads the signing parameters of a real player page", () => {
    const page = parsePlayerPage(player);
    expect(page.domain).toBe("kodikplayer.com");
    expect(page.dSign).toBe("7af8577bd2663bbd586a90cccaf740b83784ee5a37334b1b56363ea06d7755a8:2609140747");
    expect(page.pd).toBe("kodikplayer.com");
    expect(page.pdSign).toBe("7af8577bd2663bbd586a90cccaf740b83784ee5a37334b1b56363ea06d7755a8:2609140747");
    expect(page.ref).toBe("https://kodikplayer.com/");
    expect(page.refSign).toBe("6137eaa1d4c94e3b6a15aaf56eb92ada806bda5bbec4784915459162b3ed622b:2609140747");
  });

  it("reads the video the page is showing", () => {
    const page = parsePlayerPage(player);
    expect(page.currentType).toBe("seria");
    expect(page.currentHash).toBe("cf62e729fdb71a0b7fb148ba6fc48ad6");
    expect(page.currentId).toBe("1211482");
  });

  it("reads every translation, the first being studio 3560", () => {
    const page = parsePlayerPage(player);
    expect(page.translations).toHaveLength(33);
    expect(page.translations[0]).toMatchObject({
      id: 3560, type: "voice", episodesCount: 28, mediaId: "55917", mediaHash: "d1d44d5cd59af5af897ce899a776dacf",
    });
  });

  it("reads all 28 episodes in order", () => {
    const page = parsePlayerPage(player);
    expect(page.episodes.map((e) => e.number)).toEqual(Array.from({ length: 28 }, (_, i) => i + 1));
    expect(page.episodes[0]).toMatchObject({ mediaId: "1211482", mediaHash: "cf62e729fdb71a0b7fb148ba6fc48ad6" });
  });

  it("defaults the ftor path", () => {
    expect(parsePlayerPage(player).ftorPath).toBe("/ftor");
  });

  it("accepts a movie page with no episode list", () => {
    const page = parsePlayerPage(movie);
    expect(page.currentType).toBe("video");
    expect(page.currentId).toBe("990011");
    expect(page.currentHash).toBe("aa11bb22cc33dd44ee55ff6677889900");
    expect(page.episodes).toHaveLength(0);
    expect(page.translations).toHaveLength(33);
  });

  it("names the first missing piece of a broken page", () => {
    expect(() => parsePlayerPage("<html>nothing</html>")).toThrowError(KodikError);
    try { parsePlayerPage("<html>nothing</html>"); } catch (e) {
      expect((e as KodikError).kind).toBe("parser");
      expect((e as KodikError).step).toBe("domain");
    }
  });
});

describe("extractPublicToken", () => {
  it("finds the token in add-players", () => {
    expect(extractPublicToken(addPlayers)).toBe("0000000000000000000000000000abcd");
  });
  it("answers null when there is none", () => {
    expect(extractPublicToken("var x = 1;")).toBeNull();
  });
});

describe("decodeHtmlEntities", () => {
  it("decodes named, decimal and hex entities", () => {
    expect(decodeHtmlEntities("Tom &amp; Jerry &lt;3&gt;")).toBe("Tom & Jerry <3>");
    expect(decodeHtmlEntities("&#65;&#x41;&#X41;")).toBe("AAA");
  });
  it("leaves unknown and out-of-range entities as they are", () => {
    expect(decodeHtmlEntities("&notareal;")).toBe("&notareal;");
    expect(decodeHtmlEntities("&#x110000;")).toBe("&#x110000;");
    expect(decodeHtmlEntities("&#99999999999;")).toBe("&#99999999999;");
  });
});
