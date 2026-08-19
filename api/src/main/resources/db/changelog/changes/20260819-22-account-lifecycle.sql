--liquibase formatted sql

--changeset anthony:20260819-22-01-password-reset
--comment: Only the hash of a reset token is stored, for the same reason as refresh tokens
--comment: and pairing codes: a database read must not be enough to take over an account.
--comment: Single use, and expiry is enforced in SQL rather than in application code.
CREATE TABLE password_reset (
    id           uuid        PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id      uuid        NOT NULL REFERENCES app_user(id) ON DELETE CASCADE,
    token_hash   text        NOT NULL UNIQUE,
    requested_ip text,
    expires_at   timestamptz NOT NULL,
    consumed_at  timestamptz,
    created_at   timestamptz NOT NULL DEFAULT now()
);

CREATE INDEX password_reset_open_idx ON password_reset (user_id) WHERE consumed_at IS NULL;
CREATE INDEX password_reset_expiry_idx ON password_reset (expires_at);
--rollback DROP TABLE password_reset;

--changeset anthony:20260819-22-02-account-deletion
--comment: Deleting an account destroys every novel in it, so it is scheduled rather than
--comment: immediate. The grace period exists because this is the one action a person takes
--comment: in a bad moment and cannot undo — the same reasoning as trash before tombstone,
--comment: applied to the whole account.
--comment:
--comment: deletion_requested_at drives the countdown; deleted_at marks it carried out, and
--comment: blocks sign-in from that moment.
ALTER TABLE app_user
    ADD COLUMN deletion_requested_at timestamptz,
    ADD COLUMN deleted_at            timestamptz;

CREATE INDEX app_user_pending_deletion_idx ON app_user (deletion_requested_at)
    WHERE deletion_requested_at IS NOT NULL AND deleted_at IS NULL;

COMMENT ON COLUMN app_user.deletion_requested_at IS
    'When the owner asked for deletion. Cancellable until the grace period elapses.';
--rollback DROP INDEX IF EXISTS app_user_pending_deletion_idx;
--rollback ALTER TABLE app_user DROP COLUMN deletion_requested_at, DROP COLUMN deleted_at;
