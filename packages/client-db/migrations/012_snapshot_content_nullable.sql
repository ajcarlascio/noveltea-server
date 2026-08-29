-- 012_snapshot_content_nullable: the sync feed strips snapshot content to keep pages
-- small. Arriving rows therefore have no content column, and the NOT NULL constraint
-- blocks the upsert. Content is fetched on demand when the author opens the snapshot.

-- SQLite cannot ALTER a CHECK constraint, so the table must be rebuilt.
CREATE TABLE snapshot_new (
    id                   TEXT PRIMARY KEY,
    document_id          TEXT NOT NULL REFERENCES document(id) ON DELETE CASCADE,
    content              TEXT CHECK (content IS NULL OR json_valid(content)),
    word_count           INTEGER NOT NULL DEFAULT 0,
    label                TEXT,
    is_automatic         INTEGER NOT NULL DEFAULT 0 CHECK (is_automatic IN (0, 1)),
    created_by_device_id TEXT,
    created_at           TEXT NOT NULL,
    project_id           TEXT REFERENCES project(id) ON DELETE CASCADE,
    updated_by_device_id TEXT,
    version              INTEGER NOT NULL DEFAULT 1,
    updated_at           TEXT
) STRICT;

INSERT INTO snapshot_new
  SELECT id, document_id, content, word_count, label, is_automatic,
         created_by_device_id, created_at, project_id, updated_by_device_id,
         version, updated_at
    FROM snapshot;

DROP TABLE snapshot;
ALTER TABLE snapshot_new RENAME TO snapshot;

CREATE INDEX snapshot_document_idx ON snapshot (document_id, created_at DESC);
CREATE INDEX snapshot_manual_idx ON snapshot (project_id, created_at DESC)
  WHERE is_automatic = 0;
