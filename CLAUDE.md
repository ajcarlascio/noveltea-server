# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Project status

Greenfield — nothing is scaffolded yet. The design of record is `docs/design/v1-data-model-api.md`. The commands and module paths below describe the *intended* layout; verify they exist before relying on them.

## What this is

NovelTea is a self-hosted, offline-first long-form writing app, Scrivener-shaped: a binder tree, document snapshots, labels/statuses, custom metadata, saved/smart collections, and compile presets. Clients are web, Tauri (Windows/macOS), and iOS. Every client keeps a full local replica and works fully disconnected.

Copyright Anthony Carlascio. This repo is the **open core** under Elastic License 2.0; commercial features live in a separate private repo (see Editions).

## Editions — this repo is not the whole product

| | Core (this repo, ELv2) | Pro (private repo, proprietary) |
|---|---|---|
| Binder, editor, snapshots, metadata, collections | ✅ | |
| Multi-device sync | ✅ | |
| Export | `md` `html` `txt` | `rtf` `docx` `odt` `epub` `pdf` |
| Sharing, invitations, guest access, roles | ❌ | ✅ |

**Paid functionality must never be committed to this repository**, not even disabled or feature-flagged. The gate is architectural, not legal — code that ships here is code a self-hoster may legitimately run. Pro ships as separate Spring and Node modules that the core loads if present.

Extension points the core owns (and must keep working when nothing implements them):

- `ExportProvider` — core registers `md`/`html`/`txt`; Pro registers the rest. Unimplemented formats return `501` with an upgrade pointer, never a stack trace.
- `SharingProvider` — absent in core. All `/members`, `/invitations` routes return `501`. **Sync must work with no provider present**, taking the single-owner path.

When adding a feature, decide its edition *before* writing it. Retrofitting an extension point around code already merged here is the expensive path.

## Architecture: two runtimes, deliberately

- **API server — Java / Spring Boot.** Auth, binder tree, documents, metadata, sharing, sync. All persistence and *all* authorization decisions live here.
- **Compile worker — Node / TypeScript.** Everything that must parse ProseMirror document JSON: word counts and every export format.

**The boundary rule: the JVM never interprets document structure.** Spring stores `document.content` as opaque `jsonb` and hands it to the worker. If you find yourself walking ProseMirror nodes in Java, the logic belongs in the worker instead.

## Export stack — no Pandoc

Pandoc is GPL and cannot ship inside a proprietary Pro module. It was also never necessary: Pandoc solves an N×M format matrix, and this is 1×5 from a clean structured source. Everything is serialization, not parsing.

| Format | Implementation | Edition |
|---|---|---|
| HTML | `prosemirror-model` `DOMSerializer` | Core |
| MD / TXT | `prosemirror-markdown` | Core |
| DOCX | `docx` npm (MIT) — write the node→docx mapping, not raw OOXML | Pro |
| PDF | Generate Typst markup, shell to `typst compile` (Apache-2.0) | Pro |
| RTF | In-house serializer — plain-text control words | Pro |
| EPUB | `jszip` (MIT) over the core HTML serializer | Pro |

Every format derives from the HTML serializer, which is why it lives in core even though most outputs are paid.

Format-specific traps worth knowing before you debug them:

- **RTF** — Unicode needs `\uN?` escapes with an ASCII fallback char; font and color tables must be declared up front.
- **EPUB** — `mimetype` must be the *first* zip entry and *stored uncompressed*. Content must be well-formed XHTML, not HTML5. Validate against EPUBCheck in CI.
- **PDF** — do not hand-roll with `pdf-lib`. Typst exists because pagination, widow/orphan control, and hyphenation are genuinely hard; keep them there.

Dependencies must be permissively licensed (MIT/Apache-2.0/BSD). Copyleft is incompatible with Pro distribution.

## Data store

