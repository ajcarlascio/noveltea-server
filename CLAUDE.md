# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Project status

The backend is built and tested. The design of record is `docs/design/v1-data-model-api.md`, amended as decisions were made; where this file and that one disagree, this file wins. A separate repo holds the front end.

## What this is

NovelTea is a self-hosted, offline-first long-form writing app built around a binder tree, document snapshots, labels and statuses, custom metadata, saved and smart collections, and compile presets. Clients are web, Tauri (Windows/macOS), and iOS. Every client keeps a full local replica and works fully disconnected.

Copyright Anthony Carlascio. This repo is the **open core** under Elastic License 2.0; commercial features live in a separate private repo (see Editions).

## Editions — this repo is not the whole product

NovelTea is open-core. **This repository is Core** (Elastic License 2.0). Commercial
features live in a separate private repo and ship as Spring and Node modules that Core
loads if present. The specific roster is deliberately not recorded here — see
`PAID-FEATURES.local.md`, which is gitignored.

**Commercial functionality must never be committed to this repository**, not even disabled
or feature-flagged. The gate is architectural, not legal — code that ships here is code a
self-hoster may legitimately run.

Extension points Core owns (and must keep working when nothing implements them):

- `ExportProvider` — Core registers the always-free formats; the private repo registers the
  rest. Unimplemented formats return `501` with an upgrade pointer, never a stack trace.
- `SharingProvider` — absent in Core. All `/members`, `/invitations` routes return `501`.
  **Sync must work with no provider present**, taking the single-owner path.

If a commercial feature needs new tables, the *migration still lands in Core*, so upgrading
a licence never requires a schema migration against a live database. Core writes nothing to
them.

When adding a feature, decide its edition *before* writing it. Retrofitting an extension
point around code already merged here is the expensive path.

## Architecture: two runtimes, deliberately

- **API server — Java / Spring Boot.** Auth, binder tree, documents, metadata, sharing, sync. All persistence and *all* authorization decisions live here.
- **Compile worker — Node / TypeScript.** Everything that must parse ProseMirror document JSON: word counts and every export format.

**The boundary rule: the JVM never interprets document structure.** Spring stores `document.content` as opaque `jsonb` and hands it to the worker. If you find yourself walking ProseMirror nodes in Java, the logic belongs in the worker instead.

## Export stack — no Pandoc

Pandoc is GPL and cannot ship inside a proprietary module. It was also never necessary: Pandoc solves an N×M format matrix, and this is 1×5 from a clean structured source. Everything is serialization, not parsing.

| Format | Implementation |
|---|---|
| HTML | `prosemirror-model` `DOMSerializer` |
| MD / TXT | `prosemirror-markdown` |
| DOCX | `docx` npm (MIT) — write the node→docx mapping, not raw OOXML |
| PDF | Generate Typst markup, shell to `typst compile` (Apache-2.0) |
| RTF | In-house serializer — plain-text control words |
| EPUB | `jszip` (MIT) over the Core HTML serializer |

Which formats are free and which are commercial is recorded in `PAID-FEATURES.local.md`, not here. Every format derives from the HTML serializer, which is why that serializer lives in Core regardless.

Format-specific traps worth knowing before you debug them:

- **RTF** — Unicode needs `\uN?` escapes with an ASCII fallback char; font and color tables must be declared up front.
- **EPUB** — `mimetype` must be the *first* zip entry and *stored uncompressed*. Content must be well-formed XHTML, not HTML5. Validate against EPUBCheck in CI.
- **PDF** — do not hand-roll with `pdf-lib`. Typst exists because pagination, widow/orphan control, and hyphenation are genuinely hard; keep them there.

Dependencies must be permissively licensed (MIT/Apache-2.0/BSD). Copyleft is incompatible with commercial distribution.

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

## Sharing and authorization

Membership is a table, not a column. `project.owner_id` stays only as a denormalized fast path.

```
project_member      (project_id, user_id, role, scope_binder_item_id?, invited_by, created_at)
project_invitation  (id, project_id, email, role, scope_binder_item_id?,
                     token_hash, expires_at, accepted_at, invited_by)
```

