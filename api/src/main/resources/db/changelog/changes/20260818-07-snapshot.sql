--liquibase formatted sql

--changeset anthony:20260818-07-01-snapshot
--comment: point-in-time copies of document content. Not a synced entity: snapshots
--comment: are local history and deliberately absent from change_log, so they never
--comment: multiply across devices.
CREATE TABLE snapshot (
    id                   uuid        PRIMARY KEY DEFAULT gen_random_uuid(),
    document_id          uuid        NOT NULL REFERENCES document(id) ON DELETE CASCADE,
    content              jsonb       NOT NULL,
    word_count           integer     NOT NULL DEFAULT 0,
    label                text,
    is_automatic         boolean     NOT NULL DEFAULT false,
    created_by_device_id uuid        REFERENCES device(id) ON DELETE SET NULL,
    created_at           timestamptz NOT NULL DEFAULT now()
);
CREATE INDEX snapshot_document_idx ON snapshot (document_id, created_at DESC);
--rollback DROP TABLE snapshot;
