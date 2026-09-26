-- Viewing sync (src/sync.ts). Every row carries the device's own `at` (ms); a newer one wins.
CREATE TABLE positions (
  user INTEGER NOT NULL, anime INTEGER NOT NULL, episode INTEGER NOT NULL,
  p INTEGER NOT NULL, d INTEGER NOT NULL, at INTEGER NOT NULL,
  PRIMARY KEY (user, anime, episode)
);
CREATE TABLE dubs (
  user INTEGER NOT NULL, anime INTEGER NOT NULL,
  id INTEGER NOT NULL, title TEXT NOT NULL, at INTEGER NOT NULL,
  PRIMARY KEY (user, anime)
);
CREATE TABLE secrets (
  user INTEGER NOT NULL, anime INTEGER NOT NULL,
  "on" INTEGER NOT NULL, watched INTEGER NOT NULL, at INTEGER NOT NULL,
  PRIMARY KEY (user, anime)
);
-- A finished title: stands against anything written before it (30 days).
CREATE TABLE gone (
  user INTEGER NOT NULL, anime INTEGER NOT NULL, at INTEGER NOT NULL,
  PRIMARY KEY (user, anime)
);
