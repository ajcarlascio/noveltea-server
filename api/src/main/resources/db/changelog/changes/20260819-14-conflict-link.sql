--liquibase formatted sql

--changeset anthony:20260819-14-01-conflict-of
--comment: Until now a conflict copy was linked to its original only by a generated
--comment: title ("... (Conflicted Copy, ...)"). That is unusable: titles are author-
--comment: editable, translatable, and ambiguous once two copies exist. The merge
--comment: editor needs a real edge.
--comment:
--comment: conflict_base_version records the version the losing client was working from,
--comment: so the merge UI can tell the author how far behind the fork was.
ALTER TABLE binder_item
    ADD COLUMN conflict_of_id       uuid REFERENCES binder_item(id) ON DELETE CASCADE,
    ADD COLUMN conflict_base_version bigint,
    ADD COLUMN conflict_created_at  timestamptz;

CREATE INDEX binder_item_conflict_of_idx ON binder_item (conflict_of_id)
    WHERE conflict_of_id IS NOT NULL;

COMMENT ON COLUMN binder_item.conflict_of_id IS
    'The item this is an unresolved conflict copy of; null for ordinary items.';
--rollback DROP INDEX IF EXISTS binder_item_conflict_of_idx;
--rollback ALTER TABLE binder_item DROP COLUMN conflict_of_id,
--rollback     DROP COLUMN conflict_base_version, DROP COLUMN conflict_created_at;