- **Server: PostgreSQL.** The schema leans on `jsonb` + GIN (smart-collection filters; `tsvector` manuscript search), real enums, and sequence-backed sync cursors. MariaDB's `JSON` is a `LONGTEXT` alias and would need generated columns per path.
- **Clients: SQLite** (GRDB on iOS, OPFS/wa-sqlite on web), defined once in `packages/client-db` and shared by every client. It is a *subset*, not a translation: no `change_log`, no server-owned tables, plus `sync_state` and `pending_change`. All tables are `STRICT`. **Read `packages/client-db/README.md` before touching it** — it documents every deliberate divergence from Postgres, and the two rules that cause silent data loss if broken (`PRAGMA foreign_keys` is per-connection; `pending_change` upserts must preserve `base_version`).
- **Migrations: Liquibase with SQL-formatted changelogs, not XML.** The XML abstraction exists for cross-database portability we don't want; it fights `jsonb`, GIN, and partial indexes. Clients use plain numbered SQL migrations — no Liquibase on clients.

## Sync protocol — the core invariants

`change_log` is append-only; every mutation to `binder_item`, `document`, `label`, `status`, or metadata writes a row. A client remembers the last id it saw and asks for everything after it. This is what makes offline sync tractable without CRDTs. When touching sync, hold these:

1. **Never merge rich text.** On a `document.content` conflict (client `base_version` ≠ server `version`), create a sibling `binder_item` titled `<title> (Conflicted Copy, <device>, <timestamp>)` holding the client's version and let the user reconcile. A rich-text merge algorithm is out of scope, permanently.
2. **Tree conflicts are last-write-wins** by server timestamp. Moves, renames, and reorders are low-stakes; prose is not.
3. **`bigserial` alone is an unsafe cursor.** Sequence values are assigned at insert but become visible at commit, so a txn holding id 100 can commit *after* one holding 101 — a client that has seen 101 and asks `> 101` misses 100 forever. `change_log` therefore carries `tx_id xid8 NOT NULL DEFAULT pg_current_xact_id()`, and every read must gate on `tx_id < pg_snapshot_xmin(pg_current_snapshot())`. The bound applies to `tx_id`, never to `id` — comparing a `bigint` cursor against an `xid8` is a type error, and `id` remains the cursor.
4. **`order_index` is a lexicographic string, not a float.** Fractional indexing on IEEE doubles exhausts precision after ~50 consecutive inserts between the same two siblings. Use the `fractional-indexing` string algorithm — unbounded, sorts natively in both Postgres and SQLite.
5. **The sync feed is visibility-filtered** whenever a `SharingProvider` is present. A raw per-project `change_log` read is a data leak.
6. Deletes are soft (`deleted_at` + tombstone). Trash is a real node — "delete" reparents to it; only "empty trash" hard-deletes.

Sync trigger is client-side: a connectivity monitor starts a 15-minute timer on regaining a connection and only fires if it holds for the full window (resets on drop). Manual "sync now" always overrides.

## Sharing and authorization (Pro)

Membership is a table, not a column. `project.owner_id` stays only as a denormalized fast path.

```
project_member      (project_id, user_id, role, scope_binder_item_id?, invited_by, created_at)
project_invitation  (id, project_id, email, role, scope_binder_item_id?,
                     token_hash, expires_at, accepted_at, invited_by)
```

Roles: `owner | editor | commenter | viewer`. A null `scope_binder_item_id` grants the whole project; otherwise the grant covers exactly one binder subtree — a beta reader sees "Act II" and nothing else. Guest access is an emailed invitation redeemed by magic link, minting a lightweight `is_guest` user; the membership row is the same shape.

These tables live in **core** migrations even though the feature is Pro — schema stays unified so a license upgrade needs no migration, and core simply never writes to them.

**The easiest thing to get wrong:** `GET /sync` must filter `change_log` rows against the caller's scope and role. Deletions must stay observable to scoped clients without leaking titles or the existence of out-of-scope siblings. Every entity type added to `change_log` needs its visibility rule written at the same time.

## Toolchain