Roles: `owner | editor | commenter | viewer`. A null `scope_binder_item_id` grants the whole project; otherwise the grant covers exactly one binder subtree — a beta reader sees "Act II" and nothing else. Guest access is an emailed invitation redeemed by magic link, minting a lightweight `is_guest` user; the membership row is the same shape.

These tables live in **Core** migrations even though no Core code writes to them, so that schema stays unified and a licence upgrade never requires a migration.

**The easiest thing to get wrong:** `GET /sync` must filter `change_log` rows against the caller's scope and role. Deletions must stay observable to scoped clients without leaking titles or the existence of out-of-scope siblings. Every entity type added to `change_log` needs its visibility rule written at the same time.

## Toolchain

- **JDK 21 (LTS)**, pinned via the Gradle toolchain in the root `build.gradle.kts` so CI and contributors never compile against a stray `PATH` JDK — Gradle provisions it if absent.
- **Virtual threads are on** (`spring.threads.virtual.enabled: true`). Both hot paths — sync requests and compile-job dispatch — are I/O-bound waiting on Postgres or the worker, which is exactly the case they serve. Avoid `synchronized` blocks around blocking I/O; they pin the carrier thread.
- **Gradle owns `:api` only.** Everything Node-side is npm: shared libraries live under `packages/*` as npm workspaces, services live at the root (`worker/`). There is no `:worker` Gradle project.
- **No build step on the Node side.** Node ≥ 22.6 strips TypeScript types natively and ships `node:sqlite`, so `packages/client-db` has zero dependencies and its tests run real SQLite. Keep it that way: a transpiler in this path buys nothing.
- **Hibernate runs `ddl-auto: validate`.** Liquibase owns the schema; Hibernate must never alter it.
- **The changelog path is part of a changeset's identity.** Liquibase stores it in `DATABASECHANGELOG.FILENAME`, so the *same* file registered under two paths counts as two changesets. `api/build.gradle.kts` therefore sets `searchPath` to `src/main/resources` and `changelogFile` to `db/changelog/db.changelog-master.yaml`, matching Spring's `classpath:` view exactly. Get this wrong and the app finds nothing applied and tries to recreate a schema that already exists (`relation "app_user" already exists`), which looks like a migration bug and is not.
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
docker compose up -d             # local Postgres 18

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

## Binder tree semantics

`BinderService` owns structural operations. Two rules that are not obvious from the schema:

- **Trash is a move, not a delete.** Trashing reparents an item to the project's trash node and records `trashed_from_parent_id`; the item keeps syncing and stays restorable. `deleted_at` is reserved for the tombstone written when the trash is emptied — rows are retained, never removed, so a client that was offline still learns the item is gone. Restoring to a parent that has since been trashed falls back to root rather than refusing, because a refusal strands the item where the author cannot reach it.
- **Cycle prevention is application-level and cannot be moved into the schema.** No CHECK constraint can express "this item is not among its own descendants". `BinderService.move` runs a recursive CTE before every reparent. Without it, a mis-ordered drag detaches an entire subtree: chapters that exist in the database but appear nowhere in the binder. Three tests cover it, and disabling the guard turns them red.

## Merge editor support

`MergeService` reconciles a conflict copy with its original. Three things to know:

- **The link is a foreign key, not a title.** `binder_item.conflict_of_id` points at the item a copy forked from, with `conflict_base_version` recording how far behind the losing client was. Titles are author-editable and ambiguous once two copies exist; never match on them.
- **No diff is computed server-side.** Content is ProseMirror JSON and only the editor understands its schema — which the client already has. The server returns both documents and their provenance; the client renders the merge. A server-side structural diff would be a second, divergent implementation of the document model.
- **Resolving trashes the copy, never deletes it.** A bad merge must stay recoverable. `resolve` also refuses on a stale `baseVersion` rather than forking again — merging is interactive, so the author re-opens the updated pair. Forking on merge would let copies breed without bound.

`ConflictSummary.originalVersion` and `ConflictDetail.originalVersion` are the **document's** version, which is what `resolve` validates. `binder_item` carries its own independent version for structural edits; returning that one produces a `baseVersion` that can never match.

