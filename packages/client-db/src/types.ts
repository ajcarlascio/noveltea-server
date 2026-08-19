export interface Migration {
  readonly version: number;
  readonly name: string;
  readonly sql: string;
}

export type SqlValue = string | number | bigint | null | Uint8Array;

/**
 * The minimum surface every client SQLite binding can satisfy.
 *
 * Deliberately tiny so the same code works against node:sqlite (tests, Tauri
 * sidecar), wa-sqlite/OPFS (web), and GRDB via a thin bridge (iOS), without this
 * package depending on any of them.
 *
 * `run` and `query` take bound parameters. Document payloads are arbitrary
 * user-authored JSON and must never be interpolated into SQL text.
 */
export interface SqliteAdapter {
  /** Execute one or more statements with no parameters, discarding results. */
  exec(sql: string): void;
  /** Execute a single parameterised statement, discarding results. */
  run(sql: string, params?: readonly SqlValue[]): void;
  /** Execute a single parameterised query and return all rows. */
  query<T = Record<string, unknown>>(sql: string, params?: readonly SqlValue[]): T[];
}

export type ChangeOp = "create" | "update" | "delete";

export type EntityType =
  | "binder_item"
  | "document"
  | "taxonomy"
  | "custom_metadata_field"
  | "custom_metadata_value"
  | "collection"
  | "collection_item"
  | "compile_preset";

export interface PendingChange {
  id: number;
  project_id: string;
  entity_type: EntityType;
  entity_id: string;
  op: ChangeOp;
  base_version: number | null;
  payload: string | null;
  attempts: number;
  last_error: string | null;
  created_at: string;
  updated_at: string;
}
