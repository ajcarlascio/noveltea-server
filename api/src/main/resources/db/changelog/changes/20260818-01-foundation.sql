--liquibase formatted sql

-- Foundation: extensions and shared helpers.
--
-- Type convention for this schema: closed value sets are modelled as `text` with
-- a named CHECK constraint rather than native Postgres enums. Reasons:
--   1. The same schema is mirrored into client-side SQLite, which has no enums;
--      keeping the shapes aligned keeps the sync mapping trivial.
--   2. Adding a value is a constraint swap with a clean Liquibase rollback,
--      whereas ALTER TYPE ... ADD VALUE is awkward to roll back at all.
--   3. The export format list already grew once during design (A3/A8).
-- The cost is ~3 bytes/row of storage and slightly weaker type safety. Accepted.

--changeset anthony:20260818-01-01-citext
--comment: case-insensitive email storage for app_user and project_invitation
CREATE EXTENSION IF NOT EXISTS citext;
--rollback DROP EXTENSION IF EXISTS citext;

--changeset anthony:20260818-01-02-set-updated-at splitStatements:false
--comment: shared trigger function maintaining updated_at on mutation
CREATE OR REPLACE FUNCTION set_updated_at() RETURNS trigger AS $$
BEGIN
    NEW.updated_at = now();
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;
--rollback DROP FUNCTION IF EXISTS set_updated_at();