## Auth and error handling

`POST /auth/register|login` create an account or a session; `POST /auth/pairing-codes` mints a short code on a trusted device, which `POST /auth/pair` redeems to onboard a second one. `POST /auth/refresh` rotates.

Rules worth keeping:

- **`noveltea.auth.jwt-secret` has no default and startup fails without it** (32 bytes minimum). A fallback signing key is a fallback that reaches production. Set `NOVELTEA_JWT_SECRET`; the test profile carries its own.
- **Refresh tokens rotate on every use and are stored only as SHA-256 hashes.** A leaked token works at most once, and the legitimate device's next refresh failing is a detectable signal. Pairing codes are stored hashed for the same reason.
- **Every auth failure returns one identical message.** Distinguishing "no such account" from "wrong password" turns login into an account-enumeration oracle.
- **`ProjectAccess` is the single authorization gate.** Core recognises ownership only; the commercial `SharingProvider` extends it rather than replacing it.
- **A resource the caller may not see returns 404, never 403** — a 403 confirms it exists. But *unauthenticated* is 401, not 403, so clients know whether refreshing a token would help. Spring defaults to 403 for both; `RestAuthEntryPoints` corrects it.

`GlobalExceptionHandler` owns every mapping — controllers must not declare their own `@ExceptionHandler`. Unexpected exceptions are logged with a stack trace and answered with a generic message, because exception text routinely carries SQL, table names and parameter values.

## Model tests

`com.noveltea.model` is covered by two suites that exist for different reasons:

- **`WireEnumTest`** drives off the enum constants themselves, so a new value is covered the moment it is added: round-tripping, unique lowercase wire forms, case and whitespace tolerance, and empty (never an exception) for null, blank, unknown and injection-shaped input.
- **`EnumSchemaAlignmentTest`** reads the allowed values out of `pg_constraint` and compares them to the enum, then writes every value to the real table. The enum and the CHECK constraint are two declarations of one fact in different languages; nothing but this keeps them honest. Add a value to one and not the other and it fails, naming the constraint.

## User-generated content is hostile

Document JSON arrives from clients and is stored as-is. Two rules follow:

- **Link hrefs are allowlisted, not escaped.** Escaping does nothing to `javascript:alert(1)` — there is nothing in it to escape. `isSafeHref` permits `http`, `https`, `mailto` and relative references, normalising away case, whitespace and control characters first, and fails closed on anything unlisted. An unsafe link loses its anchor and keeps its text, and the author is warned. This applies to Markdown too: markdown links execute in renderers.
- **All text is escaped on output**, and a test feeds `<script>` through as author text and asserts it parses back as text rather than an element.

We do not store HTML, so there is nothing to sanitise on write; the exposure is entirely on the way out.

`IdorSweepTest` enumerates every route from the handler mapping and drives it with a stranger's token: routes naming another account's resource must not answer 2xx, caller-scoped collections must answer without containing the victim's data, and nothing may answer 500. Because it reads the mapping rather than a hand-written list, a new controller method that forgets its check fails it immediately.

## Comments and mail

Comments are annotations on a document, optionally anchored to a passage, threaded by `parent_comment_id`, and synced like any other entity.

- **An anchor stores the quoted text alongside its offsets.** ProseMirror positions shift with every edit, so orphaning is judged by whether the quoted words are still in the document, not by whether the offsets still line up. A comment whose text has gone is reported **orphaned** — never moved and never deleted. Relocating an editor's note to the wrong sentence is worse than admitting it lost its place.
- **Authorship is server-assigned**, which is why comments are hand-written in sync rather than spec-driven: taking the author from a payload would let a client attribute a remark to someone else.
- **Only the author edits or deletes; anyone with write access resolves.** Resolving is a shared editorial act; rewording someone else's note is not.
- Deletes are soft, so the deletion propagates and threads keep their shape.

**Mail** is one `Mailer` bean chosen at startup: SMTP when `spring.mail.host` is set, otherwise a fallback that logs and says loudly that a reset link in a log file is a credential. Decided at runtime rather than by two `@ConditionalOnMissingBean` beans, which is only dependable inside auto-configuration and produced a bean-definition clash here.

