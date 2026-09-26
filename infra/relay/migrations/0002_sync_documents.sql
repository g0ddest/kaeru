-- One JSON document per Shikimori user (src/sync.ts): one read and one write per save instead of a
-- statement per field. `version` guards a save against another device's save in between.
-- The four tables of 0001 are read once per user to fill the document, and dropped later.
CREATE TABLE documents (
  user INTEGER PRIMARY KEY,
  doc TEXT NOT NULL,
  version INTEGER NOT NULL
);
