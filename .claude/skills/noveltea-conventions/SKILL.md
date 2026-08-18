---
name: noveltea-conventions
description: Coding conventions and correctness rules for the NovelTea codebase. Load before writing or reviewing any code that touches persistence, the sync protocol, change_log, binder ordering, Liquibase changesets, sharing/authorization, or the export pipeline — i.e. essentially any server or worker change. Covers the mutation contract, optimistic concurrency, visibility filtering, edition boundaries, and per-area test requirements.
---

# NovelTea coding conventions

`CLAUDE.md` says *what* the system is. This says *how to write code in it without breaking sync.*

The failure mode this codebase is prone to is **silent divergence**: a write that succeeds on the server but never reaches other devices, or reaches them out of order. Users notice weeks later, as missing prose. Almost every rule below exists to prevent that.

---

## 1. Before writing a feature: decide its edition

Core (this repo, ELv2) or Pro (private repo)? Decide first — see the table in `CLAUDE.md`.

If Pro: the work here is limited to defining or extending an interface (`ExportProvider`, `SharingProvider`) plus a `501` fallback. **Do not write the implementation in this repo, even behind a flag or disabled by default.** Code that ships here is code a self-hoster may legitimately run.

If a Pro feature needs new tables, the *migration still lands in Core* (see A7) so that upgrading a license never requires a schema change. Core writes nothing to them.

---

## 2. The mutation contract

Every mutation to a synced entity (`binder_item`, `document`, `label`, `status`, custom metadata) must do all four of these **in a single transaction**:

1. Verify `base_version` against the stored `version` → `409` on mismatch (§3).
2. Increment `version`.
3. Set `updated_by_device_id` from the authenticated device.
4. Append a `change_log` row.

```java
@Transactional
public BinderItem rename(UUID id, String title, long baseVersion, UUID deviceId) {
    var item = repo.findForUpdate(id).orElseThrow(NotFoundException::new);
    if (item.getVersion() != baseVersion) throw new VersionConflictException(item.getVersion());
    item.setTitle(title);
    item.setVersion(item.getVersion() + 1);
    item.setUpdatedByDeviceId(deviceId);
    changeLog.record(BINDER_ITEM, id, UPDATE, deviceId);   // never optional
    return repo.save(item);
}
```

**Step 4 is the one that gets forgotten, and omitting it is invisible in tests that only assert on the HTTP response.** A missing `change_log` row means every other device silently never learns the change happened.

Funnel all synced writes through a repository/service layer that does this centrally, so it cannot be skipped. Do not scatter `changeLog.record` calls across controllers. Any new synced entity type needs a `change_log` `entity_type` value *and* a visibility rule (§4) in the same change.

---

## 3. Optimistic concurrency and conflicts

- Version mismatch on **`document.content`** → do **not** merge. Create a sibling `binder_item` titled `<title> (Conflicted Copy, <device>, <timestamp>)` holding the client's version, return it in the sync response's `conflicts[]` as `conflict_copy_id`. This is a permanent design decision, not a v1 shortcut.
- Version mismatch on **tree structure** (move/rename/reorder) → last-write-wins by server timestamp. No conflict copy.
- A conflict copy is itself a mutation: it needs its own `change_log` rows (§2).

## 4. Reading `change_log` — two non-negotiables

**Never write `WHERE id > :since` on its own.** Sequence values become visible at commit, not at insert, so a lower id can appear after a higher one and a client will skip it permanently:

```sql
WHERE project_id = :projectId
  AND id > :since
  AND id < pg_snapshot_xmin(pg_current_snapshot())   -- required, always
```

Keep this in exactly one query method. If it appears in two places, one of them will eventually lose the second clause.

**Filter visibility in SQL, not in application code.** When a `SharingProvider` is present, join against `project_member` and restrict to the caller's role and `scope_binder_item_id` subtree in the query itself. Fetching the project feed and filtering it in Java means the process already holds data the caller may not see.

Error semantics for out-of-scope entities: return **`404`, not `403`**. A `403` confirms the item exists, which leaks the structure of a manuscript a scoped guest was never granted.

Status codes: `409` version conflict · `501` unimplemented (Pro absent) · `404` absent *or* out of scope · `403` authenticated, in scope, insufficient role.

## 5. Binder ordering

`order_index` is a **lexicographic string**. Never do arithmetic on it — no midpoint averaging, no float parsing, no `ORDER BY order_index::float`. Use the `fractional-indexing` algorithm's `generateKeyBetween(a, b)` and let Postgres and SQLite sort it as text. Both clients and server must use the same implementation, or orderings diverge across devices.

## 6. Liquibase changesets

- **One logical change per changeset.** Never edit a changeset that has been merged — Liquibase checksums it and deployed databases will fail on startup. Add a new changeset instead.
- Always write an explicit `rollback`. Postgres DDL is transactional; there is no excuse for an irreversible changeset.
- SQL-formatted changelogs only. No XML abstraction — it fights `jsonb`, GIN, and partial indexes.
- Changeset id convention: `YYYYMMDD-NN-short-description`.
- Any index supporting a smart-collection query or manuscript search should be GIN; state which query it serves in a comment.

## 7. The ProseMirror boundary

In Java, `document.content` is opaque. Store and pass it through; do not deserialize it into a tree, do not count words, do not inspect node types, do not validate its shape. If a task requires knowing what is inside the document, it belongs in the Node worker.

`word_count` is computed by the worker and written back. Spring never derives it.

In the worker, parse through `prosemirror-model` against the shared schema — never treat the JSON as an untyped object graph, or the schema stops being a single source of truth.

## 8. Exporters

Implement the `ExportProvider` contract (`supports(format)` / `export(doc, config)`). Every format derives from the Core HTML serializer rather than re-walking the document.

- **Golden-file tests** per format: fixture ProseMirror doc → expected output, committed. Run EPUBCheck against EPUB output in CI.
- **Output must be byte-deterministic** or golden tests flake: fix zip entry timestamps in EPUB/DOCX, and do not embed generation dates unless the fixture pins them.
- EPUB: `mimetype` must be the first zip entry and *stored uncompressed*. Content must be well-formed XHTML.
- RTF: `\uN?` escapes with an ASCII fallback char; declare font and colour tables up front.
- PDF: pagination stays in Typst. Do not reimplement widow/orphan or hyphenation logic.
- Dependencies must be MIT/Apache-2.0/BSD. Copyleft cannot ship in Pro.

## 9. Tests that must exist

| Area | Required coverage |
|---|---|
| Any synced mutation | A `change_log` row is appended, with the right `entity_type`/`op`/`device_id` |
| Sync cursor | Two concurrent transactions committing out of sequence order — assert no row is skipped |
| Document conflict | Conflicted copy created; original content never mutated |
| Tree conflict | Last-write-wins, no conflict copy |
| Scoped sharing | A subtree-scoped viewer's feed excludes out-of-scope rows; out-of-scope fetch returns 404 |
| No `SharingProvider` | Sync works end to end on the single-owner path |
| Exporters | Golden file per format; EPUBCheck passes |

## 10. Review checklist

- [ ] Every synced write bumps `version`, sets `updated_by_device_id`, and appends `change_log`, all in one transaction
- [ ] No new `change_log` read without the `pg_snapshot_xmin` bound
- [ ] New `change_log` entity types have a visibility rule
- [ ] Out-of-scope access returns 404, not 403
- [ ] No arithmetic on `order_index`
- [ ] No ProseMirror traversal in Java
- [ ] Merged Liquibase changesets untouched; new ones have rollbacks
- [ ] No Pro implementation in this repo
- [ ] New dependencies are permissively licensed
