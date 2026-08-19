--liquibase formatted sql

--changeset anthony:20260819-21-01-purge-horizon
--comment: Tombstones cannot be kept forever, but they also cannot be dropped freely: a
--comment: client that was offline learns an item is gone ONLY from its delete row. Purge
--comment: one too early and that client keeps a document the author deleted, forever.
--comment:
--comment: So a project records how far its feed has been purged. A client asking for
--comment: anything at or below this point is told to resync from scratch rather than being
--comment: handed a feed with holes in it — a wasteful answer, but a correct one.
ALTER TABLE project
    ADD COLUMN change_log_purged_below bigint NOT NULL DEFAULT 0;

COMMENT ON COLUMN project.change_log_purged_below IS
    'Highest change_log id purged. A client whose cursor is below this must resync fully.';
--rollback ALTER TABLE project DROP COLUMN change_log_purged_below;

--changeset anthony:20260819-21-02-retention-indexes
--comment: Every purge is a range scan over a timestamp; without these they degrade as the
--comment: tables they are meant to keep small grow.
CREATE INDEX change_log_created_at_idx ON change_log (created_at);
CREATE INDEX binder_item_tombstone_idx ON binder_item (deleted_at)
    WHERE deleted_at IS NOT NULL;
CREATE INDEX compile_job_expiry_idx ON compile_job (expires_at)
    WHERE expires_at IS NOT NULL;
CREATE INDEX pairing_code_spent_idx ON pairing_code (expires_at);
--rollback DROP INDEX IF EXISTS change_log_created_at_idx;
--rollback DROP INDEX IF EXISTS binder_item_tombstone_idx;
--rollback DROP INDEX IF EXISTS compile_job_expiry_idx;
--rollback DROP INDEX IF EXISTS pairing_code_spent_idx;
