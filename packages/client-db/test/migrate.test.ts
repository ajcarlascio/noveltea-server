import { test, describe } from "node:test";
import assert from "node:assert/strict";
import { DatabaseSync } from "node:sqlite";
import { fromNodeSqlite } from "../src/adapters/node-sqlite.ts";
import { runMigrations, appliedVersions, targetVersion } from "../src/migrate.ts";

const NOW = "2026-08-18T18:00:00Z";

function freshDb() {
  const raw = new DatabaseSync(":memory:");
  const db = fromNodeSqlite(raw);
  runMigrations(db);
  return db;
}

function seedProject(db: ReturnType<typeof fromNodeSqlite>, id = "p1") {
  db.exec(
    `INSERT INTO project (id, title, created_at, updated_at)
     VALUES ('${id}', 'Book', '${NOW}', '${NOW}');`,
  );
  return id;
}

function insertItem(
  db: ReturnType<typeof fromNodeSqlite>,
  opts: { id: string; project?: string; parent?: string | null; type?: string; orderKey: string },
) {
  const parent = opts.parent ? `'${opts.parent}'` : "NULL";
  db.exec(
    `INSERT INTO binder_item (id, project_id, parent_id, type, title, order_key, created_at, updated_at)
     VALUES ('${opts.id}', '${opts.project ?? "p1"}', ${parent}, '${opts.type ?? "document"}',
             '${opts.id}', '${opts.orderKey}', '${NOW}', '${NOW}');`,
  );
}

describe("migrations", () => {
  test("apply cleanly from empty and report versions", () => {
    const raw = new DatabaseSync(":memory:");
    const db = fromNodeSqlite(raw);
    const applied = runMigrations(db);
    assert.deepEqual(applied, [1, 2, 3]);
    assert.deepEqual(appliedVersions(db), [1, 2, 3]);
    assert.equal(targetVersion(), 3);
  });

  test("are idempotent", () => {
    const db = freshDb();
    assert.deepEqual(runMigrations(db), [], "second run must apply nothing");
  });

  test("create every expected table", () => {
    const db = freshDb();
    const names = db
      .query<{ name: string }>("SELECT name FROM sqlite_master WHERE type='table' ORDER BY name;")
      .map((r) => r.name);
    for (const expected of [
      "binder_item", "collection", "collection_item", "compile_preset",
      "custom_metadata_field", "custom_metadata_value", "document", "local_config",
      "pending_change", "project", "schema_migration", "snapshot", "sync_state", "taxonomy",
    ]) {
      assert.ok(names.includes(expected), `missing table: ${expected}`);
    }
  });
});

describe("constraints", () => {
  test("foreign keys are enforced", () => {
    const db = freshDb();
    assert.throws(
      () => insertItem(db, { id: "b1", project: "nonexistent", orderKey: "a0" }),
      /FOREIGN KEY/i,
      "PRAGMA foreign_keys must be ON — without it every ON DELETE CASCADE is inert",
    );
  });

  test("root-level siblings cannot share an order_key", () => {
    const db = freshDb();
    seedProject(db);
    insertItem(db, { id: "b1", parent: null, orderKey: "a0" });
    assert.throws(
      () => insertItem(db, { id: "b2", parent: null, orderKey: "a0" }),
      /UNIQUE/i,
      "SQLite lacks NULLS NOT DISTINCT; the root partial index must cover this",
    );
  });

  test("nested siblings cannot share an order_key", () => {
    const db = freshDb();
    seedProject(db);
    insertItem(db, { id: "f1", parent: null, type: "folder", orderKey: "a0" });
    insertItem(db, { id: "b1", parent: "f1", orderKey: "a0" });
    assert.throws(() => insertItem(db, { id: "b2", parent: "f1", orderKey: "a0" }), /UNIQUE/i);
  });

  test("only one trash node per project", () => {
    const db = freshDb();
    seedProject(db);
    insertItem(db, { id: "t1", parent: null, type: "trash", orderKey: "a0" });
    assert.throws(() => insertItem(db, { id: "t2", parent: null, type: "trash", orderKey: "a1" }), /UNIQUE/i);
  });

  test("invalid JSON is rejected", () => {
    const db = freshDb();
    assert.throws(
      () => db.exec(`INSERT INTO project (id, title, settings, created_at, updated_at)
                     VALUES ('p9','Bad','{not json','${NOW}','${NOW}');`),
      /CHECK/i,
    );
  });

  test("STRICT tables reject wrong column types", () => {
    const db = freshDb();
    seedProject(db);
    insertItem(db, { id: "b1", parent: null, orderKey: "a0" });
    assert.throws(
      () => db.exec(`INSERT INTO document (id, word_count, created_at, updated_at)
                     VALUES ('b1', 'not-a-number', '${NOW}', '${NOW}');`),
      /cannot store TEXT value in INTEGER column/i,
    );
  });

  test("pending_change coalesces to one row per entity", () => {
    const db = freshDb();
    seedProject(db);
    db.exec(`INSERT INTO pending_change (project_id, entity_type, entity_id, op, base_version, created_at, updated_at)
             VALUES ('p1','document','d1','update',4,'${NOW}','${NOW}');`);
    assert.throws(
      () => db.exec(`INSERT INTO pending_change (project_id, entity_type, entity_id, op, base_version, created_at, updated_at)
                     VALUES ('p1','document','d1','update',9,'${NOW}','${NOW}');`),
      /UNIQUE/i,
      "re-queueing must upsert, preserving the original base_version",
    );
  });
});

describe("full-text search", () => {
  test("indexes documents and follows updates and deletes", () => {
    const db = freshDb();
    seedProject(db);
    insertItem(db, { id: "b1", parent: null, orderKey: "a0" });
    db.exec(`INSERT INTO document (id, search_text, created_at, updated_at)
             VALUES ('b1', 'the lighthouse keeper waited', '${NOW}', '${NOW}');`);

    const hits = db.query<{ c: number }>(
      "SELECT count(*) AS c FROM document_fts WHERE document_fts MATCH 'lighthouse';",
    );
    assert.equal(hits[0].c, 1, "insert trigger must populate the FTS index");

    db.exec("UPDATE document SET search_text = 'the harbour master waited' WHERE id = 'b1';");
    assert.equal(
      db.query<{ c: number }>("SELECT count(*) AS c FROM document_fts WHERE document_fts MATCH 'lighthouse';")[0].c,
      0,
      "update trigger must retract the old terms",
    );
    assert.equal(
      db.query<{ c: number }>("SELECT count(*) AS c FROM document_fts WHERE document_fts MATCH 'harbour';")[0].c,
      1,
    );

    db.exec("DELETE FROM document WHERE id = 'b1';");
    assert.equal(
      db.query<{ c: number }>("SELECT count(*) AS c FROM document_fts WHERE document_fts MATCH 'harbour';")[0].c,
      0,
      "delete trigger must retract the row",
    );
  });
});
