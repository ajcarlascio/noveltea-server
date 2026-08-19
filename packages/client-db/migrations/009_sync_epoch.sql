-- 009_sync_epoch: mirrors server migration 20260819-23.
--
-- The client stores the epoch it last synced against and sends it on every pull. If the
-- server's has moved on, the local copy is rebuilt from scratch: the server was restored
-- from a backup and this device is holding data the server no longer has.
ALTER TABLE sync_state ADD COLUMN sync_epoch INTEGER NOT NULL DEFAULT 1;
