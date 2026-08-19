import type { SqliteAdapter, SqlValue } from "../types.ts";

/** Shape of node:sqlite's DatabaseSync, declared structurally to avoid importing it. */
interface NodeSqliteDatabase {
  exec(sql: string): void;
  prepare(sql: string): {
    run(...params: unknown[]): unknown;
    all(...params: unknown[]): unknown[];
  };
}

/**
 * Adapter for Node's built-in `node:sqlite`. Used by tests and Node-side tooling;
 * the web and iOS clients supply their own adapters over the same interface.
 */
export function fromNodeSqlite(db: NodeSqliteDatabase): SqliteAdapter {
  return {
    exec: (sql) => db.exec(sql),
    run: (sql, params = []) => {
      db.prepare(sql).run(...(params as unknown[]));
    },
    query: <T>(sql: string, params: readonly SqlValue[] = []) =>
      db.prepare(sql).all(...(params as unknown[])) as T[],
  };
}
