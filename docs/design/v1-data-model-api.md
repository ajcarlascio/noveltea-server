# Self-Hosted Writing App — Data Model & API Sketch (v1)

> Status: v1 sketch, with amendments recorded at the bottom (2026-08-18).
> Where the amendments and the v1 body disagree, **the amendments win**.

## Design constraints driving this

- Rich text stored as structured JSON (ProseMirror-style doc), not Markdown source
- Export only needs to support: .md, .pdf, .docx *(superseded — see Amendment A3)*
- Offline-first: every client (web/Tauri/iOS) keeps a local copy and works fully disconnected
- Sync trigger: client attempts sync once it detects >15 min of continuous connectivity (avoids syncing on flaky/brief connections, e.g. a train wifi blip)

---

## 1. Data Model

### project

The top-level container (one "book").

| field | type | notes |
|---|---|---|
| id | uuid | |
| owner_id | uuid | for later multi-user support |
| title | text | |
| created_at / updated_at | timestamptz | |
| settings | jsonb | compile defaults, word count targets, etc. |

### binder_item

The tree structure — folders, documents, trash. This is the "binder": the outline the author navigates.

| field | type | notes |
|---|---|---|
| id | uuid | |
| project_id | uuid | |
| parent_id | uuid, nullable | null = root level |
| type | enum | `folder` \| `document` \| `trash` |
| title | text | |
| order_index | float | fractional indexing — lets you reorder/insert without rewriting siblings *(superseded — see A4)* |
| icon | text, nullable | optional custom icon for the binder row |
| deleted_at | timestamptz, nullable | soft delete — needed for sync tombstones |
| version | bigint | monotonic, incremented on every change |
| updated_by_device_id | uuid | for conflict attribution |
| created_at / updated_at | timestamptz | |

Trash is just a special parent — moving something to trash sets parent_id to the project's trash node rather than deleting outright. Real deletion happens on "empty trash," which produces a tombstone.

### document

1:1 with a binder_item of type document. Split from binder_item so the (large) content payload doesn't bloat every tree query.

| field | type | notes |
|---|---|---|
| id | uuid | same as binder_item.id, or separate FK — recommend same id for simplicity |
| content | jsonb | ProseMirror doc JSON |
| word_count | int | derived, cached for fast binder display |
| synopsis | text, nullable | short index-card summary shown on the corkboard |
| notes | text, nullable | document notes (rich text or plain) |
| version | bigint | incremented per content save |
| updated_at | timestamptz | |

### snapshot

Manual or automatic point-in-time captures of a document's content, so an author can revert a revision pass.

| field | type | notes |
|---|---|---|
| id | uuid | |
| document_id | uuid | |
| content | jsonb | full copy at that point |
| label | text, nullable | user-given name, e.g. "before edit pass" |
| created_at | timestamptz | |
| created_by_device_id | uuid | |

### label / status

Metadata taxonomies, per project.

| field | type | notes |
|---|---|---|
| id | uuid | |
| project_id | uuid | |
| kind | enum | `label` \| `status` |
| name | text | |
| color | text, nullable | hex, only relevant for `label` |

binder_item gets label_id and status_id nullable FKs.

### custom_metadata_field + custom_metadata_value

For arbitrary user-defined metadata. Field definitions live at the project level, values are keyed per binder_item.

### collection

