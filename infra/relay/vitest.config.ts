import { cloudflareTest } from "@cloudflare/vitest-pool-workers";
import { defineConfig } from "vitest/config";

// Tests run inside workerd through Miniflare, with the bindings from wrangler.toml,
// so the Durable Object and the WebSocket hibernation API behave as they do deployed.
//
// The two OAuth values are overridden here rather than read from the deployment: the
// client id in wrangler.toml is public but irrelevant to a test, and the client secret
// only ever exists as a `wrangler secret`, so a test needs one of its own. Neither of
// these strings is a credential of anything.
export default defineConfig({
  plugins: [
    cloudflareTest({
      wrangler: { configPath: "./wrangler.toml" },
      miniflare: {
        bindings: {
          SHIKIMORI_CLIENT_ID: "test-client-id",
          SHIKIMORI_CLIENT_SECRET: "test-client-secret",
          WEB_ALLOWED_SHIKIMORI_IDS: "42, 100",
        },
      },
    }),
  ],
});
