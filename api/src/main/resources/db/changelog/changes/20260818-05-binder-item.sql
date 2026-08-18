--liquibase formatted sql

--changeset anthony:20260818-05-01-binder-item
--comment: order_key is deliberately NOT named order_index and is text, not a
--comment: float (A4). It holds a lexicographic fractional index; arithmetic on
--comment: it is always a bug. Float fractional indexing exhausts IEEE double
--comment: precision after ~50 inserts between the same two siblings.
CREATE TABLE binder_item (
    id                   uuid        PRIMARY KEY DEFAULT gen_random_uuid(),
    project_id           uuid        NOT NULL REFERENCES project(id) ON DELETE CASCADE,
    parent_id            uuid        REFERENCES binder_item(id) ON DELETE CASCADE,
    type                 text        NOT NULL,
    title                text        NOT NULL,
    order_key            text        NOT NULL,
    icon                 text,
    label_id             uuid        REFERENCES taxonomy(id) ON DELETE SET NULL,
    status_id            uuid        REFERENCES taxonomy(id) ON DELETE SET NULL,
    deleted_at           timestamptz,
    version              bigint      NOT NULL DEFAULT 1,
    updated_by_device_id uuid        REFERENCES device(id) ON DELETE SET NULL,
    created_at           timestamptz NOT NULL DEFAULT now(),
    updated_at           timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT binder_item_type_valid
        CHECK (type IN ('folder', 'document', 'trash')),
    CONSTRAINT binder_item_trash_is_root
        CHECK (type <> 'trash' OR parent_id IS NULL)
);

-- Sibling order must be unique and total. NULLS NOT DISTINCT (PG15+) makes this
-- hold at root level too, where parent_id is null.
CREATE UNIQUE INDEX binder_item_sibling_order_idx
    ON binder_item (project_id, parent_id, order_key) NULLS NOT DISTINCT;

-- Exactly one trash node per project; located by type, which is why project
-- carries no trash_item_id FK (that would be circular).
CREATE UNIQUE INDEX binder_item_one_trash_per_project_idx
    ON binder_item (project_id) WHERE type = 'trash';

CREATE INDEX binder_item_tree_idx ON binder_item (project_id, parent_id)
    WHERE deleted_at IS NULL;
CREATE INDEX binder_item_label_idx  ON binder_item (label_id)  WHERE label_id  IS NOT NULL;
CREATE INDEX binder_item_status_idx ON binder_item (status_id) WHERE status_id IS NOT NULL;

CREATE TRIGGER binder_item_set_updated_at BEFORE UPDATE ON binder_item
    FOR EACH ROW EXECUTE FUNCTION set_updated_at();
--rollback DROP TABLE binder_item;

--changeset anthony:20260818-05-02-binder-item-taxonomy-kind splitStatements:false
--comment: label_id must point at a 'label' and status_id at a 'status'. Not
--comment: expressible as a CHECK (cross-table), so a constraint trigger enforces it.
CREATE OR REPLACE FUNCTION binder_item_assert_taxonomy_kinds() RETURNS trigger AS $$
BEGIN
    IF NEW.label_id IS NOT NULL AND NOT EXISTS (
        SELECT 1 FROM taxonomy t
        WHERE t.id = NEW.label_id AND t.kind = 'label' AND t.project_id = NEW.project_id
    ) THEN
        RAISE EXCEPTION 'label_id % is not a label in project %', NEW.label_id, NEW.project_id;
    END IF;
    IF NEW.status_id IS NOT NULL AND NOT EXISTS (
        SELECT 1 FROM taxonomy t
        WHERE t.id = NEW.status_id AND t.kind = 'status' AND t.project_id = NEW.project_id
    ) THEN
        RAISE EXCEPTION 'status_id % is not a status in project %', NEW.status_id, NEW.project_id;
    END IF;
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

CREATE CONSTRAINT TRIGGER binder_item_taxonomy_kinds
    AFTER INSERT OR UPDATE OF label_id, status_id, project_id ON binder_item
    DEFERRABLE INITIALLY IMMEDIATE
    FOR EACH ROW EXECUTE FUNCTION binder_item_assert_taxonomy_kinds();
--rollback DROP TRIGGER IF EXISTS binder_item_taxonomy_kinds ON binder_item;
--rollback DROP FUNCTION IF EXISTS binder_item_assert_taxonomy_kinds();
