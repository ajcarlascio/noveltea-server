-- 010_comments: mirrors server migration 20260819-24.
--
-- anchor keeps the quoted text alongside its positions, because ProseMirror positions
-- shift as the document is edited. When they no longer contain that text the comment is
-- shown as orphaned rather than moved or dropped.
CREATE TABLE comment (
    id                   TEXT PRIMARY KEY,
    project_id           TEXT NOT NULL REFERENCES project(id) ON DELETE CASCADE,
    document_id          TEXT NOT NULL REFERENCES binder_item(id) ON DELETE CASCADE,
    parent_comment_id    TEXT REFERENCES comment(id) ON DELETE CASCADE,
    author_user_id       TEXT,
    body                 TEXT NOT NULL CHECK (length(trim(body)) > 0),
    anchor               TEXT CHECK (anchor IS NULL OR json_valid(anchor)),
    resolved_at          TEXT,
    resolved_by_user_id  TEXT,
    deleted_at           TEXT,
    version              INTEGER NOT NULL DEFAULT 1,
    updated_by_device_id TEXT,
    created_at           TEXT NOT NULL,
    updated_at           TEXT NOT NULL,
    CHECK (parent_comment_id IS NULL OR anchor IS NULL)
) STRICT;

CREATE INDEX comment_document_idx ON comment (document_id, created_at) WHERE deleted_at IS NULL;
CREATE INDEX comment_open_idx ON comment (project_id) WHERE resolved_at IS NULL AND deleted_at IS NULL;
