import { afterEach, describe, expect, it } from "vitest";
import { canonicalAddress, restoreDeepLink } from "./bootstrap";

// The docs/cast/404.html prelude sends /anime/1535?x#frag to /?p=%2Fanime%2F1535%3Fx#frag (web-map 5).
function openAt(url: string): string {
  window.history.replaceState(null, "", url);
  restoreDeepLink(window.location, window.history);
  return window.location.pathname + window.location.search + window.location.hash;
}

afterEach(() => {
  window.history.replaceState(null, "", "/");
});

describe("restoreDeepLink", () => {
  it("puts a deep link back in the address", () => {
    expect(openAt("/?p=%2Fanime%2F1535")).toBe("/anime/1535");
  });

  it("keeps the query of the original path, e.g. the OAuth return", () => {
    expect(openAt("/?p=%2Fauth%3Fcode%3Dabc%26state%3Dxyz")).toBe("/auth?code=abc&state=xyz");
  });

  it("keeps the fragment, which the prelude leaves outside ?p=", () => {
    expect(openAt("/?p=%2Fsearch#top")).toBe("/search#top");
  });

  it("accepts the trailing-slash form Pages redirects to", () => {
    expect(openAt("/?p=%2Fauth%2F%3Fcode%3Dabc")).toBe("/auth/?code=abc");
  });

  it.each([
    ["a protocol-relative address", "/?p=%2F%2Fevil.example%2Fx"],
    ["a backslash that browsers read as //", "/?p=%2F%5Cevil.example"],
    ["an absolute URL", "/?p=https%3A%2F%2Fevil.example%2F"],
    ["a path without the leading slash", "/?p=anime%2F1"],
  ])("ignores %s", (_name, url) => {
    expect(openAt(url)).toBe(url);
  });

  it("leaves an ordinary address alone", () => {
    expect(openAt("/search?q=1")).toBe("/search?q=1");
  });
});

describe("canonicalAddress", () => {
  // "name." is the same host to DNS but another origin to the browser: its own storage, and an OAuth
  // return address neither Shikimori nor the worker knows. A sentence-ending dot copied into a link
  // is enough to land there.
  it("drops the dot a fully qualified host name ends with, keeping path, query and fragment", () => {
    const opened = new URL("https://kaeru.vitaliy.velikodniy.name./anime/1535?x=1#top");
    expect(canonicalAddress(opened)).toBe("https://kaeru.vitaliy.velikodniy.name/anime/1535?x=1#top");
  });

  it("drops every trailing dot", () => {
    expect(canonicalAddress(new URL("https://kaeru.vitaliy.velikodniy.name../"))).toBe(
      "https://kaeru.vitaliy.velikodniy.name/",
    );
  });

  it("leaves the usual address alone", () => {
    expect(canonicalAddress(new URL("https://kaeru.vitaliy.velikodniy.name/auth?code=a"))).toBeNull();
    expect(canonicalAddress(new URL("http://localhost:5173/"))).toBeNull();
  });
});
