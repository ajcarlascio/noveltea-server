--liquibase formatted sql

--changeset anthony:20260819-17-01-compile-destination
--comment: Where a finished export goes. The author chooses per compile:
--comment:   download - written to a short-lived staging area and streamed once, then purged
--comment:   server   - written to the configured mount point and kept; the operator owns it
--comment:   cloud    - commercial destination, refused by a Core build
--comment: Kept as one column rather than three booleans so a job has exactly one answer.
ALTER TABLE compile_job
    ADD COLUMN destination     text NOT NULL DEFAULT 'download',
    ADD COLUMN output_filename text,
    ADD COLUMN word_count      integer,
    ADD COLUMN warnings        jsonb NOT NULL DEFAULT '[]'::jsonb,
    ADD CONSTRAINT compile_job_destination_valid
        CHECK (destination IN ('download', 'server', 'cloud'));

-- The worker claims jobs through this index; SKIP LOCKED needs it to stay cheap.
CREATE INDEX compile_job_claimable_idx ON compile_job (created_at)
    WHERE status = 'queued';

COMMENT ON COLUMN compile_job.warnings IS
    'What the compile refused to convert, so the author sees it with the result.';
--rollback DROP INDEX IF EXISTS compile_job_claimable_idx;
--rollback ALTER TABLE compile_job DROP CONSTRAINT compile_job_destination_valid,
--rollback     DROP COLUMN destination, DROP COLUMN output_filename,
--rollback     DROP COLUMN word_count, DROP COLUMN warnings;
