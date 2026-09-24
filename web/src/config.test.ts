import { describe, expect, it } from "vitest";
import { CLIENT_ID, RELAY_URL, SHIKIMORI_URL, SITE_ORIGIN, redirectUri } from "./config";

describe("redirectUri", () => {
  // The worker accepts exactly these two web redirects: infra/relay/src/whitelist.ts:14-17.
  it("builds the production redirect", () => {
    expect(redirectUri(SITE_ORIGIN)).toBe("https://kaeru.vitaliy.velikodniy.name/auth");
  });

  it("builds the dev-server redirect", () => {
    expect(redirectUri("http://localhost:5173")).toBe("http://localhost:5173/auth");
  });

  it("defaults to the page's own origin", () => {
    expect(redirectUri()).toBe(`${window.location.origin}/auth`);
  });
});

describe("service addresses", () => {
  it("match the worker, its client id and the site", () => {
    expect(SHIKIMORI_URL).toBe("https://shikimori.io");
    expect(RELAY_URL).toBe("https://kaeru-relay.vitaliy-velikodniy.workers.dev");
    // infra/relay/wrangler.toml:23
    expect(CLIENT_ID).toBe("_MQPkUPZ7AUhCQBBnQhdipfXDTQpBmT5JtpRByuFXeg");
    expect(SITE_ORIGIN).toBe("https://kaeru.vitaliy.velikodniy.name");
  });
});
