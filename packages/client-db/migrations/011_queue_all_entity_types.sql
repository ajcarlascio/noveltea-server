-- 011_queue_all_entity_types: lets snapshots and comments be pushed.
--
-- pending_change's CHECK listed eight entity types and was never extended when snapshot
-- and comment were added. Queueing either failed the constraint, so a manual snapshot or
-- a comment made offline could be RECEIVED from another device but never SENT — which is
-- exactly the "a lost laptop takes its revision history with it" outcome the snapshot
-- design set out to avoid.
--
-- SQLite cannot alter a CHECK, so the table is rebuilt and its rows carried across.
ALTER TABLE pending_change RENAME TO pending_change_old;

CREATE TABLE pending_change (
    id            INTEGER PRIMARY KEY AUTOINCREMENT,
    project_id    TEXT NOT NULL REFERENCES project(id) ON DELETE CASCADE,
    entity_type   TEXT NOT NULL CHECK (entity_type IN (
                      'binder_item', 'document', 'taxonomy',
                      'custom_metadata_field', 'custom_metadata_value',
                      'collection', 'collection_item', 'compile_preset',
                      'snapshot', 'comment')),
    entity_id     TEXT NOT NULL,
    op            TEXT NOT NULL CHECK (op IN ('create', 'update', 'delete')),
    base_version  INTEGER,
    payload       TEXT CHECK (payload IS NULL OR json_valid(payload)),
    attempts      INTEGER NOT NULL DEFAULT 0,
    last_error    TEXT,
    created_at    TEXT NOT NULL,
    updated_at    TEXT NOT NULL,
    UNIQUE (entity_type, entity_id)
) STRICT;

INSERT INTO pending_change
    (id, project_id, entity_type, entity_id, op, base_version, payload, attempts,
     last_error, created_at, updated_at)
SELECT id, project_id, entity_type, entity_id, op, base_version, payload, attempts,
       last_error, created_at, updated_at
  FROM pending_change_old;

DROP TABLE pending_change_old;

CREATE INDEX pending_change_project_idx ON pending_change (project_id, id);
