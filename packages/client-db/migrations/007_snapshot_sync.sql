-- 007_snapshot_sync: mirrors server migration 20260819-19.
--
-- Manual snapshots arrive from other devices; automatic ones are created locally and
-- never leave. project_id lets a client filter without joining through the binder.
ALTER TABLE snapshot ADD COLUMN project_id TEXT REFERENCES project(id) ON DELETE CASCADE;
ALTER TABLE snapshot ADD COLUMN updated_by_device_id TEXT;
ALTER TABLE snapshot ADD COLUMN version INTEGER NOT NULL DEFAULT 1;
ALTER TABLE snapshot ADD COLUMN updated_at TEXT;

CREATE INDEX snapshot_manual_idx ON snapshot (project_id, created_at DESC)
    WHERE is_automatic = 0;
