--liquibase formatted sql

--changeset anthony:20260819-18-01-compile-next-attempt
--comment: Without this a failed job is re-claimed on the very next pass, so all its
--comment: retries are spent in milliseconds. Retrying exists for transient failures — a
--comment: database blip, a full disk — and those need time to clear, not three immediate
--comment: attempts. Null means "eligible now".
ALTER TABLE compile_job
    ADD COLUMN next_attempt_at timestamptz;

DROP INDEX IF EXISTS compile_job_claimable_idx;
CREATE INDEX compile_job_claimable_idx ON compile_job (next_attempt_at NULLS FIRST, created_at)
    WHERE status = 'queued';
--rollback DROP INDEX IF EXISTS compile_job_claimable_idx;
--rollback ALTER TABLE compile_job DROP COLUMN next_attempt_at;
--rollback CREATE INDEX compile_job_claimable_idx ON compile_job (created_at) WHERE status = 'queued';