- **JDK 21 (LTS)**, pinned via the Gradle toolchain in the root `build.gradle.kts` so CI and contributors never compile against a stray `PATH` JDK — Gradle provisions it if absent.
- **Virtual threads are on** (`spring.threads.virtual.enabled: true`). Both hot paths — sync requests and compile-job dispatch — are I/O-bound waiting on Postgres or the worker, which is exactly the case they serve. Avoid `synchronized` blocks around blocking I/O; they pin the carrier thread.
- **Gradle owns `:api` only.** Everything Node-side is npm: shared libraries live under `packages/*` as npm workspaces, services live at the root (`worker/`). There is no `:worker` Gradle project.
- **No build step on the Node side.** Node ≥ 22.6 strips TypeScript types natively and ships `node:sqlite`, so `packages/client-db` has zero dependencies and its tests run real SQLite. Keep it that way: a transpiler in this path buys nothing.
- **Hibernate runs `ddl-auto: validate`.** Liquibase owns the schema; Hibernate must never alter it.
- **One Liquibase runtime, always.** The version is whatever the Spring Boot BOM manages — deliberately not pinned separately. `io.spring.dependency-management` applies the BOM to the `liquibaseRuntime` configuration as well, so `./gradlew :api:update` and the changelogs Spring applies at startup execute the *same* Liquibase code. **Never run a standalone `liquibase` CLI against a NovelTea database**, whatever version is on `PATH`. Liquibase changes checksum computation across major versions: a CLI on a different major writes `DATABASECHANGELOG` checksums the application runtime then rejects, and the app refuses to boot against a database that is in fact correctly migrated. Recovering means hand-editing checksums in a live table. Verify the resolved version with:
  ```bash
  ./gradlew :api:dependencies --configuration liquibaseRuntime | grep liquibase-core
  ./gradlew :api:dependencies --configuration runtimeClasspath  | grep liquibase-core
  ```
  Those two must print the same version. If they ever diverge, fix that before running any migration.
- **Tests run against Testcontainers Postgres, never H2.** The `tx_id` / `pg_snapshot_xmin` visibility gate and the concurrent-commit ordering case both depend on real Postgres MVCC semantics and cannot be reproduced on an in-memory database.

## Commands

The Gradle wrapper jar is not committed yet — run `gradle wrapper --gradle-version 8.10` once (needs a local Gradle) to generate `gradlew`, or substitute `gradle` for `./gradlew` below.

```bash
docker compose up -d             # local Postgres 16

./gradlew build                  # compile + test everything
./gradlew :api:bootRun           # run the API server
./gradlew :api:test              # all API tests
./gradlew :api:test --tests 'com.noveltea.sync.SyncServiceTest'
./gradlew :api:test --tests 'com.noveltea.sync.SyncServiceTest.rejectsStaleBaseVersion'   # single test

# Liquibase (Spring also applies changelogs on startup)
./gradlew :api:update            # apply pending changesets
./gradlew :api:status            # what would be applied
./gradlew :api:rollbackCount -PliquibaseCommandValue=1

# Node side — npm workspaces at the repo root (packages/*), plus worker/
npm install                      # once, from the root
npm test                         # every workspace
npm test -w @noveltea/client-db  # one workspace
npm run generate -w @noveltea/client-db   # rebuild the migration bundle

# Compile worker (npm, not Gradle)
cd worker && npm run dev
cd worker && npm test -- src/export/epub.test.ts     # single test file
```

## Sync endpoint status

`GET|POST /api/v1/projects/{id}/sync` is implemented in `com.noveltea.sync.SyncService`. What is and is not true of it today:

- **Writable entity types are `binder_item` and `document` only.** Everything else returns a per-change conflict with reason `not_implemented` rather than silently dropping fields. Extend `SyncService.WRITABLE` and add a writer when you add a type.
- **`SecurityConfig` permits every request.** It is a placeholder so sync could be exercised before device pairing exists. It must be replaced before any deployment, and the pull feed must gain role/subtree visibility filtering at the same time.
- **The conflict copy is the whole safety net.** A stale `document` write is never merged and never dropped: the server keeps its version, the client's text is stored as a titled sibling, and the response returns `conflictCopyId`. The client-side merge editor that reconciles the pair is not built yet — until it is, authors see two documents.
- **Tree writes are last-write-wins** by arrival. Document content never takes that path.

When changing any of this, run the mutation check: delete the `tx_id` predicate from the pull query, or make a conflict overwrite instead of copy, and confirm the suite goes red. Tests that cannot fail are not protecting anything.

## Open questions

1. Compile job dispatch between Spring and the worker — Postgres `LISTEN/NOTIFY` on a `compile_job` table avoids adding a broker, but is unproven here.
2. License key issuance and verification for Pro — signing scheme, offline grace period, and what a self-hoster's expired key degrades to.
3. iOS local store details: GRDB schema parity, background sync scheduling.
4. Comments/annotations as a first-class entity — the `commenter` role currently has nothing to write to.
