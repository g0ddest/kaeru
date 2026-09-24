/** Shikimori answers browsers directly (CORS *), so the API client calls it without a proxy. */
export const SHIKIMORI_URL = "https://shikimori.io";

/** The worker: OAuth token exchange with the secret, Kodik resolving, watch-together rooms. */
export const RELAY_URL = "https://kaeru-relay.vitaliy-velikodniy.workers.dev";

/** Public OAuth client id of the Kaeru app on Shikimori, the same as in infra/relay/wrangler.toml. */
export const CLIENT_ID = "_MQPkUPZ7AUhCQBBnQhdipfXDTQpBmT5JtpRByuFXeg";

/** The production site; the dev server is http://localhost:5173. */
export const SITE_ORIGIN = "https://kaeru.vitaliy.velikodniy.name";

/**
 * The OAuth return address for the page's own origin. The worker accepts exactly
 * https://kaeru.vitaliy.velikodniy.name/auth and http://localhost:5173/auth.
 */
export function redirectUri(origin: string = window.location.origin): string {
  return `${origin}/auth`;
}