**Notification never fails the action.** A comment is saved whether or not a mail server is reachable, and nobody is mailed about their own comment.

### `email` is `citext`, not `text`

The driver returns it as a `PGobject`, so `(String) row.get("email")` throws. Every read casts in SQL — `u.email::text`. This cost real time: the same cast inside the notifier's swallowed `catch` looked exactly like "notifications silently do not work".

## Account lifecycle

**Password reset** is two endpoints, both unauthenticated and both rate-limited. `POST /auth/password-reset` always answers 202 whether or not the address exists — a different answer would make it the account-enumeration oracle that login deliberately is not. Only the token hash is stored, requesting a new link invalidates the previous one, and confirming it **signs every device out**: someone resetting a password usually believes it was stolen, and leaving the attacker's paired phone signed in would make the reset theatre.

Delivery goes through `PasswordResetDelivery`. Until mail exists, the default logs the token and says loudly that anyone who can read the logs can take the account — tolerable for a single-operator install, unacceptable for anything multi-tenant.

**Account deletion is scheduled, not immediate.** `POST /account/deletion` starts a 14-day countdown that `DELETE /account/deletion` cancels; the retention sweep carries it out afterwards. This is the one action a person takes in a bad moment and cannot undo, so it gets the same treatment as trash before tombstone, applied to a whole account.

- It **requires the password again** despite the caller being authenticated: a borrowed unlocked laptop must not destroy someone's novels.
- An account pending deletion **can still sign in**, or it could never cancel. Only `deleted_at` blocks sign-in and reset.
- Purging **deletes projects explicitly** before the user row. `project.owner_id` is `ON DELETE RESTRICT` so a stray user delete cannot quietly take a novel with it; deletion has to state that it means the projects too.

## Backup and restore

**Back up the database. Nothing else is the author's work.** Exports are regenerated from it on demand, and the staging directory is transient by design — neither belongs in a backup, and including them makes the backup larger and slower for no recovery value. Authors who want their own copy of a compiled manuscript take the `download` destination and keep it on their own device; that is their backup, not the server's.

```bash
pg_dump --format=custom noveltea > noveltea-$(date +%F).dump
```

`DATABASECHANGELOG` is included, so migrations do not re-run on restore; if the dump predates the code, pending ones apply at startup. For point-in-time recovery, use WAL archiving rather than more frequent dumps.

**After restoring, bump the epoch.** This is not optional:

```sql
UPDATE project SET sync_epoch = sync_epoch + 1;
```

A restore rewinds `change_log`, but every device keeps a cursor past the restored maximum. Without a bump they pull, receive nothing, and conclude they are up to date — while the server has rolled back underneath them. The local copy silently becomes the only complete one and nothing detects the divergence. `change_log_purged_below` cannot catch this: it detects a cursor that is too *low*, not a server that moved backwards.

Clients echo the epoch on every pull; a mismatch returns `resyncRequired` and they rebuild from the binder and documents.

## Limits and admission control

- **Rate limiting covers credential endpoints only** (`login`, `register`, `refresh`, `pair`). Sync is deliberately untouched: a first sync fetches every document and a resync rebuilds from scratch, so a client legitimately makes hundreds of requests in seconds. Any rate low enough to deter guessing would break exactly those flows. Verified live: 200 rapid pulls and 150 item creations all served, while the 11th login attempt is refused.
- **Compiles are bounded by queue depth, not by rate.** An author tuning a preset exports repeatedly and legitimately; each finished job frees its slot at once, so that workflow never meets the limit. Identical pending jobs are also deduplicated — 30 identical requests return one job id.
- **The request ceiling is 32MB.** Deliberately high: some authors keep an entire novel in one document, and 200,000 words is several megabytes of ProseMirror JSON. Oversized requests get a 413 rather than a dropped connection, which a client would otherwise retry forever.
- **Sync pages stop on bytes as well as rows** (4MB), always emitting at least one row so a single oversized document cannot wedge the feed. This is what makes a page size predictable on mobile data.
- **`/health` and `/health/ready`** are unauthenticated. Readiness touches the database, because an instance that cannot reach Postgres should not be sent traffic; neither reveals anything about the deployment.
- **CORS is off unless configured.** `noveltea.cors.allowed-origins` takes exact origins, never a wildcard. A browser client on its own origin must be listed there.

