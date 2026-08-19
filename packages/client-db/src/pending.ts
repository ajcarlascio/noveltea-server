import type { ChangeOp, EntityType, PendingChange, SqliteAdapter } from "./types.ts";

export interface EnqueueInput {
  projectId: string;
  entityType: EntityType;
  entityId: string;
  op: ChangeOp;
  /** Latest local state. Ignored for deletes. */
  payload?: unknown;
  /**
   * Version this client last successfully synced for the entity, or null if the
   * server has never seen it. Only used when no row is already pending — an
   * existing row's base_version always wins.
   */
  baseVersion?: number | null;
  now?: string;
}

export type EnqueueResult =
  | { action: "inserted"; op: ChangeOp }
  | { action: "merged"; op: ChangeOp }
  | { action: "dropped" };

/**
 * Resolves the pending op for an entity after a new local edit.
 *
 * The queue holds at most one row per entity: a typing session produces hundreds
 * of saves and only the final state matters. That makes this a small state
 * machine rather than a log.
 *
 *   pending  + new     -> result
 *   create   + update  -> create   (server has never seen it; do not downgrade)
 *   create   + delete  -> nothing  (see `attempts` caveat below)
 *   update   + update  -> update
 *   update   + delete  -> delete
 *   delete   + create  -> update   (resurrected before syncing)
 *   delete   + update  -> update
 *   *        + same    -> same
 */
function mergeOp(pending: ChangeOp, next: ChangeOp): ChangeOp | null {
  if (pending === "create" && next === "update") return "create";
  if (pending === "create" && next === "delete") return null; // collapses to nothing
  if (pending === "delete" && next === "create") return "update";
  if (pending === "delete" && next === "update") return "update";
  return next;
}

/**
 * Queues a local edit, merging with any change already pending for the entity.
 *
 * Two invariants this function exists to protect:
 *
 * 1. `base_version` is NEVER overwritten by a later enqueue. It records the
 *    version this client last synced. Replacing it with a locally-incremented
 *    value makes the push look conflict-free and silently clobbers a concurrent
 *    edit from another device.
 *
 * 2. A pending `create` that is deleted before syncing collapses to nothing —
 *    but ONLY if it has never been pushed (`attempts = 0`). Once a push has been
 *    attempted, the server may have applied it and lost the response; dropping
 *    the row locally would leave an entity the user deleted, which the next pull
 *    would faithfully send back down as a ghost. In that case we emit a `delete`
 *    and let the server treat deletion of an unknown id as a no-op.
 */
export function enqueueChange(db: SqliteAdapter, input: EnqueueInput): EnqueueResult {
  const now = input.now ?? new Date().toISOString();
  const payload =
    input.op === "delete" || input.payload === undefined ? null : JSON.stringify(input.payload);

  const existing = db.query<PendingChange>(
    "SELECT * FROM pending_change WHERE entity_type = ? AND entity_id = ?;",
    [input.entityType, input.entityId],
  )[0];

  if (!existing) {
    db.run(
      `INSERT INTO pending_change
         (project_id, entity_type, entity_id, op, base_version, payload, created_at, updated_at)
       VALUES (?, ?, ?, ?, ?, ?, ?, ?);`,
      [
        input.projectId,
        input.entityType,
        input.entityId,
        input.op,
        input.baseVersion ?? null,
        payload,
        now,
        now,
      ],
    );
    return { action: "inserted", op: input.op };
  }

  const merged = mergeOp(existing.op, input.op);

  if (merged === null) {
    if (existing.attempts === 0) {
      db.run("DELETE FROM pending_change WHERE id = ?;", [existing.id]);
      return { action: "dropped" };
    }
    // Possibly already applied server-side: emit a delete instead of vanishing.
    db.run(
      "UPDATE pending_change SET op = 'delete', payload = NULL, updated_at = ? WHERE id = ?;",
      [now, existing.id],
    );
    return { action: "merged", op: "delete" };
  }

  db.run(
    // base_version deliberately absent from this SET clause.
    "UPDATE pending_change SET op = ?, payload = ?, updated_at = ? WHERE id = ?;",
    [merged, merged === "delete" ? null : payload, now, existing.id],
  );
  return { action: "merged", op: merged };
}

/** Rows awaiting push, oldest first. */
export function pendingChanges(db: SqliteAdapter, projectId: string): PendingChange[] {
  return db.query<PendingChange>(
    "SELECT * FROM pending_change WHERE project_id = ? ORDER BY id;",
    [projectId],
  );
}

/** Marks rows as in flight. Call before pushing, never after. */
export function markAttempted(db: SqliteAdapter, ids: readonly number[], now?: string): void {
  const stamp = now ?? new Date().toISOString();
  for (const id of ids) {
    db.run(
      "UPDATE pending_change SET attempts = attempts + 1, updated_at = ? WHERE id = ?;",
      [stamp, id],
    );
  }
}

/** Clears rows the server has accepted. */
export function clearAccepted(db: SqliteAdapter, ids: readonly number[]): void {
  for (const id of ids) {
    db.run("DELETE FROM pending_change WHERE id = ?;", [id]);
  }
}
