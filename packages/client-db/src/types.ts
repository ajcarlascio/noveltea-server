export interface Migration {
  readonly version: number;
  readonly name: string;
  readonly sql: string;
}

/**
 * The minimum surface every client SQLite binding can satisfy.
 *
 * Deliberately tiny so the same runner works against node:sqlite (tests, Tauri
 * sidecar), wa-sqlite/OPFS (web), and GRDB via a thin bridge (iOS), without this
 * package depending on any of them.
 */
export interface SqliteAdapter {
  /** Execute one or more statements, discarding results. */
  exec(sql: string): void;
  /** Execute a query and return all rows. */
  query<T = Record<string, unknown>>(sql: string): T[];
}
