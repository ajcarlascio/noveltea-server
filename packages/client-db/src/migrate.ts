import type { Migration, SqliteAdapter } from "./types.ts";
import { MIGRATIONS } from "./generated/migrations.ts";

/**
 * PRAGMAs that must be set on every connection, not once per database.
 *
 * foreign_keys defaults to OFF in SQLite for backwards compatibility, and it is
 * a per-connection setting. Forgetting it on any connection silently disables
 * every ON DELETE CASCADE in the schema.
 */
export function applyConnectionPragmas(db: SqliteAdapter): void {
  db.exec("PRAGMA foreign_keys = ON;");
  db.exec("PRAGMA busy_timeout = 5000;");
}

function ensureMigrationTable(db: SqliteAdapter): void {
  db.exec(`
    CREATE TABLE IF NOT EXISTS schema_migration (
      version    INTEGER PRIMARY KEY,
      name       TEXT    NOT NULL,
      applied_at TEXT    NOT NULL
    ) STRICT;
  `);
}

export function appliedVersions(db: SqliteAdapter): number[] {
  ensureMigrationTable(db);
  return db
    .query<{ version: number }>("SELECT version FROM schema_migration ORDER BY version;")
    .map((row) => row.version);
}

/**
 * Applies every migration the database has not yet seen, in version order, each
 * in its own transaction. SQLite DDL is transactional, so a failing migration
 * leaves no partial schema behind.
 *
 * Idempotent: running it against an up-to-date database is a no-op.
 */
export function runMigrations(
  db: SqliteAdapter,
  migrations: readonly Migration[] = MIGRATIONS,
): number[] {
  applyConnectionPragmas(db);
  const already = new Set(appliedVersions(db));
  const applied: number[] = [];

  for (const migration of [...migrations].sort((a, b) => a.version - b.version)) {
    if (already.has(migration.version)) continue;

    db.exec("BEGIN;");
    try {
      db.exec(migration.sql);
      db.run(
        "INSERT INTO schema_migration (version, name, applied_at) VALUES (?, ?, ?);",
        [migration.version, migration.name, new Date().toISOString()],
      );
      db.exec("COMMIT;");
    } catch (error) {
      db.exec("ROLLBACK;");
      throw new Error(
        `Migration ${migration.version}_${migration.name} failed: ${(error as Error).message}`,
        { cause: error },
      );
    }
    applied.push(migration.version);
  }

  return applied;
}

/** Highest migration version this package ships. */
export function targetVersion(migrations: readonly Migration[] = MIGRATIONS): number {
  return migrations.reduce((max, m) => Math.max(max, m.version), 0);
}
