import { describe, expect, it } from "vitest";
import links from "../../../android/src/test/resources/kodik/links.json?raw";
import { KodikError } from "../src/kodik/errors";
import { decodeLinks, decodeSrc } from "../src/kodik/links";

function rotate(text: string, n: number): string {
  return text.replace(/[a-z]/g, (c) => String.fromCharCode(97 + ((c.charCodeAt(0) - 97 + n) % 26)))
    .replace(/[A-Z]/g, (c) => String.fromCharCode(65 + ((c.charCodeAt(0) - 65 + n) % 26)));
}

describe("decodeLinks", () => {
  it("decodes every quality of a real /ftor answer to an https manifest", () => {
    const result = decodeLinks(links);
    expect([...result.keys()].sort((a, b) => a - b)).toEqual([360, 480, 720]);
    for (const url of result.values()) {
      expect(url.startsWith("https://")).toBe(true);
      expect(url).toContain("manifest.m3u8");
    }
  });

  it("refuses an answer with nothing decodable", () => {
    const json = JSON.stringify({ links: { 360: [{ src: "not-base64-garbage!!!" }] } });
    expect(() => decodeLinks(json)).toThrowError(KodikError);
  });
});

describe("decodeSrc", () => {
  it("round-trips a rotated base64 manifest url", () => {
    const plain = "https://example.com/stream/manifest.m3u8?sig=abc";
    const base64 = btoa(plain).replace(/=+$/, "");
    expect(decodeSrc(rotate(base64, 26 - 7))).toBe(plain);
  });
  it("answers null for garbage", () => {
    expect(decodeSrc("!!!not-valid-base64-at-all???")).toBeNull();
  });
});
