--liquibase formatted sql

--changeset anthony:20260828-26-01-device-platform-vocabulary
--comment: The platforms the client actually sends.
--comment:
--comment: The original CHECK allowed ('web', 'windows', 'macos', 'ios'). The client has
--comment: only ever sent ('web', 'tauri', 'ios', 'android') — Platform in
--comment: src/features/auth/api.ts — so the two lists overlapped on 'web' and 'ios' and
--comment: nothing else. AuthService silently rewrote everything it did not recognise to
--comment: 'web', which meant every desktop and every Android sign-in was recorded as a
--comment: browser. The device list exists so an author can tell their laptop from their
--comment: phone, and it could not.
--comment:
--comment: Widened rather than replaced: 'windows' and 'macos' stay valid so that no row
--comment: already written becomes invalid, and so that a future shell that wants to be
--comment: specific about which desktop it is still can be.
ALTER TABLE device DROP CONSTRAINT device_platform_valid;

ALTER TABLE device ADD CONSTRAINT device_platform_valid
    CHECK (platform IN ('web', 'tauri', 'android', 'ios', 'windows', 'macos', 'linux'));
--rollback ALTER TABLE device DROP CONSTRAINT device_platform_valid;
--rollback ALTER TABLE device ADD CONSTRAINT device_platform_valid CHECK (platform IN ('web', 'windows', 'macos', 'ios'));
