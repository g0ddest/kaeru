import { applyD1Migrations, env, type D1Migration } from "cloudflare:test";

// The sync tables, created in the test database before every test file (migrations/).
await applyD1Migrations(env.SYNC_DB, (env.TEST_MIGRATIONS ?? []) as D1Migration[]);
