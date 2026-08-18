--liquibase formatted sql

--changeset anthony:20260818-08-01-custom-metadata-field
CREATE TABLE custom_metadata_field (
    id                   uuid        PRIMARY KEY DEFAULT gen_random_uuid(),
    project_id           uuid        NOT NULL REFERENCES project(id) ON DELETE CASCADE,
    name                 text        NOT NULL,
    field_type           text        NOT NULL,
    options              jsonb,
    order_key            text        NOT NULL,
    deleted_at           timestamptz,
    version              bigint      NOT NULL DEFAULT 1,
    updated_by_device_id uuid        REFERENCES device(id) ON DELETE SET NULL,
    created_at           timestamptz NOT NULL DEFAULT now(),
    updated_at           timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT custom_metadata_field_type_valid
        CHECK (field_type IN ('text', 'number', 'date', 'boolean', 'select')),
    CONSTRAINT custom_metadata_field_options_for_select
        CHECK (field_type = 'select' OR options IS NULL)
);
CREATE UNIQUE INDEX custom_metadata_field_project_name_idx
    ON custom_metadata_field (project_id, name) WHERE deleted_at IS NULL;
CREATE TRIGGER custom_metadata_field_set_updated_at BEFORE UPDATE ON custom_metadata_field
    FOR EACH ROW EXECUTE FUNCTION set_updated_at();
--rollback DROP TABLE custom_metadata_field;

--changeset anthony:20260818-08-02-custom-metadata-value
--comment: carries its own surrogate id rather than a composite (binder_item_id,
--comment: field_id) key, because change_log.entity_id is a single uuid column.
CREATE TABLE custom_metadata_value (
    id                   uuid        PRIMARY KEY DEFAULT gen_random_uuid(),
    binder_item_id       uuid        NOT NULL REFERENCES binder_item(id) ON DELETE CASCADE,
    field_id             uuid        NOT NULL REFERENCES custom_metadata_field(id) ON DELETE CASCADE,
    value                jsonb,
    version              bigint      NOT NULL DEFAULT 1,
    updated_by_device_id uuid        REFERENCES device(id) ON DELETE SET NULL,
    created_at           timestamptz NOT NULL DEFAULT now(),
    updated_at           timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT custom_metadata_value_unique UNIQUE (binder_item_id, field_id)
);
CREATE INDEX custom_metadata_value_field_idx ON custom_metadata_value (field_id);
CREATE TRIGGER custom_metadata_value_set_updated_at BEFORE UPDATE ON custom_metadata_value
    FOR EACH ROW EXECUTE FUNCTION set_updated_at();
--rollback DROP TABLE custom_metadata_value;