The limiter is **in-memory and therefore per instance**. Behind more than one replica it limits per replica — a real limitation to address before scaling out, not a subtlety.

## Known weak points

Found by an adversarial pass against the running stack. None is a data-exposure bug; all are availability or abuse risks that need addressing before this faces the open internet.

- **No rate limiting anywhere.** 25 failed logins were served in 0.1s. Password guessing, credential stuffing and endpoint hammering are all unthrottled. Login, refresh and pairing redemption need it most.
- **No per-account compile quota.** 30 jobs were accepted instantly; each writes a file. An authenticated user can fill the export volume. The retention sweep reclaims it only after the TTL.
- **No explicit request body ceiling.** An 8MB body is accepted silently; 32MB is dropped at the transport layer rather than answered with 413.
- **The sync feed is bounded by rows, not bytes.** A page of 500 documents has no size limit, which matters on mobile data.
- **An access token outlives device revocation** by up to its 15-minute TTL. The refresh token is rejected immediately, so the window is bounded, but revocation is not instant. Fixing it properly means a revocation check on each request, which trades a database read per call.

What held up: JWT forgery (`alg:none`, tampered payload, wrong key), cross-account access on every route, SQL and template injection, path traversal, malformed JSON, and oversized pages. Nothing returned 500 and nothing leaked internals.

## Retention

An hourly sweep (`RetentionService`) removes what nothing will read again: old feed rows, tombstones, expired exports, spent pairing codes and dead invitations.

**The change feed is the dangerous one.** A client that has been offline learns an item was deleted *only* from its delete row. Purge that row too early and the client keeps a document its author threw away, permanently, because no later sync mentions it again. So a row is removed only when both hold:

1. it is older than `changeLogRetention`, **and**
2. every non-revoked device of the project owner has read past it (`device.last_seen_change_id`, advanced on each pull).

A device that has never synced has cursor 0 and therefore blocks purging entirely — correct, since it has seen nothing. Revoked devices are ignored, or an abandoned laptop would preserve history forever.

**When a cursor falls behind the purge point, the client is told to resync** (`PullResponse.resyncRequired`) and must rebuild from `GET /binder` plus documents. Two details make that safe:

- The comparison is **strictly** below `change_log_purged_below`. A cursor sitting exactly at the purge point has seen everything removed.
- The returned `latestId` is never below the purge point, **even when the feed is now empty**. Resuming at 0 would put the client straight back into a resync — an infinite loop.

**Server exports are never deleted.** Only files inside the staging directory are removed, checked by resolved path. A `server` export is the author's own manuscript sitting in the operator's mount, and deleting it would be unforgivable.

## Search

`GET /projects/{id}/search?q=…` searches titles, synopses, body text and notes across a project.

- **Synopses and notes are searchable although they are never exported.** They are exactly what an author searches to find a scene again; leaving them out would make them write-only. Compile and search deliberately disagree about them.
- **Weighted, not flat.** Title beats synopsis beats body beats notes. Someone typing "lighthouse" usually wants the scene called that, not the twentieth paragraph mentioning one. Titles live on `binder_item` so they carry their own `title_tsv` and their weight is applied in the query; the rest are weighted inside `document.search_tsv`.
- **`websearch_to_tsquery` parses the input**, so quoted phrases, `or` and `-exclusion` work, and malformed input yields no results rather than an error. Never build a tsquery by string concatenation.
- **Trashed items are excluded by default** and returned flagged when asked for; tombstoned ones never appear.
- Folders match too — they have titles worth finding.

`document.search_text` is supplied by clients on sync push, since only they parse ProseMirror. A document with no body still matches on its title.

## Snapshots

Point-in-time copies of a document, so a revision pass can be undone. `POST /documents/{id}/snapshots` captures, `GET` lists, `POST /snapshots/{id}/restore` puts one back.

