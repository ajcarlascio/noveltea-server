--liquibase formatted sql

--changeset anthony:20260827-25-01-admin-and-forced-change
--comment: Two flags a self-hosted instance cannot do without.
--comment:
--comment: is_admin is deliberately instance-wide and unrelated to project_member.role.
--comment: A project owner owns a novel; an admin owns the server, and the only thing the
--comment: two have in common is the word. Conflating them would make every project owner
--comment: able to mint accounts on someone else's machine.
--comment:
--comment: must_change_password exists because the first account on a fresh instance is
--comment: created by a machine, not a person: nobody chose its password, so it has to be
--comment: unusable for anything except choosing a real one. It is also set on every
--comment: account an admin creates or resets, where the same reasoning applies — somebody
--comment: other than the account holder knows the current password.
ALTER TABLE app_user
    ADD COLUMN is_admin             boolean NOT NULL DEFAULT false,
    ADD COLUMN must_change_password boolean NOT NULL DEFAULT false;

--comment: A guest authenticates only by redeeming an invitation and has no password_hash
--comment: at all (see app_user_guest_has_no_password), so neither flag can be true for
--comment: one: there would be nothing to change, and nothing to authenticate an admin with.
ALTER TABLE app_user
    ADD CONSTRAINT app_user_guest_is_never_admin
        CHECK (NOT is_guest OR NOT is_admin),
    ADD CONSTRAINT app_user_guest_has_no_password_to_change
        CHECK (NOT is_guest OR NOT must_change_password);

--comment: Partial, because "does this instance already have an admin?" is the question
--comment: asked once per startup and the answer is one row out of however many accounts.
CREATE INDEX app_user_admin_idx ON app_user (id)
    WHERE is_admin AND deleted_at IS NULL;

COMMENT ON COLUMN app_user.is_admin IS
    'Instance administrator: may list and create accounts. Unrelated to project_member.role.';
COMMENT ON COLUMN app_user.must_change_password IS
    'Set when someone other than the account holder chose the current password. Every API '
    'route except POST /api/v1/account/password refuses the caller until it is cleared.';
--rollback DROP INDEX IF EXISTS app_user_admin_idx;
--rollback ALTER TABLE app_user DROP CONSTRAINT app_user_guest_is_never_admin, DROP CONSTRAINT app_user_guest_has_no_password_to_change;
--rollback ALTER TABLE app_user DROP COLUMN is_admin, DROP COLUMN must_change_password;
