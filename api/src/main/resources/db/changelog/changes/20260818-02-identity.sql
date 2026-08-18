--liquibase formatted sql

--changeset anthony:20260818-02-01-app-user
--comment: "user" is reserved in Postgres, hence app_user. password_hash is null
--comment: for guest accounts, which authenticate only via redeemed invitation.
CREATE TABLE app_user (
    id            uuid        PRIMARY KEY DEFAULT gen_random_uuid(),
    email         citext      NOT NULL UNIQUE,
    display_name  text,
    password_hash text,
    is_guest      boolean     NOT NULL DEFAULT false,
    created_at    timestamptz NOT NULL DEFAULT now(),
    updated_at    timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT app_user_guest_has_no_password
        CHECK (NOT is_guest OR password_hash IS NULL)
);
CREATE TRIGGER app_user_set_updated_at BEFORE UPDATE ON app_user
    FOR EACH ROW EXECUTE FUNCTION set_updated_at();
--rollback DROP TABLE app_user;

--changeset anthony:20260818-02-02-device
--comment: one row per paired client. refresh_token_hash is the stored half of
--comment: the long-lived refresh token; revoked_at supports DELETE /devices/:id.
CREATE TABLE device (
    id                 uuid        PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id            uuid        NOT NULL REFERENCES app_user(id) ON DELETE CASCADE,
    name               text        NOT NULL,
    platform           text        NOT NULL,
    last_synced_at     timestamptz,
    last_seen_change_id bigint,
    refresh_token_hash text,
    revoked_at         timestamptz,
    created_at         timestamptz NOT NULL DEFAULT now(),
    updated_at         timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT device_platform_valid
        CHECK (platform IN ('web', 'windows', 'macos', 'ios'))
);
CREATE INDEX device_user_idx ON device (user_id) WHERE revoked_at IS NULL;
CREATE TRIGGER device_set_updated_at BEFORE UPDATE ON device
    FOR EACH ROW EXECUTE FUNCTION set_updated_at();
--rollback DROP TABLE device;
