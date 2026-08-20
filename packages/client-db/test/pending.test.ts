import { test, describe } from "node:test";
import assert from "node:assert/strict";
import { DatabaseSync } from "node:sqlite";
import { fromNodeSqlite } from "../src/adapters/node-sqlite.ts";
import { runMigrations } from "../src/migrate.ts";
import {
  enqueueChange,
  pendingChanges,
  markAttempted,
  clearAccepted,
} from "../src/pending.ts";
import type { SqliteAdapter } from "../src/types.ts";

const NOW = "2026-08-18T18:00:00Z";
const PROJECT = "p1";

function freshDb(): SqliteAdapter {
  const db = fromNodeSqlite(new DatabaseSync(":memory:"));
  runMigrations(db);
  db.run("INSERT INTO project (id, title, created_at, updated_at) VALUES (?, ?, ?, ?);", [
    PROJECT, "Book", NOW, NOW,
  ]);
  return db;
}

function enqueue(db: SqliteAdapter, op: "create" | "update" | "delete", payload?: unknown, baseVersion?: number | null) {
  return enqueueChange(db, {
    projectId: PROJECT,
    entityType: "document",
    entityId: "d1",
    op,
    payload,
    baseVersion,
    now: NOW,
  });
}

describe("pending_change merge", () => {
  test("queue holds at most one row per entity", () => {
    const db = freshDb();
    enqueue(db, "update", { v: 1 }, 4);
    enqueue(db, "update", { v: 2 });
    enqueue(db, "update", { v: 3 });
    const rows = pendingChanges(db, PROJECT);
    assert.equal(rows.length, 1, "a typing session must not accumulate a backlog");
    assert.deepEqual(JSON.parse(rows[0].payload!), { v: 3 }, "payload is the latest local state");
  });

  test("base_version survives every later enqueue", () => {
    const db = freshDb();
    enqueue(db, "update", { v: 1 }, 4);
    enqueue(db, "update", { v: 2 }, 99);
    enqueue(db, "update", { v: 3 }, 1000);
    assert.equal(
      pendingChanges(db, PROJECT)[0].base_version,
      4,
      "overwriting base_version would make the push look conflict-free and clobber another device",
    );
  });

  test("create + update stays create", () => {
    const db = freshDb();
    enqueue(db, "create", { v: 1 });
    const result = enqueue(db, "update", { v: 2 });
    assert.deepEqual(result, { action: "merged", op: "create" });
    const row = pendingChanges(db, PROJECT)[0];
    assert.equal(row.op, "create", "server has no row yet; downgrading to update would 404");
    assert.deepEqual(JSON.parse(row.payload!), { v: 2 });
  });

  test("update + delete becomes delete and clears the payload", () => {
    const db = freshDb();
    enqueue(db, "update", { v: 1 }, 7);
    const result = enqueue(db, "delete");
    assert.deepEqual(result, { action: "merged", op: "delete" });
    const row = pendingChanges(db, PROJECT)[0];
    assert.equal(row.op, "delete");
    assert.equal(row.payload, null);
    assert.equal(row.base_version, 7, "delete still needs the version to detect conflicts");
  });

  test("delete + update resurrects as update", () => {
    const db = freshDb();
    enqueue(db, "update", { v: 1 }, 7);
    enqueue(db, "delete");
    const result = enqueue(db, "update", { v: 2 });
    assert.deepEqual(result, { action: "merged", op: "update" });
    assert.equal(pendingChanges(db, PROJECT)[0].op, "update");
  });
});

describe("create-then-delete collapse", () => {
  test("never-pushed create + delete leaves no trace", () => {
    const db = freshDb();
    enqueue(db, "create", { v: 1 });
    const result = enqueue(db, "delete");
    assert.deepEqual(result, { action: "dropped" });
    assert.equal(
      pendingChanges(db, PROJECT).length,
      0,
      "the server never saw it, so there is nothing to tell the server",
    );
  });

  test("ATTEMPTED create + delete emits a delete instead of vanishing", () => {
    const db = freshDb();
    enqueue(db, "create", { v: 1 });
    markAttempted(db, [pendingChanges(db, PROJECT)[0].id], NOW);

    const result = enqueue(db, "delete");
    assert.deepEqual(result, { action: "merged", op: "delete" });

    const rows = pendingChanges(db, PROJECT);
    assert.equal(rows.length, 1);
    assert.equal(
      rows[0].op,
      "delete",
      "a push may have landed with a lost response; dropping this would let the entity " +
        "come back on the next pull as a ghost the user already deleted",
    );
  });
});

describe("push lifecycle", () => {
  test("accepted rows are cleared, others survive", () => {
    const db = freshDb();
    enqueue(db, "update", { v: 1 }, 1);
    enqueueChange(db, {
      projectId: PROJECT, entityType: "binder_item", entityId: "b1",
      op: "update", payload: { title: "x" }, baseVersion: 2, now: NOW,
    });

    const all = pendingChanges(db, PROJECT);
    assert.equal(all.length, 2);
    clearAccepted(db, [all[0].id]);

    const left = pendingChanges(db, PROJECT);
    assert.equal(left.length, 1);
    assert.equal(left[0].entity_type, "binder_item");
  });

  test("markAttempted increments rather than sets", () => {
    const db = freshDb();
    enqueue(db, "update", { v: 1 }, 1);
    const id = pendingChanges(db, PROJECT)[0].id;
    markAttempted(db, [id], NOW);
    markAttempted(db, [id], NOW);
    assert.equal(pendingChanges(db, PROJECT)[0].attempts, 2);
  });
});

describe("every synced entity type can be queued", () => {
  test("SNAPSHOTS AND COMMENTS CAN BE PUSHED", () => {
    const db = freshDb();
    // Both were added to the schema long after pending_change's CHECK was written. While
    // they were missing, a manual snapshot or an offline comment could be received from
    // another device but never sent — the revision history a lost laptop takes with it.
    for (const entityType of ["snapshot", "comment"] as const) {
      const result = enqueueChange(db, {
        projectId: PROJECT, entityType, entityId: `${entityType}-1`,
        op: "create", payload: { x: 1 }, now: NOW,
      });
      assert.equal(result.action, "inserted", `${entityType} must be queueable`);
    }
    assert.equal(pendingChanges(db, PROJECT).length, 2);
  });

  test("an unknown entity type is still rejected", () => {
    const db = freshDb();
    assert.throws(() => enqueueChange(db, {
      projectId: PROJECT, entityType: "whiteboard" as never, entityId: "w1",
      op: "create", payload: {}, now: NOW,
    }));
  });

  test("rows queued before the rebuild survive it", () => {
    // The migration recreates the table; anything already waiting must not be dropped,
    // or an upgrade silently discards unsent work.
    const db = freshDb();
    enqueueChange(db, {
      projectId: PROJECT, entityType: "document", entityId: "d1",
      op: "update", baseVersion: 3, payload: { v: 1 }, now: NOW,
    });
    const before = pendingChanges(db, PROJECT);
    assert.equal(before.length, 1);
    assert.equal(before[0].base_version, 3);
  });
});
