-- 001_core: client-side mirror of the server's project tables.
--
-- This is a SUBSET of the Postgres schema, not a translation of it. See README.md
-- for the full list of deliberate divergences. The ones visible here:
--   * uuid  -> TEXT   (SQLite has no uuid type)
--   * jsonb -> TEXT   with a json_valid() CHECK
--   * timestamptz -> TEXT, ISO-8601 in UTC ('2026-08-18T18:00:00Z')
--   * No change_log: the client consumes the server's feed, it does not produce one.
--   * Server-owned tables (app_user, device, project_member, project_invitation,
--     compile_job) are absent — the client never authorises anything locally.

CREATE TABLE project (
    id         TEXT PRIMARY KEY,
    title      TEXT NOT NULL,
    settings   TEXT NOT NULL DEFAULT '{}' CHECK (json_valid(settings)),
    created_at TEXT NOT NULL,
    updated_at TEXT NOT NULL
) STRICT;

CREATE TABLE taxonomy (
    id                   TEXT PRIMARY KEY,
    project_id           TEXT NOT NULL REFERENCES project(id) ON DELETE CASCADE,
    kind                 TEXT NOT NULL CHECK (kind IN ('label', 'status')),
    name                 TEXT NOT NULL,
    color                TEXT,
    order_key            TEXT NOT NULL,
    deleted_at           TEXT,
    version              INTEGER NOT NULL DEFAULT 1,
    updated_by_device_id TEXT,
    created_at           TEXT NOT NULL,
    updated_at           TEXT NOT NULL
) STRICT;

CREATE UNIQUE INDEX taxonomy_project_kind_name_idx
    ON taxonomy (project_id, kind, name) WHERE deleted_at IS NULL;

CREATE TABLE binder_item (
    id                   TEXT PRIMARY KEY,
    project_id           TEXT NOT NULL REFERENCES project(id) ON DELETE CASCADE,
    parent_id            TEXT REFERENCES binder_item(id) ON DELETE CASCADE,
    type                 TEXT NOT NULL CHECK (type IN ('folder', 'document', 'trash')),
    title                TEXT NOT NULL,
    -- Lexicographic fractional index. Never do arithmetic on this (A4).
    order_key            TEXT NOT NULL,
    icon                 TEXT,
    label_id             TEXT REFERENCES taxonomy(id) ON DELETE SET NULL,
    status_id            TEXT REFERENCES taxonomy(id) ON DELETE SET NULL,
    deleted_at           TEXT,
    version              INTEGER NOT NULL DEFAULT 1,
    updated_by_device_id TEXT,
    created_at           TEXT NOT NULL,
    updated_at           TEXT NOT NULL,
    CHECK (type <> 'trash' OR parent_id IS NULL)
) STRICT;

-- SQLite has no NULLS NOT DISTINCT, so the server's single sibling-order index
-- becomes two: one for nested items, one for root-level items where parent_id
-- is NULL and would otherwise never collide.
CREATE UNIQUE INDEX binder_item_sibling_order_idx
    ON binder_item (project_id, parent_id, order_key) WHERE parent_id IS NOT NULL;
CREATE UNIQUE INDEX binder_item_root_order_idx
    ON binder_item (project_id, order_key) WHERE parent_id IS NULL;

CREATE UNIQUE INDEX binder_item_one_trash_per_project_idx
    ON binder_item (project_id) WHERE type = 'trash';
CREATE INDEX binder_item_tree_idx
    ON binder_item (project_id, parent_id) WHERE deleted_at IS NULL;

CREATE TABLE document (
    id                   TEXT PRIMARY KEY REFERENCES binder_item(id) ON DELETE CASCADE,
    content              TEXT NOT NULL DEFAULT '{"type":"doc","content":[]}'
                              CHECK (json_valid(content)),
    -- Flattened plain text, produced alongside word_count. On the client this is
    -- written by the editor layer; on the server, by the compile worker.
    search_text          TEXT,
    word_count           INTEGER NOT NULL DEFAULT 0 CHECK (word_count >= 0),
    synopsis             TEXT,
    notes                TEXT,
    version              INTEGER NOT NULL DEFAULT 1,
    updated_by_device_id TEXT,
    created_at           TEXT NOT NULL,
    updated_at           TEXT NOT NULL
) STRICT;

