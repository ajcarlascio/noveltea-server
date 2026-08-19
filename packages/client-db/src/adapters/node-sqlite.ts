import type { SqliteAdapter } from "../types.ts";

/** Shape of node:sqlite's DatabaseSync, declared structurally to avoid importing it. */
interface NodeSqliteDatabase {
  exec(sql: string): void;
  prepare(sql: string): { all(): unknown[] };
}

/**
 * Adapter for Node's built-in `node:sqlite`. Used by tests and any Node-side
 * tooling; the web and iOS clients supply their own adapters over the same
 * two-method interface.
 */
export function fromNodeSqlite(db: NodeSqliteDatabase): SqliteAdapter {
  return {
    exec: (sql) => db.exec(sql),
    query: <T>(sql: string) => db.prepare(sql).all() as T[],
  };
}
