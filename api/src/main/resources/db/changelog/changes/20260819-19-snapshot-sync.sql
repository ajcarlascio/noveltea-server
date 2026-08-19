--liquibase formatted sql

--changeset anthony:20260819-19-01-snapshot-sync-columns
--comment: Manual snapshots sync; automatic ones stay on the device that made them.
--comment:
--comment: A snapshot is a FULL copy of a document. Syncing every autosave capture across
--comment: three devices would put hundreds of megabytes of history on a phone for prose it
--comment: may never open, and would make snapshots the highest-volume entity in a feed that
--comment: exists to be frugal. But leaving all of them local means a lost laptop loses its
--comment: entire revision history, which contradicts everything else here.
--comment:
--comment: A manual snapshot is a deliberate "keep this version" and is rare enough to copy.
--comment: is_automatic already distinguished the two; this makes it load-bearing.
--comment:
--comment: project_id is denormalised so the sync feed can be filtered without joining
--comment: through document and binder_item on every row.
ALTER TABLE snapshot
    ADD COLUMN project_id           uuid,
    ADD COLUMN updated_by_device_id uuid REFERENCES device(id) ON DELETE SET NULL,
    ADD COLUMN version              bigint NOT NULL DEFAULT 1,
    ADD COLUMN updated_at           timestamptz NOT NULL DEFAULT now();

UPDATE snapshot s
   SET project_id = b.project_id
  FROM binder_item b
 WHERE b.id = s.document_id AND s.project_id IS NULL;

DELETE FROM snapshot WHERE project_id IS NULL;

ALTER TABLE snapshot
    ALTER COLUMN project_id SET NOT NULL,
    ADD CONSTRAINT snapshot_project_fk FOREIGN KEY (project_id) REFERENCES project(id) ON DELETE CASCADE;

-- Manual snapshots are the ones that travel; the partial index keeps that read cheap.
CREATE INDEX snapshot_manual_idx ON snapshot (project_id, created_at DESC)
    WHERE is_automatic = false;

COMMENT ON COLUMN snapshot.is_automatic IS
    'Automatic captures stay on the device that made them; manual ones sync to every device.';
--rollback DROP INDEX IF EXISTS snapshot_manual_idx;
--rollback ALTER TABLE snapshot DROP CONSTRAINT snapshot_project_fk,
--rollback     DROP COLUMN project_id, DROP COLUMN updated_by_device_id,
--rollback     DROP COLUMN version, DROP COLUMN updated_at;

--changeset anthony:20260819-19-02-change-log-snapshot
--comment: Only manual snapshots ever produce a row here, but the type must be permitted.
ALTER TABLE change_log DROP CONSTRAINT change_log_entity_type_valid;
ALTER TABLE change_log ADD CONSTRAINT change_log_entity_type_valid
    CHECK (entity_type IN (
        'binder_item', 'document', 'taxonomy', 'custom_metadata_field',
        'custom_metadata_value', 'collection', 'collection_item', 'compile_preset',
        'project_member', 'snapshot'));
--rollback ALTER TABLE change_log DROP CONSTRAINT change_log_entity_type_valid;
--rollback ALTER TABLE change_log ADD CONSTRAINT change_log_entity_type_valid
--rollback     CHECK (entity_type IN ('binder_item','document','taxonomy','custom_metadata_field',
--rollback     'custom_metadata_value','collection','collection_item','compile_preset','project_member'));
