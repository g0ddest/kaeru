import react from "@vitejs/plugin-react";
import { defineConfig } from "vitest/config";

export default defineConfig({
  // The site root: the app sits beside the Cast skin and /w/ on the same Pages host.
  base: "/",
  plugins: [react()],
  // The worker's CORS and the OAuth redirect accept only this dev origin.
  server: { port: 5173, strictPort: true, host: "localhost" },
  test: {
    environment: "jsdom",
    globals: true,
    setupFiles: ["src/test/setup.ts"],
    // Vitest blanks CSS in tests, `?raw` included; let raw CSS through so the token tests can read it.
    css: { include: [/\.css\?raw$/] },
  },
});
