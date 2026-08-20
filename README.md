# NovelTea — server

Self-hosted, offline-first writing software for long-form fiction. This repository is the
backend: a Spring Boot API, a Postgres schema, a compile worker, and the packages the
clients share. The front end lives in a separate repository.

Every client keeps a **complete local replica** and works fully disconnected. The server is
a synchronisation point and a compile engine, not the place the work lives — which is the
single fact that explains most of the design decisions below.

---

## Running it

```bash
docker compose up -d                                   # Postgres 18
export NOVELTEA_JWT_SECRET=$(openssl rand -base64 48)  # no default; startup fails without it
./gradlew :api:bootRun                                 # API on :8080

export NOVELTEA_DB_URL=postgresql://noveltea:noveltea@localhost:5432/noveltea
npm install && npm start -w @noveltea/worker           # compile worker
```

Health: `GET /health` (liveness) and `GET /health/ready` (touches the database).

```bash
./gradlew :api:test        # Java suite — real Postgres, in an isolated schema
npm test                   # every Node workspace
./gradlew :api:update      # apply Liquibase changesets
```

### Configuration worth knowing

| Setting | Why |
|---|---|
| `NOVELTEA_JWT_SECRET` | Required, ≥32 bytes. No default on purpose: a fallback signing key is a fallback that reaches production. |
| `spring.mail.host` | Unset means reset links and notifications are **written to the log** instead of sent. Fine for one operator reading their own logs; not otherwise. |
| `noveltea.cors.allowed-origins` | Empty means same-origin only. A browser client on another origin must be listed. |
| `NOVELTEA_EXPORT_PATH` | Where `server` exports land — mount a volume here. Never purged. |
| `NOVELTEA_STAGING_PATH` | Where `download` exports wait. Purged after their TTL; container-local scratch is fine. |

---

## Layout

```
api/                    Spring Boot: auth, sync, binder, merge, snapshot, compile,
                        comment, search, retention, project
worker/                 Node service that runs compile jobs
packages/client-db/     SQLite schema + migrations for every client
packages/compile/       ProseMirror → txt/md/html
docs/design/            The original data model and API sketch, with amendments
```

`CLAUDE.md` documents the architecture and the invariants the code is expected to hold; it
is the authority where it and the design doc disagree.

---

## The rules that matter

Most of these were learned by getting them wrong first.

- **Never merge prose.** A conflicting document write produces a *conflict copy* — a sibling
  holding the losing version — and the author reconciles it. Both versions always survive.
- **The JVM never interprets document structure.** Content is opaque `jsonb` to Java;
  anything that must read ProseMirror belongs in `packages/compile`.
- **Every push write is scoped to its project.** Authorizing the project in the path proves
  the caller may write *there*, not that the id they sent belongs to it.
- **Deletes are announced.** A client learns something is gone only from its `change_log`
  row, so nothing is removed without one — including every descendant of a deleted folder.
- **Absence beats forbidden.** A resource the caller may not see returns 404. Unauthenticated
  is 401, so clients know whether refreshing a token would help.
- **Retention only purges what every live device has already read.**

## Backups

**Back up the database. Nothing else is the author's work** — exports regenerate and the
staging directory is transient.

```bash
pg_dump --format=custom noveltea > noveltea-$(date +%F).dump
```

**After restoring, bump the epoch.** Not optional:

```sql
UPDATE project SET sync_epoch = sync_epoch + 1;
```

A restore rewinds `change_log` while devices keep cursors past the restored maximum.
Without a bump they pull, receive nothing, and conclude they are current while the server
has silently rolled back underneath them.

---

## Known issues

Honest status. None is fixed by pretending otherwise.

### Operational

| Issue | Impact |
|---|---|
| **Rate limiting is in-memory** | Limits apply *per instance*; behind two replicas an attacker gets double. Needs a shared store before scaling out. |
| **`X-Forwarded-For` is trusted for rate limiting** | Spoofing the header evades the per-IP limit. It slows guessing; it is not the only defence. |
| **Liquibase adds ~10s to startup** | Fixed overhead, not proportional to changeset count. Migrations could move to a deploy step instead. |
| **`maxPushBatchSize` is unenforced** | A push batch is unbounded in row count. Enforcing it needs a client that knows how to split, so it is a protocol decision. |
| **`RequestSizeLimitFilter` reads `Content-Length` only** | A chunked request has none and bypasses the 413 ceiling. |

### Correctness and coverage

| Issue | Impact |
|---|---|
| **Concurrent moves can still build a cycle** | `move` checks for a cycle then updates without locking either item. Two devices moving A under B and B under A simultaneously can both pass and commit, detaching a subtree. The existing concurrency test races the same item, which cannot cycle. |
| **`IdorSweepTest` skips routes it cannot fill** | Path variables other than project/item/copy/device are silently skipped, so eight routes are unswept. Their checks were verified by hand; nothing would catch the next one. |
| **Restore and merge do not refresh `search_text`** | After restoring a snapshot, search still matches the discarded revision's words. Only clients can produce `search_text`, so it stays stale until that document is next edited. |
| **`maxDocumentBytes` is unenforced on the document path** | It applies to spec-driven entities only; a single document can reach the 32MB request ceiling. |
| **`maxTitleLength` is unused** | A binder title created through sync is unbounded. |
| **Snapshot pruning can pick the wrong row** | `restore` captures the pre-restore state and prunes in one transaction, where `now()` is constant, so ties order arbitrarily. |
| **Comment notification sends inside the transaction** | An unreachable SMTP server holds a write transaction open for the connect timeout. |

### Not built

- **Sharing and visibility filtering.** `SharingProvider` is an extension point Core never
  implements; the tables exist so a licence upgrade needs no migration. In a Core build the
  owner is the only user, so comment notification is effectively unreachable.
- **Commercial export formats** (`rtf`, `docx`, `odt`, `epub`, `pdf`) and cloud storage.
  Core refuses them with `501` rather than pretending they do not exist.

---

## Testing

Around 330 Java tests and 130 Node tests, against real Postgres and real SQLite — never an
in-memory substitute, because the parts most likely to be wrong (MVCC visibility, partial
indexes, `FOR UPDATE SKIP LOCKED`) are exactly what a substitute replaces.

**Tests are checked by mutation, not by counting them.** Break the behaviour deliberately and
confirm the suite goes red. This has repeatedly exposed tests that could not fail — one
"trashed items are excluded" test built its fixture with a tombstone rather than a trashed
item, and passed against genuinely broken behaviour for as long as it existed.

## Licence

Elastic License 2.0 — see `LICENSE.md`. Free to self-host and use commercially; you may not
offer it to others as a hosted service. Dependencies must be MIT, Apache-2.0 or BSD.
