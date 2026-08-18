--liquibase formatted sql

--changeset anthony:20260818-09-01-collection
--comment: saved searches. is_smart = true means query is evaluated live; false
--comment: means membership is the explicit collection_item list.
CREATE TABLE collection (
    id                   uuid        PRIMARY KEY DEFAULT gen_random_uuid(),
    project_id           uuid        NOT NULL REFERENCES project(id) ON DELETE CASCADE,
    name                 text        NOT NULL,
    query                jsonb,
    is_smart             boolean     NOT NULL DEFAULT false,
    color                text,
    order_key            text        NOT NULL,
    deleted_at           timestamptz,
    version              bigint      NOT NULL DEFAULT 1,
    updated_by_device_id uuid        REFERENCES device(id) ON DELETE SET NULL,
    created_at           timestamptz NOT NULL DEFAULT now(),
    updated_at           timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT collection_smart_has_query CHECK (NOT is_smart OR query IS NOT NULL)
);
CREATE INDEX collection_project_idx ON collection (project_id) WHERE deleted_at IS NULL;
CREATE INDEX collection_query_idx   ON collection USING gin (query jsonb_path_ops)
    WHERE query IS NOT NULL;
CREATE TRIGGER collection_set_updated_at BEFORE UPDATE ON collection
    FOR EACH ROW EXECUTE FUNCTION set_updated_at();
--rollback DROP TABLE collection;

--changeset anthony:20260818-09-02-collection-item
--comment: membership for static (non-smart) collections only.
CREATE TABLE collection_item (
    id                   uuid        PRIMARY KEY DEFAULT gen_random_uuid(),
    collection_id        uuid        NOT NULL REFERENCES collection(id) ON DELETE CASCADE,
    binder_item_id       uuid        NOT NULL REFERENCES binder_item(id) ON DELETE CASCADE,
    order_key            text        NOT NULL,
    version              bigint      NOT NULL DEFAULT 1,
    updated_by_device_id uuid        REFERENCES device(id) ON DELETE SET NULL,
    created_at           timestamptz NOT NULL DEFAULT now(),
    updated_at           timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT collection_item_unique UNIQUE (collection_id, binder_item_id)
);
CREATE UNIQUE INDEX collection_item_order_idx ON collection_item (collection_id, order_key);
CREATE INDEX collection_item_binder_idx ON collection_item (binder_item_id);
CREATE TRIGGER collection_item_set_updated_at BEFORE UPDATE ON collection_item
    FOR EACH ROW EXECUTE FUNCTION set_updated_at();
--rollback DROP TABLE collection_item;
