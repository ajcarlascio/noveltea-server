--liquibase formatted sql

--changeset anthony:20260818-10-01-compile-preset
--comment: format list per A8. md/html/txt are Core; rtf/docx/odt/epub/pdf are Pro
--comment: (A7). The constraint permits all eight because a Core install must still
--comment: be able to store and sync a preset created on a Pro install; enforcement
--comment: of what may actually be *run* is the ExportProvider's job, not the schema's.
CREATE TABLE compile_preset (
    id                    uuid        PRIMARY KEY DEFAULT gen_random_uuid(),
    project_id            uuid        NOT NULL REFERENCES project(id) ON DELETE CASCADE,
    name                  text        NOT NULL,
    format                text        NOT NULL,
    included_binder_items uuid[],
    include_query         jsonb,
    separator_rules       jsonb       NOT NULL DEFAULT '{}'::jsonb,
    title_page            jsonb,
    front_matter          jsonb,
    deleted_at            timestamptz,
    version               bigint      NOT NULL DEFAULT 1,
    updated_by_device_id  uuid        REFERENCES device(id) ON DELETE SET NULL,
    created_at            timestamptz NOT NULL DEFAULT now(),
    updated_at            timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT compile_preset_format_valid
        CHECK (format IN ('md', 'html', 'txt', 'rtf', 'docx', 'odt', 'epub', 'pdf')),
    CONSTRAINT compile_preset_has_selection
        CHECK (included_binder_items IS NOT NULL OR include_query IS NOT NULL)
);
CREATE INDEX compile_preset_project_idx ON compile_preset (project_id) WHERE deleted_at IS NULL;
CREATE TRIGGER compile_preset_set_updated_at BEFORE UPDATE ON compile_preset
    FOR EACH ROW EXECUTE FUNCTION set_updated_at();
--rollback DROP TABLE compile_preset;

--changeset anthony:20260818-10-02-compile-job
--comment: async export queue. The worker claims rows via the partial index below;
--comment: dispatch mechanism (LISTEN/NOTIFY vs polling) is still open.
CREATE TABLE compile_job (
    id                    uuid        PRIMARY KEY DEFAULT gen_random_uuid(),
    project_id            uuid        NOT NULL REFERENCES project(id) ON DELETE CASCADE,
    preset_id             uuid        REFERENCES compile_preset(id) ON DELETE SET NULL,
    inline_config         jsonb,
    format                text        NOT NULL,
    status                text        NOT NULL DEFAULT 'queued',
    requested_by_user_id  uuid        REFERENCES app_user(id) ON DELETE SET NULL,
    requested_by_device_id uuid       REFERENCES device(id) ON DELETE SET NULL,
    output_path           text,
    output_bytes          bigint,
    error_message         text,
    attempts              integer     NOT NULL DEFAULT 0,
    created_at            timestamptz NOT NULL DEFAULT now(),
    started_at            timestamptz,
    finished_at           timestamptz,
    expires_at            timestamptz,
    CONSTRAINT compile_job_status_valid
        CHECK (status IN ('queued', 'running', 'done', 'failed')),
    CONSTRAINT compile_job_format_valid
        CHECK (format IN ('md', 'html', 'txt', 'rtf', 'docx', 'odt', 'epub', 'pdf')),
    CONSTRAINT compile_job_has_config
        CHECK (preset_id IS NOT NULL OR inline_config IS NOT NULL)
);
CREATE INDEX compile_job_pending_idx ON compile_job (created_at)
    WHERE status IN ('queued', 'running');
CREATE INDEX compile_job_project_idx ON compile_job (project_id, created_at DESC);
--rollback DROP TABLE compile_job;
