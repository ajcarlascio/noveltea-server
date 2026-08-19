# @noveltea/client-db

SQLite schema and migration runner shared by NovelTea's offline clients (web, Tauri, iOS).

Zero dependencies and no build step: the package is TypeScript executed directly by Node's native type stripping (Node ≥ 22.6), and tests run against `node:sqlite`.

```bash
npm install          # from the repo root — this is an npm workspace
npm test -w @noveltea/client-db
```

## What this is not

It is **not** a translation of the Postgres schema. It is the subset a client needs, plus tables the server does not have. Server-owned tables — `app_user`, `device`, `project_member`, `project_invitation`, `compile_job`, `change_log` — are deliberately absent. **A client never authorises anything locally**; it renders what the server sent and queues what the user changed.

## Deliberate divergences from the server schema

| Server (Postgres) | Client (SQLite) | Why |
|---|---|---|
| `uuid` | `TEXT` | No native type. Ids stay server-generated. |
| `jsonb` | `TEXT` + `CHECK (json_valid(...))` | Closest available guarantee. |
| `timestamptz` | `TEXT`, ISO-8601 UTC | Sorts correctly as text; no timezone ambiguity. |
| `uuid[]` | JSON array in `TEXT` | No array type. |
| one sibling-order index with `NULLS NOT DISTINCT` | **two** partial unique indexes | SQLite has no `NULLS NOT DISTINCT`, so root-level rows (`parent_id IS NULL`) need their own index or they never collide. |
| generated `tsvector` + GIN | FTS5 external-content table + triggers | SQLite's equivalent. Triggers are mandatory — external-content FTS5 is not self-maintaining. |
| `change_log` (+ `tx_id`) | `sync_state` + `pending_change` | The client consumes the server's feed; it does not produce one. |
| `bigserial` | `INTEGER PRIMARY KEY AUTOINCREMENT` | Local queue ordering only, never a sync cursor. |

All tables are `STRICT`, so column types are actually enforced rather than coerced.

## Two things that will bite you

**`PRAGMA foreign_keys = ON` is per-connection, not per-database.** SQLite defaults it *off*. Any connection that forgets it silently disables every `ON DELETE CASCADE` in this schema. Call `applyConnectionPragmas(db)` on every connection you open — `runMigrations` does it for you, but only for the connection you hand it.

**`pending_change` coalesces: at most one row per `(entity_type, entity_id)`.** A typing session produces hundreds of saves to one document, and only the final content matters. When re-queueing an entity that is already pending, upsert so that:

- `payload` is **replaced** with the latest local state, and
- `base_version` is **preserved** from the original row.

`base_version` is the version this client last successfully synced. Overwriting it with a locally-incremented value makes every push look conflict-free and will silently clobber a concurrent edit from another device. The `UNIQUE` constraint stops you inserting a duplicate; it cannot stop you upserting the wrong `base_version`.

## Adding a migration

1. Add `migrations/NNN_name.sql`. Versions must be **contiguous from 1** — the bundler fails the build otherwise, because a client that skipped a release could apply migrations out of order.
2. Run `npm run generate` (also wired to `pretest` and `prepare`).
3. Add a test covering whatever constraint the migration introduces.

**Never edit a migration that has shipped.** Unlike Liquibase, there is no checksum to catch you — the client simply never re-runs it, so your change silently applies to new installs only, and the two populations diverge.

The `.sql` files are the single source of truth. iOS bundles them directly as resources; `src/generated/migrations.ts` is how web and Tauri get the same bytes without a filesystem at runtime. It is generated and gitignored — never edit it.

## Adapters

`runMigrations` takes a two-method interface (`exec`, `query`) so this package depends on no particular binding:

| Target | Binding |
|---|---|
| Tests, Node tooling | `node:sqlite` via `fromNodeSqlite()` |
| Web | wa-sqlite over OPFS |
| Tauri | `tauri-plugin-sql` or a `node:sqlite` sidecar |
| iOS | GRDB, via a thin bridge |
