-- 005_conflict_link: mirrors server migration 20260819-14.
--
-- A conflict copy points at the item it forked from. Clients need this to render the
-- merge editor; matching on the generated title would be fragile and ambiguous.
ALTER TABLE binder_item ADD COLUMN conflict_of_id TEXT REFERENCES binder_item(id) ON DELETE CASCADE;
ALTER TABLE binder_item ADD COLUMN conflict_base_version INTEGER;
ALTER TABLE binder_item ADD COLUMN conflict_created_at TEXT;

CREATE INDEX binder_item_conflict_of_idx ON binder_item (conflict_of_id)
    WHERE conflict_of_id IS NOT NULL;
