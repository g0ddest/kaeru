// The invitation landing (docs/cast/w/index.html, served for every missing path as 404.html)
// hands non-invitation paths to the SPA through a <script data-route> prelude; restoreDeepLink
// turns the hop back into the original address. The strings Android's AssetLinksTest counts
// (android/src/test/java/app/kaeru/ui/mobile/together/AssetLinksTest.kt) are re-checked here.
import { readFileSync } from "node:fs";
import { dirname, resolve } from "node:path";
import { fileURLToPath } from "node:url";
import { describe, expect, it, vi } from "vitest";
import { restoreDeepLink } from "./bootstrap";

// docs/cast sits outside web/, where Vite refuses `?raw` imports, so the pages are read from disk.
// fileURLToPath gets the string itself: a URL object here would be jsdom's, which node:fs rejects.
const CAST = resolve(dirname(fileURLToPath(import.meta.url)), "../../../docs/cast");
const fallback = readFileSync(resolve(CAST, "404.html"), "utf8");
const landing = readFileSync(resolve(CAST, "w/index.html"), "utf8");

const ORIGIN = "https://kaeru.vitaliy.velikodniy.name";

function prelude(page: string): string {
  const found = [...page.matchAll(/<script data-route>([\s\S]*?)<\/script>/g)];
  expect(found).toHaveLength(1);
  return found[0]?.[1] ?? "";
}

// Runs the prelude against a fake location and returns where it sent the browser, if anywhere.
function hop(address: string): string | null {
  const url = new URL(address, ORIGIN);
  const replace = vi.fn<(to: string) => void>();
  const run = new Function("location", prelude(landing)) as (location: unknown) => void;
  run({ href: url.href, pathname: url.pathname, search: url.search, replace });
  expect(replace.mock.calls.length).toBeLessThanOrEqual(1);
  return replace.mock.calls[0]?.[0] ?? null;
}

// Feeds the SPA's boot step the hop address and returns the address it restores.
function restore(address: string): string | null {
  const url = new URL(address, ORIGIN);
  const replaceState = vi.fn<(data: unknown, unused: string, to?: string | URL | null) => void>();
  restoreDeepLink(
    { href: url.href, pathname: url.pathname, search: url.search, hash: url.hash } as Location,
    { replaceState } as unknown as History,
  );
  const to = replaceState.mock.calls[0]?.[2];
  return to === undefined || to === null ? null : String(to);
}

describe("the invitation landing", () => {
  it("is served byte for byte as the 404 page", () => {
    expect(fallback).toBe(landing);
  });

  it("runs the route prelude in <head>, before the landing's own script", () => {
    const at = landing.indexOf("<script data-route>");
    expect(at).toBeGreaterThan(-1);
    expect(at).toBeLessThan(landing.indexOf("</head>"));
    expect(at).toBeLessThan(landing.indexOf("<script>"));
  });

  it("keeps every string AssetLinksTest counts where it was", () => {
    const code = prelude(landing);
    for (const banned of ["location.hash", "fetch(", "cdn.", "analytics", "unpkg", '<a class="button']) {
      expect(code).not.toContain(banned);
    }
    expect(code).not.toMatch(/\bkey\b/);
    expect(landing.match(/location\.hash/g)).toHaveLength(1);
    const script = landing.split("<script>")[1]?.split("</script>")[0] ?? "";
    expect(script).toContain("fetch(");
    expect(script).toContain("/^[A-Za-z0-9_-]{22}$/.test(key)");
    const uncommented = script
      .split("\n")
      .filter((line) => !line.trimStart().startsWith("//"))
      .join("\n")
      .replace(/\/\*[\s\S]*?\*\//g, "");
    expect(uncommented.match(/\bkey\b/g)).toHaveLength(4);
  });
});

describe("the route prelude", () => {
  it.each([
    ["/anime/1535", "/?p=%2Fanime%2F1535"],
    ["/auth?code=abc&state=xyz", "/?p=%2Fauth%3Fcode%3Dabc%26state%3Dxyz"],
    ["/auth/?code=abc&state=xyz", "/?p=%2Fauth%2F%3Fcode%3Dabc%26state%3Dxyz"],
    ["/list?tab=planned#top", "/?p=%2Flist%3Ftab%3Dplanned#top"],
  ])("sends %s to the app as %s", (from, to) => {
    expect(hop(from)).toBe(to);
  });

  it.each(["/w/AAAAAAAAAAA#BBBBBBBBBBBBBBBBBBBBBB", "/w/AAAAAAAAAAA", "/w/", "/", "/?p=%2Fanime%2F1535"])(
    "leaves %s on the landing",
    (address) => {
      expect(hop(address)).toBeNull();
    },
  );

  it.each([
    "/anime/1535",
    "/auth?code=abc&state=xyz",
    "/list?tab=planned#top",
    "/search?q=%D1%84%D1%80%D0%B8%D1%80%D0%B5%D0%BD",
  ])("comes back to %s once the app restores it", (address) => {
    const via = hop(address);
    expect(via).not.toBeNull();
    expect(restore(via ?? "")).toBe(address);
  });
});