Saved/smart searches (tag- or metadata-based groupings that aren't part of the tree).

| field | type | notes |
|---|---|---|
| id | uuid | |
| project_id | uuid | |
| name | text | |
| query | jsonb | structured filter (label = X, status = Y, keyword contains Z) |
| is_smart | bool | smart (live query) vs. static (manual member list) |

### compile_preset

Saved export configuration.

| field | type | notes |
|---|---|---|
| id | uuid | |
| project_id | uuid | |
| name | text | |
| format | enum | `md` \| `pdf` \| `docx` *(extended — see A3)* |
| included_binder_items | uuid[] | or a query, same idea as collections |
| separator_rules | jsonb | e.g. page break between folders, `#` between docs |
| title_page / front_matter | jsonb, nullable | |

### device

Registered client for auth + conflict attribution.

| field | type | notes |
|---|---|---|
| id | uuid | |
| user_id | uuid | |
| name | text | "Anthony's MacBook", "iPhone" |
| platform | enum | `web` \| `windows` \| `ios` |
| last_synced_at | timestamptz | |
| refresh_token_hash | text | |

### change_log

Append-only table backing the sync protocol — every mutation to binder_item, document, label, status, or metadata writes a row here.

| field | type | notes |
|---|---|---|
| id | bigserial | ordering primary key, doubles as a sync cursor *(unsafe as-is — see A5)* |
| project_id | uuid | |
| entity_type | enum | `binder_item` \| `document` \| `label` \| ... |
| entity_id | uuid | |
| op | enum | `create` \| `update` \| `delete` |
| device_id | uuid | origin device |
| created_at | timestamptz | |

This is the key piece that makes offline sync tractable without full CRDT machinery: each client just remembers the last change_log.id it has seen and asks "give me everything after N."

---

## 2. Sync & Conflict Strategy

- Each entity carries a version integer, bumped on every write.
- Client pushes a batch: {entity, base_version, new_data} for each locally-changed record since last sync.
- Server accepts if base_version matches current server version (no one else changed it since the client last knew about it). Otherwise it's a conflict.
- On conflict for document content: don't try to merge rich text — create a sibling binder_item titled "<original title> (Conflicted Copy, <device>, <timestamp>)" containing the client's version, and let the user manually reconcile. This is the established behaviour for file sync generally, and avoids building a merge algorithm for rich text, which is a rabbit hole.
- On conflict for tree structure (moves/renames/reorders): last-write-wins by server timestamp is fine — much lower stakes than losing prose.
- Sync trigger: client-side connectivity monitor starts a 15-minute timer on regaining connection; only fires sync if the connection holds for the full window (resets on drop). Manual "sync now" button always available as an override.

---

## 3. API Spec (REST + one sync endpoint)

Base: /api/v1. Auth via bearer JWT issued per device at pairing time (short-lived access token + long-lived refresh token).

### Auth

```
POST /auth/pair          { pairing_code } → { device_id, access_token, refresh_token }
POST /auth/refresh       { refresh_token } → { access_token }
DELETE /devices/:id      revoke a device
GET /devices             list devices for the account
```

### Projects

```
GET    /projects
POST   /projects                     { title }
GET    /projects/:id
PATCH  /projects/:id
DELETE /projects/:id
```

### Binder tree

```
GET    /projects/:id/binder                  full tree (or ?since=version for incremental)
POST   /projects/:id/binder-items            { parent_id, type, title, order_index }
PATCH  /binder-items/:id                     { title?, parent_id?, order_index?, label_id?, status_id? }
DELETE /binder-items/:id                     soft delete → moves to trash
POST   /binder-items/:id/restore
DELETE /projects/:id/trash                   empty trash (hard delete + tombstone)
```

### Documents

```
GET    /documents/:id
PUT    /documents/:id                { content, base_version }   → 409 on version mismatch
GET    /documents/:id/snapshots
POST   /documents/:id/snapshots      { label? }
POST   /snapshots/:id/restore
```

### Metadata

```
GET    /projects/:id/labels
POST   /projects/:id/labels          { name, color }
GET    /projects/:id/statuses
POST   /projects/:id/statuses        { name }
GET    /projects/:id/custom-fields
POST   /projects/:id/custom-fields   { name, field_type }
PUT    /binder-items/:id/metadata    { field_id, value }
```

### Collections

```
GET    /projects/:id/collections
POST   /projects/:id/collections     { name, query, is_smart }
GET    /collections/:id/items
```

### Compile / Export

```
GET    /projects/:id/compile-presets
POST   /projects/:id/compile-presets          { name, format, included_binder_items, separator_rules }
POST   /projects/:id/compile                  { preset_id | inline_config } → { job_id }
GET    /compile-jobs/:id                      { status: queued|running|done|failed, download_url? }
```

Compile runs async since DOCX/PDF generation on a large manuscript isn't instant — client polls or gets a webhook/WS push.

### Sync

```
GET  /projects/:id/sync?since=<change_log_id>
     → { changes: [...], latest_id: N }

POST /projects/:id/sync
     Body: { since: <change_log_id>, changes: [ { entity_type, entity_id, op, base_version, data } ] }
     → { applied: [...], conflicts: [ { entity_id, reason, conflict_copy_id? } ], latest_id: N }
```

This one endpoint pair is the whole offline sync engine — everything else in the API exists for the "online, direct" case (e.g. web client hitting the server live), while offline clients mostly talk to their local store and reconcile through /sync.

---

## Amendments (2026-08-18)

### A1 — Stack

**Java / Spring Boot** for the API server; a separate **Node/TypeScript compile worker** for anything that must parse ProseMirror JSON (word count, all export formats). The JVM treats `document.content` as opaque `jsonb`. Rationale: `prosemirror-model` and `prosemirror-markdown` only exist in JS, and the compile service wants to be a separate async, CPU-heavy process regardless.

### A2 — Database

**PostgreSQL** on the server (not SQLite, not MariaDB): the schema depends on `jsonb` + GIN for smart-collection queries and `tsvector` for manuscript search. **SQLite** on every client, as a subset schema — no `change_log`, plus a local `pending_changes` queue. Migrations via **Liquibase with SQL-formatted changelogs** (XML's portability abstraction fights `jsonb`/GIN); clients use plain numbered SQL migrations, no Liquibase.

### A3 — Export formats

`format` enum extends to `md | html | txt | rtf | docx | odt | epub | pdf`. **EPUB is the important addition** — it's the primary self-publishing output for a book app. RTF matters because it's still a common submission format. HTML and TXT are near-free from ProseMirror.

*Superseded in part by A8 (implementation) and A7 (which formats are paid).*

### A4 — `order_index` becomes a string

Float-based fractional indexing exhausts IEEE double precision after roughly 50 consecutive inserts between the same two siblings. Switch to a **lexicographic string key** (the `fractional-indexing` algorithm) — unbounded, and it sorts natively in both Postgres and SQLite.

### A5 — `change_log.id` is not a safe cursor on its own

Sequence values are assigned at insert but only become visible at commit, so a transaction holding id 100 can commit *after* one holding 101. A client that has seen 101 and requests `> 101` will miss 100 permanently. Fix: only serve rows below `pg_snapshot_xmin(pg_current_snapshot())`, or assign the cursor value at commit time.

### A6 — Sharing: accounts, guests, and subtree scope

`project.owner_id` stays only as a denormalized fast path; real membership moves to:

```
project_member      (project_id, user_id, role, scope_binder_item_id?, invited_by, created_at)
project_invitation  (id, project_id, email, role, scope_binder_item_id?,
                     token_hash, expires_at, accepted_at, invited_by)
```

Roles: `owner | editor | commenter | viewer`. A null `scope_binder_item_id` grants the whole project; otherwise the grant covers exactly one binder subtree (a beta reader sees "Act II" only). Guest access = an emailed invitation redeemed by magic link, minting an `is_guest` user; the membership row is the same shape.

New API surface:

```
GET    /projects/:id/members
POST   /projects/:id/invitations     { email, role, scope_binder_item_id? }
DELETE /invitations/:id
POST   /invitations/accept           { token } → { access_token, refresh_token }
PATCH  /members/:id                  { role?, scope_binder_item_id? }
DELETE /members/:id
```

**Impact on sync:** `GET /sync` must filter `change_log` rows by the caller's role and scope. Deletions must remain observable to scoped clients without leaking titles or the existence of out-of-scope siblings. Every entity type added to `change_log` needs its visibility rule written at the same time.

### A7 — Open-core split

The product splits into a public **Core** (this repo, ELv2) and a private commercial repo.
The capability roster is not recorded in this repository; it lives in the gitignored
`PAID-FEATURES.local.md`.

Gating is architectural, not licensed: **no commercial code is committed to the public
repo**, not even disabled. Core defines two extension points — `ExportProvider` and
`SharingProvider` — and returns `501` with an upgrade pointer for anything unimplemented.
Sync must function with no `SharingProvider` present, taking the single-owner path.

The A6 sharing tables ship in **Core** migrations even though the feature is commercial, so
that a licence upgrade requires no schema migration. Core simply never writes to them.

### A8 — Export implementation: Pandoc dropped

Pandoc is GPL and cannot ship inside a proprietary module. It was also unnecessary — Pandoc solves an N×M format matrix; this is 1×5 from an already-structured source, so every target is serialization rather than parsing.

| Format | Implementation | Licence | Difficulty |
|---|---|---|---|
| HTML | `prosemirror-model` `DOMSerializer` | MIT | trivial |
| MD / TXT | `prosemirror-markdown` | MIT | trivial |
| DOCX | `docx` npm (dolanmiu) | MIT | moderate |
| PDF | generate Typst markup → `typst compile` | Apache-2.0 | moderate |
| RTF | in-house control-word serializer | — | moderate |
| EPUB | `jszip` over the Core HTML output | MIT | moderate |

Estimated ~2–4 weeks for a solid v1 across all five paid formats, RTF and EPUB taking most of it. Dropping Pandoc also removes a ~150MB system dependency from the worker image.

Known traps: RTF needs `\uN?` Unicode escapes with ASCII fallbacks plus declared font/colour tables; EPUB requires `mimetype` as the first, uncompressed zip entry and strict XHTML (validate with EPUBCheck in CI); PDF pagination stays in Typst — hand-rolling widow/orphan and hyphenation logic on `pdf-lib` is a trap.

### A9 — Licence: Elastic License 2.0, not PolyForm Noncommercial

PolyForm Noncommercial was rejected for two reasons. Its Personal Uses clause permits hobby and study use *"without any anticipated commercial application"* — but the core user is a novelist writing a book they intend to sell, so the free tier's primary use case sits in ambiguous territory. And a licence cannot gate features: under PolyForm, a self-hoster enabling paid code would not even be in violation.

**Elastic License 2.0** permits everything except providing the software to third parties as a hosted or managed service, circumventing licence-key functionality, and removing notices. Users may write and sell commercially; competitors may not resell NovelTea as a service; and the licence explicitly backs the key enforcement this needs.

Copyright Anthony Carlascio.

---

## Open questions to settle next

1. Compile job dispatch between Spring and the Node worker — Postgres `LISTEN/NOTIFY` on a `compile_job` table avoids adding a broker, but is unproven here.
2. Licence key issuance and verification — signing scheme, offline grace period, and what an expired key degrades to for a self-hoster.
3. iOS local store details: GRDB schema parity with the server subset, background sync scheduling.
4. Comments/annotations as a first-class entity — the `commenter` role introduced in A6 currently has nothing to write to.
