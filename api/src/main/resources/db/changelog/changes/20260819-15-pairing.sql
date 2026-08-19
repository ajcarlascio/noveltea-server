--liquibase formatted sql

--changeset anthony:20260819-15-01-pairing-code
--comment: Device pairing. An already-trusted device mints a short human-typable code;
--comment: the new device redeems it for its own credentials. Only the hash is stored,
--comment: so a database read cannot pair a device — same reasoning as refresh tokens.
CREATE TABLE pairing_code (
    id                    uuid        PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id               uuid        NOT NULL REFERENCES app_user(id) ON DELETE CASCADE,
    code_hash             text        NOT NULL UNIQUE,
    created_by_device_id  uuid        REFERENCES device(id) ON DELETE SET NULL,
    consumed_by_device_id uuid        REFERENCES device(id) ON DELETE SET NULL,
    expires_at            timestamptz NOT NULL,
    consumed_at           timestamptz,
    created_at            timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT pairing_code_consumed_pair
        CHECK ((consumed_at IS NULL) = (consumed_by_device_id IS NULL))
);

CREATE INDEX pairing_code_open_idx ON pairing_code (user_id)
    WHERE consumed_at IS NULL;
--rollback DROP TABLE pairing_code;

--changeset anthony:20260819-15-02-device-token-metadata
--comment: refresh_token_hash already existed; these support rotation and auditing.
ALTER TABLE device
    ADD COLUMN refresh_token_issued_at timestamptz,
    ADD COLUMN refresh_token_expires_at timestamptz,
    ADD COLUMN last_seen_at timestamptz;
--rollback ALTER TABLE device DROP COLUMN refresh_token_issued_at,
--rollback     DROP COLUMN refresh_token_expires_at, DROP COLUMN last_seen_at;
