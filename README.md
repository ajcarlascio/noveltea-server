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
| `NOVELTEA_API_DOCS_ENABLED` | Off by default. Set `true` to serve the live spec and Swagger UI — see below. |

---

## API documentation

The OpenAPI spec lives at **`docs/api/openapi.yaml`**, checked into the repo rather than
left as a build artifact so a spec change shows up in code review like any other diff.
It is regenerated automatically as part of `./gradlew build` (and directly via
`./gradlew :api:generateOpenApiDocs`): the `org.springdoc.openapi-gradle-plugin` boots the
app in a forked JVM, asks it for `/v3/api-docs`, writes the result to that file, and stops
it. Commit the regenerated file along with whatever controller change produced it.

**That step boots the real application, so Postgres must be running** — `docker compose up
-d` first. It is not the Testcontainers database the tests use; the forked app reads
`application.yml` like any other run. Without a database the plugin waits out its timeout
and fails with nothing useful in the message, which looks like a plugin problem and is not.

You are not required to regenerate on a machine without a database, because the spec cannot
drift silently: `OpenApiSpecFreshnessTest` reads the routes out of the live handler mapping
and compares them to the checked-in file, in both directions. A controller method added
without regenerating fails that test, and so does a spec entry for a route that no longer
exists. The file is declared as an input to the `test` task, so editing it alone still
re-runs the check rather than leaving Gradle to call the task up to date.

The same annotations serve the spec live, and Swagger UI on top of it — both are **off by
default**, because `/v3/api-docs` is a complete map of every route and schema, and a
self-hosted instance may face the open internet. Turn them on with:

```bash
export NOVELTEA_API_DOCS_ENABLED=true
```

then visit `/v3/api-docs` (JSON) or `/swagger-ui.html` (interactive). Both paths are
`permitAll` in `SecurityConfig` and deliberately outside `/api/v1`, so they don't touch the
invariant `IdorSweepTest` enforces (nothing under that prefix is reachable without a token).

---

## Layout

```
api/                    Spring Boot: auth, sync, binder, merge, snapshot, compile,
                        comment, search, retention, project
worker/                 Node service that runs compile jobs
packages/client-db/     SQLite schema + migrations for every client
packages/compile/       ProseMirror → txt/md/html
docs/design/            The original data model and API sketch, with amendments
docs/api/               Generated OpenAPI spec (openapi.yaml) — see "API documentation" above
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

## Rebuilding a client

A client whose cursor has fallen behind the feed's purge point — retention removed the
rows, or the project was restored from an older backup — is told to resync, and rebuilds
from two endpoints:

```
GET /api/v1/projects/{id}/binder                 the tree
GET /api/v1/projects/{id}/documents?after=&limit= the bodies, paged
```

The second exists only for this. The change feed carries document content, but only on
rows appended since a cursor, so a client rebuilding from nothing cannot otherwise
recover a document nobody has edited recently. Page until `hasMore` is false, passing
each `nextCursor` back as `after`.

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
