-- 002_sync: client-only tables. None of these exist on the server.

-- Per-project sync cursor. last_change_id is the highest server change_log.id
-- this client has durably applied; the next pull asks for everything after it.
CREATE TABLE sync_state (
    project_id      TEXT PRIMARY KEY REFERENCES project(id) ON DELETE CASCADE,
    last_change_id  INTEGER NOT NULL DEFAULT 0,
    last_synced_at  TEXT,
    last_attempt_at TEXT,
    last_error      TEXT
) STRICT;

-- Outbound queue of local edits awaiting push.
--
-- COALESCING: at most one row per (entity_type, entity_id), enforced by the
-- unique constraint. A typing session produces hundreds of saves to one document;
-- pushing each of them would be pointless, since only the final content survives.
-- On re-queue, callers MUST upsert such that:
--   * payload      is REPLACED with the latest local state
--   * base_version is PRESERVED from the original row
-- base_version is the version this client last successfully synced. Overwriting
-- it with a locally-incremented value would make every push look conflict-free
-- and silently clobber concurrent edits from another device.
CREATE TABLE pending_change (
    id            INTEGER PRIMARY KEY AUTOINCREMENT,
    project_id    TEXT NOT NULL REFERENCES project(id) ON DELETE CASCADE,
    entity_type   TEXT NOT NULL CHECK (entity_type IN (
                      'binder_item', 'document', 'taxonomy',
                      'custom_metadata_field', 'custom_metadata_value',
                      'collection', 'collection_item', 'compile_preset')),
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

CREATE INDEX pending_change_project_idx ON pending_change (project_id, id);

-- Small key/value store for client identity and sync bookkeeping.
-- NOT a credential store: tokens belong in the platform keychain
-- (Keychain on iOS, DPAPI/keyring via Tauri, IndexedDB-backed secure storage on web).
CREATE TABLE local_config (
    key        TEXT PRIMARY KEY,
    value      TEXT,
    updated_at TEXT NOT NULL
) STRICT;
