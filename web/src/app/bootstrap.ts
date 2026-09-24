/**
 * GitHub Pages has no rewrites. The prelude in docs/cast/404.html sends a deep link such as
 * /anime/1535?x#frag to /?p=%2Fanime%2F1535%3Fx#frag; this puts the path back before the router
 * reads the address. Only a same-site path is accepted: "//host" and "/\host" would leave the site.
 */
export function restoreDeepLink(location: Location, history: History): void {
  const target = new URLSearchParams(location.search).get("p");
  if (target === null || !target.startsWith("/")) return;
  if (target.startsWith("//") || target.startsWith("/\\")) return;
  history.replaceState(null, "", target + location.hash);
}

/**
 * "kaeru.vitaliy.velikodniy.name." is the same site to DNS and to GitHub Pages, but another origin to
 * the browser: its own storage, a CORS origin the worker refuses and an OAuth return address Shikimori
 * does not know. A sentence-ending dot copied into a link is enough to land there. Returns the address
 * without the dot to go to instead, or null when the address is already the usual one.
 */
export function canonicalAddress(location: Pick<Location, "href" | "hostname">): string | null {
  if (!location.hostname.endsWith(".")) return null;
  const url = new URL(location.href);
  url.hostname = location.hostname.replace(/\.+$/, "");
  return url.href;
}