CREATE TABLE snapshot (
    id                   TEXT PRIMARY KEY,
    document_id          TEXT NOT NULL REFERENCES document(id) ON DELETE CASCADE,
    content              TEXT NOT NULL CHECK (json_valid(content)),
    word_count           INTEGER NOT NULL DEFAULT 0,
    label                TEXT,
    is_automatic         INTEGER NOT NULL DEFAULT 0 CHECK (is_automatic IN (0, 1)),
    created_by_device_id TEXT,
    created_at           TEXT NOT NULL
) STRICT;

CREATE INDEX snapshot_document_idx ON snapshot (document_id, created_at DESC);

CREATE TABLE custom_metadata_field (
    id                   TEXT PRIMARY KEY,
    project_id           TEXT NOT NULL REFERENCES project(id) ON DELETE CASCADE,
    name                 TEXT NOT NULL,
    field_type           TEXT NOT NULL
                              CHECK (field_type IN ('text','number','date','boolean','select')),
    options              TEXT CHECK (options IS NULL OR json_valid(options)),
    order_key            TEXT NOT NULL,
    deleted_at           TEXT,
    version              INTEGER NOT NULL DEFAULT 1,
    updated_by_device_id TEXT,
    created_at           TEXT NOT NULL,
    updated_at           TEXT NOT NULL
) STRICT;

CREATE UNIQUE INDEX custom_metadata_field_project_name_idx
    ON custom_metadata_field (project_id, name) WHERE deleted_at IS NULL;

CREATE TABLE custom_metadata_value (
    id                   TEXT PRIMARY KEY,
    binder_item_id       TEXT NOT NULL REFERENCES binder_item(id) ON DELETE CASCADE,
    field_id             TEXT NOT NULL REFERENCES custom_metadata_field(id) ON DELETE CASCADE,
    value                TEXT CHECK (value IS NULL OR json_valid(value)),
    version              INTEGER NOT NULL DEFAULT 1,
    updated_by_device_id TEXT,
    created_at           TEXT NOT NULL,
    updated_at           TEXT NOT NULL,
    UNIQUE (binder_item_id, field_id)
) STRICT;

CREATE TABLE collection (
    id                   TEXT PRIMARY KEY,
    project_id           TEXT NOT NULL REFERENCES project(id) ON DELETE CASCADE,
    name                 TEXT NOT NULL,
    query                TEXT CHECK (query IS NULL OR json_valid(query)),
    is_smart             INTEGER NOT NULL DEFAULT 0 CHECK (is_smart IN (0, 1)),
    color                TEXT,
    order_key            TEXT NOT NULL,
    deleted_at           TEXT,
    version              INTEGER NOT NULL DEFAULT 1,
    updated_by_device_id TEXT,
    created_at           TEXT NOT NULL,
    updated_at           TEXT NOT NULL,
    CHECK (is_smart = 0 OR query IS NOT NULL)
) STRICT;

CREATE TABLE collection_item (
    id                   TEXT PRIMARY KEY,
    collection_id        TEXT NOT NULL REFERENCES collection(id) ON DELETE CASCADE,
    binder_item_id       TEXT NOT NULL REFERENCES binder_item(id) ON DELETE CASCADE,
    order_key            TEXT NOT NULL,
    version              INTEGER NOT NULL DEFAULT 1,
    updated_by_device_id TEXT,
    created_at           TEXT NOT NULL,
    updated_at           TEXT NOT NULL,
    UNIQUE (collection_id, binder_item_id)
) STRICT;

CREATE TABLE compile_preset (
    id                    TEXT PRIMARY KEY,
    project_id            TEXT NOT NULL REFERENCES project(id) ON DELETE CASCADE,
    name                  TEXT NOT NULL,
    format                TEXT NOT NULL CHECK (format IN
                              ('md','html','txt','rtf','docx','odt','epub','pdf')),
    -- Postgres stores uuid[]; SQLite has no array type, so this is a JSON array.
    included_binder_items TEXT CHECK (included_binder_items IS NULL
                                      OR json_valid(included_binder_items)),
    include_query         TEXT CHECK (include_query IS NULL OR json_valid(include_query)),
    separator_rules       TEXT NOT NULL DEFAULT '{}' CHECK (json_valid(separator_rules)),
    title_page            TEXT CHECK (title_page IS NULL OR json_valid(title_page)),
    front_matter          TEXT CHECK (front_matter IS NULL OR json_valid(front_matter)),
    deleted_at            TEXT,
    version               INTEGER NOT NULL DEFAULT 1,
    updated_by_device_id  TEXT,
    created_at            TEXT NOT NULL,
    updated_at            TEXT NOT NULL,
    CHECK (included_binder_items IS NOT NULL OR include_query IS NOT NULL)
) STRICT;
