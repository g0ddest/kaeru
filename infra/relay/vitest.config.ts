import { cloudflareTest } from "@cloudflare/vitest-pool-workers";
import { defineConfig } from "vitest/config";

// Tests run inside workerd through Miniflare, with the bindings from wrangler.toml,
// so the Durable Object and the WebSocket hibernation API behave as they do deployed.
export default defineConfig({
  plugins: [cloudflareTest({ wrangler: { configPath: "./wrangler.toml" } })],
});
