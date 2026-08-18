--liquibase formatted sql

-- Sharing is a Pro feature (A7), but its tables live in Core migrations by design:
-- a licence upgrade must never require a schema migration against a live database.
-- Core simply never writes to these tables, and its SharingProvider is absent.

--changeset anthony:20260818-11-01-project-member
--comment: a null scope_binder_item_id grants the whole project; otherwise the grant
--comment: covers exactly that binder subtree. One grant per (project, user) — if
--comment: multiple disjoint subtree grants per user are ever needed, drop the unique
--comment: constraint and make permission resolution take the most permissive match.
CREATE TABLE project_member (
    id                   uuid        PRIMARY KEY DEFAULT gen_random_uuid(),
    project_id           uuid        NOT NULL REFERENCES project(id) ON DELETE CASCADE,
    user_id              uuid        NOT NULL REFERENCES app_user(id) ON DELETE CASCADE,
    role                 text        NOT NULL,
    scope_binder_item_id uuid        REFERENCES binder_item(id) ON DELETE CASCADE,
    invited_by           uuid        REFERENCES app_user(id) ON DELETE SET NULL,
    version              bigint      NOT NULL DEFAULT 1,
    updated_by_device_id uuid        REFERENCES device(id) ON DELETE SET NULL,
    created_at           timestamptz NOT NULL DEFAULT now(),
    updated_at           timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT project_member_role_valid
        CHECK (role IN ('owner', 'editor', 'commenter', 'viewer')),
    CONSTRAINT project_member_unique UNIQUE (project_id, user_id),
    CONSTRAINT project_member_owner_is_unscoped
        CHECK (role <> 'owner' OR scope_binder_item_id IS NULL)
);
CREATE INDEX project_member_user_idx  ON project_member (user_id);
CREATE INDEX project_member_scope_idx ON project_member (scope_binder_item_id)
    WHERE scope_binder_item_id IS NOT NULL;
CREATE TRIGGER project_member_set_updated_at BEFORE UPDATE ON project_member
    FOR EACH ROW EXECUTE FUNCTION set_updated_at();
--rollback DROP TABLE project_member;

--changeset anthony:20260818-11-02-project-invitation
--comment: token_hash stores only the hash of the emailed magic-link token; the
--comment: plaintext token is never persisted. Guest redemption mints an app_user
--comment: with is_guest = true and inserts the corresponding project_member row.
CREATE TABLE project_invitation (
    id                   uuid        PRIMARY KEY DEFAULT gen_random_uuid(),
    project_id           uuid        NOT NULL REFERENCES project(id) ON DELETE CASCADE,
    email                citext      NOT NULL,
    role                 text        NOT NULL,
    scope_binder_item_id uuid        REFERENCES binder_item(id) ON DELETE CASCADE,
    token_hash           text        NOT NULL UNIQUE,
    invited_by           uuid        REFERENCES app_user(id) ON DELETE SET NULL,
    expires_at           timestamptz NOT NULL,
    accepted_at          timestamptz,
    accepted_user_id     uuid        REFERENCES app_user(id) ON DELETE SET NULL,
    revoked_at           timestamptz,
    created_at           timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT project_invitation_role_valid
        CHECK (role IN ('editor', 'commenter', 'viewer')),
    CONSTRAINT project_invitation_accepted_has_user
        CHECK ((accepted_at IS NULL) = (accepted_user_id IS NULL))
);
-- At most one live invitation per (project, email).
CREATE UNIQUE INDEX project_invitation_pending_idx
    ON project_invitation (project_id, email)
    WHERE accepted_at IS NULL AND revoked_at IS NULL;
--rollback DROP TABLE project_invitation;
