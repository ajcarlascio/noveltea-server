--liquibase formatted sql

--changeset anthony:20260819-13-01-trashed-from-parent
--comment: Trashing is a MOVE, not a delete. The item is reparented to the project's
--comment: trash node and keeps syncing normally; `deleted_at` is reserved for the
--comment: tombstone written when the trash is emptied. Restoring therefore needs to
--comment: know where the item came from, which nothing recorded until now.
--comment:
--comment: Nullable and unset for anything not in the trash.
ALTER TABLE binder_item
    ADD COLUMN trashed_from_parent_id uuid REFERENCES binder_item(id) ON DELETE SET NULL;

COMMENT ON COLUMN binder_item.trashed_from_parent_id IS
    'Parent to restore to when this item is recovered from trash; null unless trashed.';
--rollback ALTER TABLE binder_item DROP COLUMN trashed_from_parent_id;
