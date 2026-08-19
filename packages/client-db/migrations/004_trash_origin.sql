-- 004_trash_origin: mirrors server migration 20260819-13.
--
-- Trashing is a move to the project's trash node, not a delete, so restoring needs
-- to remember the original parent. `deleted_at` stays reserved for the tombstone
-- written when trash is emptied.
ALTER TABLE binder_item
    ADD COLUMN trashed_from_parent_id TEXT REFERENCES binder_item(id) ON DELETE SET NULL;
