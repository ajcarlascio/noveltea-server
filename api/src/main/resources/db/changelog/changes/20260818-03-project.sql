--liquibase formatted sql

--changeset anthony:20260818-03-01-project
--comment: owner_id is a denormalized fast path only (A6/A7). Authoritative
--comment: access is project_member, created in 20260818-11-sharing.sql.
CREATE TABLE project (
    id         uuid        PRIMARY KEY DEFAULT gen_random_uuid(),
    owner_id   uuid        NOT NULL REFERENCES app_user(id) ON DELETE RESTRICT,
    title      text        NOT NULL,
    settings   jsonb       NOT NULL DEFAULT '{}'::jsonb,
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now()
);
CREATE INDEX project_owner_idx ON project (owner_id);
CREATE TRIGGER project_set_updated_at BEFORE UPDATE ON project
    FOR EACH ROW EXECUTE FUNCTION set_updated_at();
--rollback DROP TABLE project;
