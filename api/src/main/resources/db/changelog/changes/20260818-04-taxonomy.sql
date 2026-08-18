--liquibase formatted sql

--changeset anthony:20260818-04-01-taxonomy
--comment: labels and statuses share one table, distinguished by kind. Synced,
--comment: so it carries version + updated_by_device_id like any change_log entity.
CREATE TABLE taxonomy (
    id                   uuid        PRIMARY KEY DEFAULT gen_random_uuid(),
    project_id           uuid        NOT NULL REFERENCES project(id) ON DELETE CASCADE,
    kind                 text        NOT NULL,
    name                 text        NOT NULL,
    color                text,
    order_key            text        NOT NULL,
    deleted_at           timestamptz,
    version              bigint      NOT NULL DEFAULT 1,
    updated_by_device_id uuid        REFERENCES device(id) ON DELETE SET NULL,
    created_at           timestamptz NOT NULL DEFAULT now(),
    updated_at           timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT taxonomy_kind_valid  CHECK (kind IN ('label', 'status')),
    CONSTRAINT taxonomy_color_label_only CHECK (color IS NULL OR kind = 'label')
);
CREATE UNIQUE INDEX taxonomy_project_kind_name_idx
    ON taxonomy (project_id, kind, name) WHERE deleted_at IS NULL;
CREATE TRIGGER taxonomy_set_updated_at BEFORE UPDATE ON taxonomy
    FOR EACH ROW EXECUTE FUNCTION set_updated_at();
--rollback DROP TABLE taxonomy;