**Manual snapshots sync; automatic ones do not.** A snapshot is a full copy of a document. Syncing every autosave capture across three devices would put hundreds of megabytes of history on a phone for prose it may never open, and would make snapshots the heaviest thing in a feed designed to be frugal. But keeping all of them local means a lost laptop takes its entire revision history with it, which contradicts everything else here. A manual snapshot is a deliberate "keep this version" and is rare enough to copy; `is_automatic` is what carries that distinction, and only manual snapshots append to `change_log`.

Consequences worth holding on to:

- **The feed carries snapshot metadata, never content** (`to_jsonb(t) - 'content'`). Clients fetch a body from `GET /snapshots/{id}` when the author actually opens it. Parenthesise that expression: `to_jsonb(t) - 'content'::text` casts the key, not the result.
- **Anything arriving over sync is manual by definition**, and is stored that way regardless of what the payload claims.
- **Snapshots are immutable.** Create and delete only; an update is refused rather than rewriting history.
- **Restoring is itself undoable** — the pre-restore state is captured automatically first, so an author who reverts to the wrong version has not lost the newer one. Restore also takes a `baseVersion` and refuses a stale one, so it cannot overwrite an edit made on another device.
- **Only automatic snapshots are pruned** (`noveltea.snapshots.keep-automatic-per-document`). A manual snapshot is something the author asked for; deleting it is not the server's decision.

## Compile pipeline

`POST /projects/{id}/compile` queues a `compile_job` and returns immediately. The Node worker in `worker/` claims it, renders it with `@noveltea/compile`, writes the artifact and records the result. `GET /compile-jobs/{id}` reports status; `/download` streams the file.

- **The author picks a destination per compile.** `download` stages the file briefly and expires it; `server` writes to the operator's mounted path and keeps it; `cloud` is commercial and a Core build answers `501`. `DestinationProvider` is the extension point, shaped like `ExportProvider`.
- **The worker reads Postgres directly.** It must, because it owns the document schema and the JVM never interprets document structure. It claims with `FOR UPDATE SKIP LOCKED`, so running several workers needs no coordination and a crashed one blocks nobody.
- **`pg_notify` is sent inside the submitting transaction.** Postgres holds notifications until commit, so the worker cannot wake before the row it is being told about is visible. Polling is a fallback for anything missed, not the primary path.
- **A failed job backs off** (`next_attempt_at`) rather than being re-claimed on the next pass. Without it a drain spends every retry in milliseconds, which is useless for the transient faults retrying exists for.
- **Download paths are re-validated against the configured roots** before anything is streamed. A corrupted or tampered `output_path` must not become an arbitrary file read.

Run it with `NOVELTEA_DB_URL`, `NOVELTEA_EXPORT_PATH` and `NOVELTEA_STAGING_PATH`; the API needs the same two paths under `noveltea.compile.*`.

## Compile

`packages/compile` turns ProseMirror documents into the formats Core ships (`txt`, `md`, `html`). `ExportProvider` on the Java side reports the same boundary; a commercial module contributes the remaining formats by supplying another implementation, and Core must keep working with only its own provider present.

Rules the tests enforce:

- **Only document text is converted.** Folders contribute a title at most. **Synopses and notes are never exported under any option** — they are the author's scaffolding, not the book, and exporting them by accident is worse than refusing.
- **`planCompile()` reports what would be included before anything is rendered**, so an author learns that half their selection is folders before waiting for a long manuscript rather than after.
- **An unknown node or mark warns and keeps its text.** Losing words to a node type the package has not met is the worst available outcome; the wrapper is dropped and the prose survives. Each unknown type is reported once, not per occurrence.
- **A format Core does not ship throws** rather than quietly producing something else.

Markdown escaping is deliberately narrow: escaping every `.` and `-` puts backslashes through ordinary prose. Only ambiguous inline characters are escaped, and block markers only at line start.

Tests parse HTML output with an independent parser rather than comparing it to a string the serializer produced, and one property test asserts every authored word survives into every format. See `packages/compile/README.md`.

## Constants and limits

