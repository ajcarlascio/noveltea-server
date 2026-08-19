--liquibase formatted sql

--changeset anthony:20260819-16-01-project-soft-delete
--comment: Deleting a project destroys an entire novel, so it is two-step: DELETE marks
--comment: deleted_at and hides the project, and only an already-hidden project can be
--comment: permanently destroyed. The same reasoning as trash-before-tombstone on items.
ALTER TABLE project
    ADD COLUMN deleted_at timestamptz;

-- Listing only ever wants live projects.
CREATE INDEX project_owner_live_idx ON project (owner_id) WHERE deleted_at IS NULL;

COMMENT ON COLUMN project.deleted_at IS
    'Set when the owner deletes the project; it becomes invisible but recoverable until purged.';
--rollback DROP INDEX IF EXISTS project_owner_live_idx;
--rollback ALTER TABLE project DROP COLUMN deleted_at;
