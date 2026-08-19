--liquibase formatted sql

--changeset anthony:20260819-23-01-project-sync-epoch
--comment: Lets an operator force every client to rebuild.
--comment:
--comment: Restoring a backup rewinds change_log, but devices keep cursors PAST the restored
--comment: maximum. They pull, receive nothing, and conclude they are up to date while the
--comment: server has rolled back underneath them — their local copy silently becomes the
--comment: only complete one, and nothing detects the divergence.
--comment:
--comment: change_log_purged_below cannot catch this: it only detects a cursor that is too
--comment: LOW. An epoch that a client echoes back detects a server that moved backwards.
--comment:
--comment: After restoring a backup:  UPDATE project SET sync_epoch = sync_epoch + 1;
ALTER TABLE project
    ADD COLUMN sync_epoch bigint NOT NULL DEFAULT 1;

COMMENT ON COLUMN project.sync_epoch IS
    'Bump to force every client to discard its cursor and rebuild. Required after a restore.';
--rollback ALTER TABLE project DROP COLUMN sync_epoch;