- **Closed value sets are enums in `com.noveltea.model`**, each with a `wire()` form matching its `text` column and CHECK constraint. Adding a value means editing one enum, not hunting string literals. Do not reintroduce bare string comparisons for entity types, ops, formats, roles or platforms.
- **Numeric bounds live in `LimitProperties`** (`noveltea.limits.*`), not as private static fields. A deployment can change them, and two services cannot disagree about the same bound.

## Push writes must be scoped to the project

A push is authorized on the project in its path. That proves the caller may write to **this** project — not that the entity id they sent belongs to it. **Every read and write in the push path therefore carries a project scope**, and `SyncEntitySpec.scopeClause()` supplies it for spec-driven entities (through `collection` or `binder_item` for the two tables with no `project_id` of their own; it throws rather than emit an unscoped statement for a type nobody has scoped).

This was a real, exploitable defect, not a hypothetical. Without the scope, an id learned from an export filename or a bug report was enough to overwrite another author's chapter, reparent their folder so the subtree vanished from their binder, or delete their snapshots — and because `change_log` was written against the *attacker's* project, the victim's own devices never learned anything had happened.

`document` has no `project_id`; it is scoped through its `binder_item`. `SyncTenantIsolationTest` pins each case and fails if any scope is removed.

## Synced data entity types

`SyncEntitySpec` declares taxonomy, custom metadata, collections and compile presets: their columns, types, required fields, parent references and cross-field invariants. `SyncEntityWriter` validates against it before building any statement.

**Every CHECK constraint in the schema must have a matching invariant here**, and `SyncEntitySpecGuardTest` enforces it by reading `pg_constraint` at test time. An `Invariant` names the exact constraint it mirrors (`mirrorsConstraint`) — that string is checked, not decorative. Add a CHECK without a counterpart and the build fails with a message telling you which one. A constraint that only exists in Postgres surfaces as an exception that fails an entire push; caught in the spec it is one reported conflict with a `detail` the client can act on. When you add a CHECK, add the invariant in the same change.

Two rules that are easy to get wrong:

- **`parentRefs` are an authorization boundary**, guarded by a test asserting every referenced table has a `project_id` to scope on, not just referential integrity. Without the same-project check a caller could attach a row to another account's collection. The rejection message is deliberately vague so it cannot confirm the row exists elsewhere.
- **Invariants run against the merged row on update**, never the patch alone. `{"is_smart": true}` looks harmless in isolation and is invalid against a collection with no query.

Documents and binder items are deliberately not spec-driven: conflict copies and tree semantics do not belong in a declarative table.

## Sync endpoint status

`GET|POST /api/v1/projects/{id}/sync` is implemented in `com.noveltea.sync.SyncService`. What is and is not true of it today:

- **Writable entity types are `binder_item` and `document` only.** Everything else returns a per-change conflict with reason `not_implemented` rather than silently dropping fields. Add a case to the switch in `SyncService.applyOne`, or a `SyncEntitySpec` entry, when you add a type.
- **Auth is real now.** Every route under `/api/v1` needs a bearer access token except register, login, refresh and pair. The device is taken from the token's `did` claim, never from a header, so a client cannot attribute writes to another device. The pull feed still needs role/subtree visibility filtering, which arrives with the commercial `SharingProvider`.
- **The conflict copy is the whole safety net.** A stale `document` write is never merged and never dropped: the server keeps its version, the client's text is stored as a titled sibling, and the response returns `conflictCopyId`. The client-side merge editor that reconciles the pair is not built yet — until it is, authors see two documents.
- **Tree writes are last-write-wins** by arrival. Document content never takes that path.

When changing any of this, run the mutation check: delete the `tx_id` predicate from the pull query, or make a conflict overwrite instead of copy, and confirm the suite goes red. Tests that cannot fail are not protecting anything.

## Open questions

1. Compile job dispatch between Spring and the worker — Postgres `LISTEN/NOTIFY` on a `compile_job` table avoids adding a broker, but is unproven here.
2. Licence key issuance and verification — signing scheme, offline grace period, and what a self-hoster's expired key degrades to.
3. iOS local store details: GRDB schema parity, background sync scheduling.
4. Comments/annotations as a first-class entity — the `commenter` role currently has nothing to write to.
