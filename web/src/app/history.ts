/**
 * Whether the history entry behind this one is a page of this visit to the site, so «Назад» can go
 * back to it. BrowserRouter numbers its entries in history.state.idx from the page the site was opened
 * on. The location key cannot tell: a sign-in hands over with a replace, so the page opened from a
 * deep link carries a fresh key with Shikimori's sign-in page behind it.
 */
export function hasPageBehind(): boolean {
  const state: unknown = window.history.state;
  if (typeof state !== "object" || state === null) return false;
  const idx = (state as { idx?: unknown }).idx;
  return typeof idx === "number" && idx > 0;
}
