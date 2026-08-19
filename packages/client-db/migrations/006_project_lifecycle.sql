-- 006_project_lifecycle: mirrors server migration 20260819-16.
--
-- A deleted project is hidden but recoverable until purged, so clients need to know
-- the difference rather than dropping the rows outright.
ALTER TABLE project ADD COLUMN deleted_at TEXT;
