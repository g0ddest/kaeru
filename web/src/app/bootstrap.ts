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
