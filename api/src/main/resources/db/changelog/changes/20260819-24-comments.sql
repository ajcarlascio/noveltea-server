--liquibase formatted sql

--changeset anthony:20260819-24-01-comment
--comment: Comments and annotations on a document.
--comment:
--comment: anchor holds where in the prose the comment points: {from, to, quotedText}.
--comment: ProseMirror positions shift as the document is edited, so the quoted text is
--comment: stored alongside them — when the positions no longer contain that text the
--comment: comment is reported as orphaned rather than deleted or silently relocated.
--comment: Losing an editor's note because the sentence moved would be the same class of
--comment: mistake as losing the sentence.
--comment:
--comment: author_user_id is never taken from a request payload; it is the authenticated
--comment: caller, or a client could forge who said what.
CREATE TABLE comment (
    id                   uuid        PRIMARY KEY DEFAULT gen_random_uuid(),
    project_id           uuid        NOT NULL REFERENCES project(id) ON DELETE CASCADE,
    document_id          uuid        NOT NULL REFERENCES binder_item(id) ON DELETE CASCADE,
    parent_comment_id    uuid        REFERENCES comment(id) ON DELETE CASCADE,
    author_user_id       uuid        REFERENCES app_user(id) ON DELETE SET NULL,
    body                 text        NOT NULL,
    anchor               jsonb,
    resolved_at          timestamptz,
    resolved_by_user_id  uuid        REFERENCES app_user(id) ON DELETE SET NULL,
    deleted_at           timestamptz,
    version              bigint      NOT NULL DEFAULT 1,
    updated_by_device_id uuid        REFERENCES device(id) ON DELETE SET NULL,
    created_at           timestamptz NOT NULL DEFAULT now(),
    updated_at           timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT comment_body_not_empty CHECK (length(btrim(body)) > 0),
    CONSTRAINT comment_resolved_pair CHECK ((resolved_at IS NULL) = (resolved_by_user_id IS NULL)),
    -- A reply inherits its thread's anchor; only a top-level comment carries one.
    CONSTRAINT comment_reply_has_no_anchor CHECK (parent_comment_id IS NULL OR anchor IS NULL)
);

CREATE INDEX comment_document_idx ON comment (document_id, created_at)
    WHERE deleted_at IS NULL;
CREATE INDEX comment_thread_idx ON comment (parent_comment_id) WHERE parent_comment_id IS NOT NULL;
CREATE INDEX comment_open_idx ON comment (project_id) WHERE resolved_at IS NULL AND deleted_at IS NULL;

CREATE TRIGGER comment_set_updated_at BEFORE UPDATE ON comment
    FOR EACH ROW EXECUTE FUNCTION set_updated_at();
--rollback DROP TABLE comment;

--changeset anthony:20260819-24-02-change-log-comment
ALTER TABLE change_log DROP CONSTRAINT change_log_entity_type_valid;
ALTER TABLE change_log ADD CONSTRAINT change_log_entity_type_valid
    CHECK (entity_type IN (
        'binder_item', 'document', 'taxonomy', 'custom_metadata_field',
        'custom_metadata_value', 'collection', 'collection_item', 'compile_preset',
        'project_member', 'snapshot', 'comment'));
--rollback ALTER TABLE change_log DROP CONSTRAINT change_log_entity_type_valid;
--rollback ALTER TABLE change_log ADD CONSTRAINT change_log_entity_type_valid
--rollback     CHECK (entity_type IN ('binder_item','document','taxonomy','custom_metadata_field',
--rollback     'custom_metadata_value','collection','collection_item','compile_preset',
--rollback     'project_member','snapshot'));
