package com.noveltea.sync;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.noveltea.binder.BinderService;
import com.noveltea.config.LimitProperties;
import com.noveltea.model.ChangeOp;
import com.noveltea.model.EntityType;
import com.noveltea.order.FractionalIndex;
import com.noveltea.sync.entity.EntityValidationException;
import com.noveltea.sync.entity.SyncEntityWriter;
import com.noveltea.sync.dto.SyncDtos.AppliedChange;
import com.noveltea.sync.dto.SyncDtos.ChangeRecord;
import com.noveltea.sync.dto.SyncDtos.ChangeRequest;
import com.noveltea.sync.dto.SyncDtos.ConflictRecord;
import com.noveltea.sync.dto.SyncDtos.PullResponse;
import com.noveltea.sync.dto.SyncDtos.PushResponse;
import java.time.OffsetDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * The offline sync engine.
 *
 * <p>Governing rule: <b>an author's text is never destroyed to resolve a conflict.</b>
 * When the server cannot accept a document write, it keeps its own version and stores
 * the client's version as a sibling "conflict copy", so both survive and the author
 * reconciles them by hand. Nothing in this class overwrites document content that the
 * client has not demonstrably seen.
 */
@Service
public class SyncService {

    private final JdbcClient jdbc;
    private final TransactionTemplate tx;
    private final ObjectMapper mapper;
    private final SyncEntityWriter entities;
    private final LimitProperties limits;
    private final BinderService binder;

    public SyncService(
            JdbcClient jdbc,
            TransactionTemplate tx,
            ObjectMapper mapper,
            SyncEntityWriter entities,
            LimitProperties limits,
            BinderService binder) {
        this.binder = binder;
        this.jdbc = jdbc;
        this.tx = tx;
        this.mapper = mapper;
        this.entities = entities;
        this.limits = limits;
    }

    // ---------------------------------------------------------------- pull

    /**
     * Serves the change feed after {@code since}.
     *
     * <p>The {@code tx_id} predicate is not optional. Sequence values are assigned at
     * INSERT and become visible at COMMIT, so a transaction holding id 100 can commit
     * after one holding 101. Serving 101 while 100 is still in flight would let the
     * client advance its cursor past a row it will never be offered again.
     */
    public PullResponse pull(UUID projectId, long since, int limit) {
        return pull(projectId, null, since, limit);
    }

    public PullResponse pull(UUID projectId, UUID deviceId, long since, int limit) {
        return pull(projectId, deviceId, since, limit, null);
    }

    /**
     * @param clientEpoch the epoch the client last synced against, or null if it has none.
     *     A mismatch means the server moved backwards — restored from a backup — and the
     *     client is holding data the server no longer has.
     */
    public PullResponse pull(UUID projectId, UUID deviceId, long since, int limit, Long clientEpoch) {
        Objects.requireNonNull(projectId, "projectId");

        long epoch = jdbc.sql("SELECT sync_epoch FROM project WHERE id = :id")
                .param("id", projectId).query(Long.class).optional().orElse(1L);

        // A client that has never synced sends no epoch; since=0 is already a full rebuild.
        if (clientEpoch != null && clientEpoch != epoch) {
            return new PullResponse(List.of(), 0L, false, true, epoch);
        }

        // If the feed has been trimmed past this cursor, the client cannot be told what
        // changed — only that it must look again. Returning a partial feed instead would
        // leave it holding documents whose deletions it will never hear about.
        long purgedBelow = jdbc.sql("SELECT change_log_purged_below FROM project WHERE id = :id")
                .param("id", projectId).query(Long.class).optional().orElse(0L);
        // Strictly below: a cursor sitting exactly at the purge point has seen everything
        // that was removed, and asks only for rows that still exist.
        if (purgedBelow > 0 && since < purgedBelow) {
            long currentMax = jdbc.sql("""
                    SELECT coalesce(max(id), 0) FROM change_log
                     WHERE project_id = :projectId
                       AND tx_id < pg_snapshot_xmin(pg_current_snapshot())
                    """)
                    .param("projectId", projectId).query(Long.class).single();

            // Never below the purge point, even when the feed is now empty. Resuming at 0
            // would put the client straight back into a resync, forever.
            long resumeAt = Math.max(purgedBelow, currentMax);
            return new PullResponse(List.of(), resumeAt, false, true, epoch);
        }

        int capped = Math.min(Math.max(limit, 1), limits.maxSyncPageSize());

        List<Map<String, Object>> rows = jdbc.sql("""
                SELECT id, entity_type, entity_id, op, device_id, created_at
                  FROM change_log
                 WHERE project_id = :projectId
                   AND id > :since
                   AND tx_id < pg_snapshot_xmin(pg_current_snapshot())
                 ORDER BY id
                 LIMIT :limit
                """)
                .param("projectId", projectId)
                .param("since", since)
                .param("limit", capped + 1)
                .query()
                .listOfRows();

        boolean hasMore = rows.size() > capped;

        if (hasMore) {
            rows = rows.subList(0, capped);
        }

        Map<String, List<UUID>> idsByType = new LinkedHashMap<>();
        for (Map<String, Object> row : rows) {
            if (!"delete".equals(row.get("op"))) {
                idsByType
                        .computeIfAbsent((String) row.get("entity_type"), k -> new ArrayList<>())
                        .add((UUID) row.get("entity_id"));
            }
        }

        Map<String, Map<UUID, JsonNode>> hydrated = new LinkedHashMap<>();
        idsByType.forEach((type, ids) -> hydrated.put(type, loadEntities(type, ids)));

        List<ChangeRecord> changes = new ArrayList<>(rows.size());
        long latest = since;
        long bytesSoFar = 0;
        for (Map<String, Object> row : rows) {
            long id = ((Number) row.get("id")).longValue();
            String type = (String) row.get("entity_type");
            UUID entityId = (UUID) row.get("entity_id");
            JsonNode data = hydrated.getOrDefault(type, Map.of()).get(entityId);
            // Stop on bytes as well as rows. 500 rows of full documents has no predictable
            // size, and a client on mobile data has no way to refuse a page already sent.
            // Always emit at least one row, or a single oversized document would wedge the
            // feed permanently.
            long rowBytes = data == null ? 0 : data.toString().length();
            if (!changes.isEmpty() && bytesSoFar + rowBytes > limits.maxSyncPageBytes()) {
                hasMore = true;
                break;
            }
            bytesSoFar += rowBytes;

            changes.add(new ChangeRecord(
                    id,
                    type,
                    entityId,
                    (String) row.get("op"),
                    (UUID) row.get("device_id"),
                    toOffsetDateTime(row.get("created_at")),
                    data));
            latest = id;
        }

        // Records how far this device has read, which is what lets retention know when a
        // delete row is safe to remove. Only ever moves forward.
        if (deviceId != null && latest > since) {
            jdbc.sql("""
                    UPDATE device
                       SET last_seen_change_id = greatest(coalesce(last_seen_change_id, 0), :latest),
                           last_synced_at = now()
                     WHERE id = :deviceId
                    """)
                    .param("latest", latest).param("deviceId", deviceId).update();
        }

        return new PullResponse(changes, latest, hasMore, false, epoch);
    }

    /**
     * Loads current entity state as JSON.
     *
     * <p>Postgres builds the JSON with {@code to_jsonb(t)} and hands it back as text, so
     * this never touches driver-specific column types and every column — including nested
     * jsonb and uuid arrays — arrives correctly typed without per-column translation.
     */
    private Map<UUID, JsonNode> loadEntities(String entityType, List<UUID> ids) {
        if (ids == null || ids.isEmpty()) {
            return Map.of();
        }
        // Table names come from the enum, never from the request.
        String table = EntityType.fromWire(entityType).map(EntityType::table).orElse(null);
        if (table == null) {
            return Map.of();
        }
        // A snapshot is a full copy of a document. Shipping content in the feed would make
        // manual snapshots the heaviest thing a client downloads, for prose it may never
        // open — clients fetch the body from GET /snapshots/{id} when the author asks.
        // Parenthesised deliberately: `to_jsonb(t) - 'content'::text` casts the KEY, not
        // the result, and hands back jsonb instead of the text this expects.
        String projection = EntityType.SNAPSHOT.wire().equals(entityType)
                ? "(to_jsonb(t) - 'content')::text"
                : "to_jsonb(t)::text";

        List<Map<String, Object>> rows = jdbc
                .sql("SELECT id, " + projection + " AS row_json FROM " + table
                        + " t WHERE id = ANY(:ids)")
                .param("ids", ids.toArray(UUID[]::new))
                .query()
                .listOfRows();

        Map<UUID, JsonNode> out = new LinkedHashMap<>();
        for (Map<String, Object> row : rows) {
            try {
                out.put((UUID) row.get("id"), mapper.readTree((String) row.get("row_json")));
            } catch (Exception e) {
                throw new IllegalStateException("unreadable row json for " + entityType, e);
            }
        }
        return out;
    }

    private static OffsetDateTime toOffsetDateTime(Object value) {
        return value instanceof OffsetDateTime odt
                ? odt
                : ((java.sql.Timestamp) value).toInstant().atOffset(java.time.ZoneOffset.UTC);
    }

    // ---------------------------------------------------------------- push

    /**
     * Applies a batch of client changes. Each change runs in its own transaction so a
     * conflict on one never rolls back another that was accepted — the response reports
     * per-change outcomes precisely because partial success is the normal case.
     */
    public PushResponse push(UUID projectId, UUID deviceId, List<ChangeRequest> changes) {
        Objects.requireNonNull(projectId, "projectId");
        List<ChangeRequest> safeChanges = changes == null ? List.of() : changes;
        List<AppliedChange> applied = new ArrayList<>();
        List<ConflictRecord> conflicts = new ArrayList<>();

        for (ChangeRequest change : safeChanges) {
            try {
                tx.executeWithoutResult(status ->
                        applyOne(projectId, deviceId, change, applied, conflicts));
            } catch (RuntimeException e) {
                // The hand-written paths build SQL directly, so a constraint violation or a
                // rejected reparent arrives as an exception. Uncaught it escaped the loop as
                // a 500: earlier changes had already committed in their own transactions,
                // the client got no `applied` list, and its retry turned every accepted
                // change into a spurious conflict copy. One bad change is one conflict.
                conflicts.add(new ConflictRecord(
                        change.entityId(),
                        change.entityType(),
                        ConflictReason.INVALID_REQUEST,
                        null,
                        null,
                        describe(e)));
            }
        }

        Long latest = jdbc.sql("""
                SELECT coalesce(max(id), 0) FROM change_log
                 WHERE project_id = :projectId
                   AND tx_id < pg_snapshot_xmin(pg_current_snapshot())
                """)
                .param("projectId", projectId)
                .query(Long.class)
                .single();

        return new PushResponse(applied, conflicts, latest);
    }

    private void applyOne(
            UUID projectId,
            UUID deviceId,
            ChangeRequest change,
            List<AppliedChange> applied,
            List<ConflictRecord> conflicts) {

        // Parse before any lookup. Enum parsing rejects absent and unknown values in one
        // step, so a malformed change is one reported conflict rather than a 500 that
        // fails the whole batch.
        if (change == null || change.entityId() == null) {
            conflicts.add(new ConflictRecord(
                    change == null ? null : change.entityId(),
                    change == null ? null : change.entityType(),
                    ConflictReason.INVALID_REQUEST, null, null, "entityId is required"));
            return;
        }

        EntityType entityType = EntityType.fromWire(change.entityType()).orElse(null);
        ChangeOp op = ChangeOp.fromWire(change.op()).orElse(null);
        if (entityType == null || op == null) {
            conflicts.add(new ConflictRecord(
                    change.entityId(), change.entityType(), ConflictReason.INVALID_REQUEST, null, null,
                    entityType == null
                            ? "unknown entityType: " + change.entityType()
                            : "unknown op: " + change.op()));
            return;
        }

        switch (entityType) {
            case DOCUMENT -> applyDocument(projectId, deviceId, change, applied, conflicts);
            case BINDER_ITEM -> applyBinderItem(projectId, deviceId, change, applied, conflicts);
            case SNAPSHOT -> applySnapshot(projectId, deviceId, op, change, applied, conflicts);
            case COMMENT -> applyComment(projectId, deviceId, op, change, applied, conflicts);
            default -> applyDataEntity(projectId, deviceId, entityType, op, change, applied, conflicts);
        }
    }

    /**
     * Comments made offline, arriving when the device reconnects.
     *
     * <p>Authorship is taken from the pushing device's owner, never from the payload: a
     * client must not be able to attribute a remark to someone else. Editing and deleting
     * are restricted to the author for the same reason.
     */
    private void applyComment(
            UUID projectId,
            UUID deviceId,
            ChangeOp op,
            ChangeRequest change,
            List<AppliedChange> applied,
            List<ConflictRecord> conflicts) {

        UUID actor = deviceId == null ? null : jdbc
                .sql("SELECT user_id FROM device WHERE id = :id")
                .param("id", deviceId).query(UUID.class).optional().orElse(null);

        Optional<Long> current = jdbc
                .sql("SELECT version FROM comment WHERE id = :id AND project_id = :projectId")
                .param("id", change.entityId()).param("projectId", projectId)
                .query(Long.class).optional();

        switch (op) {
            case CREATE -> {
                if (current.isPresent()) {
                    applied.add(new AppliedChange(change.entityId(), "comment", current.get()));
                    return;
                }
                String documentIdText = optionalText(change, "document_id");
                String body = optionalText(change, "body");
                if (documentIdText == null || body == null || body.isBlank()) {
                    conflicts.add(new ConflictRecord(change.entityId(), "comment",
                            ConflictReason.INVALID_REQUEST, null, null,
                            "a comment needs document_id and body"));
                    return;
                }

                UUID documentId = UUID.fromString(documentIdText);
                boolean inProject = Boolean.TRUE.equals(jdbc.sql("""
                        SELECT EXISTS (SELECT 1 FROM binder_item
                                        WHERE id = :id AND project_id = :projectId)
                        """)
                        .param("id", documentId).param("projectId", projectId)
                        .query(Boolean.class).single());
                if (!inProject) {
                    conflicts.add(new ConflictRecord(change.entityId(), "comment",
                            ConflictReason.INVALID_REQUEST, null, null,
                            "document_id does not refer to anything in this project"));
                    return;
                }

                String anchor = change.data() != null && change.data().hasNonNull("anchor")
                        ? change.data().get("anchor").toString()
                        : null;

                // A reply must belong to a thread on this same document. Unvalidated, a
                // client could attach a reply to any comment id it could name — including
                // one in another account, where it would appear as though a stranger had
                // joined a private conversation. The REST path already enforces this; a
                // reply arriving over sync is the same operation.
                UUID parent = optionalUuid(change, "parent_comment_id");
                if (parent != null) {
                    boolean sameThread = Boolean.TRUE.equals(jdbc.sql("""
                            SELECT EXISTS (
                                SELECT 1 FROM comment
                                 WHERE id = :parentId
                                   AND project_id = :projectId
                                   AND document_id = :documentId
                                   AND deleted_at IS NULL)
                            """)
                            .param("parentId", parent)
                            .param("projectId", projectId)
                            .param("documentId", documentId)
                            .query(Boolean.class).single());
                    if (!sameThread) {
                        conflicts.add(new ConflictRecord(change.entityId(), "comment",
                                ConflictReason.INVALID_REQUEST, null, null,
                                "parent_comment_id does not refer to a thread on this document"));
                        return;
                    }
                }

                jdbc.sql("""
                        INSERT INTO comment
                            (id, project_id, document_id, parent_comment_id, author_user_id,
                             body, anchor, updated_by_device_id)
                        VALUES (:id, :projectId, :documentId, :parentId, :authorId, :body,
                                CAST(:anchor AS jsonb), :deviceId)
                        """)
                        .param("id", change.entityId()).param("projectId", projectId)
                        .param("documentId", documentId).param("parentId", parent)
                        .param("authorId", actor).param("body", body.trim())
                        .param("anchor", parent == null ? anchor : null)
                        .param("deviceId", deviceId)
                        .update();
                recordChange(projectId, "comment", change.entityId(), "create", deviceId);
                applied.add(new AppliedChange(change.entityId(), "comment", 1L));
            }
            case UPDATE, DELETE -> {
                if (current.isEmpty()) {
                    if (op == ChangeOp.DELETE) {
                        applied.add(new AppliedChange(change.entityId(), "comment", 0L));
                    } else {
                        conflicts.add(new ConflictRecord(change.entityId(), "comment",
                                ConflictReason.ENTITY_MISSING, null, null, null));
                    }
                    return;
                }
                UUID author = jdbc.sql("SELECT author_user_id FROM comment WHERE id = :id")
                        .param("id", change.entityId()).query(UUID.class).optional().orElse(null);
                if (author != null && !author.equals(actor)) {
                    conflicts.add(new ConflictRecord(change.entityId(), "comment",
                            ConflictReason.INVALID_REQUEST, null, null,
                            "only the author can change a comment"));
                    return;
                }

                long next = current.get() + 1;
                if (op == ChangeOp.DELETE) {
                    jdbc.sql("""
                            UPDATE comment SET deleted_at = now(), version = :next,
                                   updated_by_device_id = :deviceId
                             WHERE id = :id AND project_id = :projectId
                            """)
                            .param("next", next).param("deviceId", deviceId)
                            .param("id", change.entityId()).param("projectId", projectId).update();
                    recordChange(projectId, "comment", change.entityId(), "delete", deviceId);
                } else {
                    String body = optionalText(change, "body");
                    if (body == null || body.isBlank()) {
                        conflicts.add(new ConflictRecord(change.entityId(), "comment",
                                ConflictReason.INVALID_REQUEST, null, null, "a comment needs a body"));
                        return;
                    }
                    jdbc.sql("""
                            UPDATE comment SET body = :body, version = :next,
                                   updated_by_device_id = :deviceId
                             WHERE id = :id AND project_id = :projectId
                            """)
                            .param("body", body.trim()).param("next", next)
                            .param("deviceId", deviceId).param("id", change.entityId())
                            .param("projectId", projectId).update();
                    recordChange(projectId, "comment", change.entityId(), "update", deviceId);
                }
                applied.add(new AppliedChange(change.entityId(), "comment", next));
            }
        }
    }

    /**
     * Snapshots that arrive over sync are manual by definition.
     *
     * <p>Automatic captures never leave the device that made them, so anything pushed here
     * is a deliberate "keep this version" and is stored as such regardless of what the
     * payload claims. Snapshots are immutable: only create and delete are meaningful, and
     * an update is refused rather than silently rewriting history.
     */
    private void applySnapshot(
            UUID projectId,
            UUID deviceId,
            ChangeOp op,
            ChangeRequest change,
            List<AppliedChange> applied,
            List<ConflictRecord> conflicts) {

        Optional<Long> current = jdbc
                .sql("SELECT version FROM snapshot WHERE id = :id AND project_id = :projectId")
                .param("id", change.entityId()).param("projectId", projectId)
                .query(Long.class).optional();

        switch (op) {
            case DELETE -> {
                if (current.isPresent()) {
                    jdbc.sql("DELETE FROM snapshot WHERE id = :id AND project_id = :projectId")
                            .param("id", change.entityId()).param("projectId", projectId).update();
                    recordChange(projectId, "snapshot", change.entityId(), "delete", deviceId);
                }
                applied.add(new AppliedChange(change.entityId(), "snapshot", current.orElse(0L)));
            }
            case CREATE -> {
                if (current.isPresent()) {
                    applied.add(new AppliedChange(change.entityId(), "snapshot", current.get()));
                    return;
                }
                String documentIdText = optionalText(change, "document_id");
                if (documentIdText == null || change.data() == null || !change.data().hasNonNull("content")) {
                    conflicts.add(new ConflictRecord(change.entityId(), "snapshot",
                            ConflictReason.INVALID_REQUEST, null, null,
                            "a snapshot needs document_id and content"));
                    return;
                }
                UUID documentId = UUID.fromString(documentIdText);

                // The document must belong to the project being synced, or a caller could
                // attach history to somebody else's manuscript.
                boolean inProject = Boolean.TRUE.equals(jdbc.sql("""
                        SELECT EXISTS (SELECT 1 FROM binder_item
                                        WHERE id = :id AND project_id = :projectId)
                        """)
                        .param("id", documentId).param("projectId", projectId)
                        .query(Boolean.class).single());
                if (!inProject) {
                    conflicts.add(new ConflictRecord(change.entityId(), "snapshot",
                            ConflictReason.INVALID_REQUEST, null, null,
                            "document_id does not refer to anything in this project"));
                    return;
                }

                jdbc.sql("""
                        INSERT INTO snapshot
                            (id, project_id, document_id, content, word_count, label,
                             is_automatic, created_by_device_id, updated_by_device_id)
                        VALUES (:id, :projectId, :documentId, CAST(:content AS jsonb), :wordCount,
                                :label, false, :deviceId, :deviceId)
                        """)
                        .param("id", change.entityId())
                        .param("projectId", projectId)
                        .param("documentId", documentId)
                        .param("content", change.data().get("content").toString())
                        .param("wordCount", optionalInt(change, "word_count", 0))
                        .param("label", optionalText(change, "label"))
                        .param("deviceId", deviceId)
                        .update();
                recordChange(projectId, "snapshot", change.entityId(), "create", deviceId);
                applied.add(new AppliedChange(change.entityId(), "snapshot", 1L));
            }
            case UPDATE -> conflicts.add(new ConflictRecord(change.entityId(), "snapshot",
                    ConflictReason.INVALID_REQUEST, null, null,
                    "snapshots are immutable; create a new one instead"));
        }
    }

    /**
     * Taxonomy, metadata, collections and presets: pure data, no conflict copies.
     *
     * <p>These use last-write-wins on a version check, like tree structure. Losing a label
     * rename is recoverable in seconds; only document prose justifies a conflict copy.
     * Validation happens before any statement is built, so a change that would violate a
     * CHECK constraint is reported rather than raised.
     */
    private void applyDataEntity(
            UUID projectId,
            UUID deviceId,
            EntityType entityType,
            ChangeOp op,
            ChangeRequest change,
            List<AppliedChange> applied,
            List<ConflictRecord> conflicts) {

        if (!entities.supports(entityType)) {
            conflicts.add(new ConflictRecord(
                    change.entityId(), entityType.wire(), ConflictReason.NOT_IMPLEMENTED, null, null, null));
            return;
        }

        Optional<Long> current = entities.currentVersion(projectId, entityType, change.entityId());

        try {
            switch (op) {
                case DELETE -> {
                    // Idempotent: deleting something already gone is success.
                    if (current.isPresent()) {
                        entities.delete(projectId, entityType, change.entityId(), deviceId);
                    }
                    applied.add(new AppliedChange(change.entityId(), entityType.wire(), current.orElse(0L)));
                }
                case CREATE -> {
                    if (current.isPresent()) {
                        // Re-delivery of a create whose response was lost: accept quietly.
                        applied.add(new AppliedChange(change.entityId(), entityType.wire(), current.get()));
                        return;
                    }
                    long version = entities.create(
                            projectId, deviceId, entityType, change.entityId(), change.data());
                    recordChange(projectId, entityType.wire(), change.entityId(), "create", deviceId);
                    applied.add(new AppliedChange(change.entityId(), entityType.wire(), version));
                }
                case UPDATE -> {
                    if (current.isEmpty()) {
                        conflicts.add(new ConflictRecord(change.entityId(), entityType.wire(),
                                ConflictReason.ENTITY_MISSING, null, null, null));
                        return;
                    }
                    long version = entities.update(projectId, deviceId, entityType, change.entityId(),
                            current.get(), change.data());
                    recordChange(projectId, entityType.wire(), change.entityId(), "update", deviceId);
                    applied.add(new AppliedChange(change.entityId(), entityType.wire(), version));
                }
            }
        } catch (EntityValidationException e) {
            // Malformed input never reaches the database, and never fails its neighbours.
            conflicts.add(new ConflictRecord(change.entityId(), entityType.wire(),
                    ConflictReason.INVALID_REQUEST, null, null, e.getMessage()));
        }
    }

    // ------------------------------------------------------------ document

    private void applyDocument(
            UUID projectId,
            UUID deviceId,
            ChangeRequest change,
            List<AppliedChange> applied,
            List<ConflictRecord> conflicts) {

        Optional<Long> current = jdbc
                // document has no project_id; it is scoped through its binder_item. Without
                // this, knowing an id is enough to overwrite another author's chapter.
                .sql("""
                        SELECT d.version FROM document d
                          JOIN binder_item b ON b.id = d.id
                         WHERE d.id = :id AND b.project_id = :projectId
                         FOR UPDATE OF d
                        """)
                .param("id", change.entityId())
                .param("projectId", projectId)
                .query(Long.class)
                .optional();

        if ("delete".equals(change.op())) {
            // Idempotent: deleting something already gone is success, not an error.
            // The client's create+delete collapse depends on this.
            if (current.isPresent()) {
                softDeleteItem(projectId, deviceId, change.entityId());
            }
            applied.add(new AppliedChange(change.entityId(), "document", current.orElse(0L)));
            return;
        }

        String content = requiredJson(change, "content");

        if (current.isEmpty()) {
            if ("create".equals(change.op())) {
                // The binder_item must already exist (pushed in the same batch or earlier).
                Optional<String> title = jdbc
                        .sql("SELECT title FROM binder_item WHERE id = :id AND project_id = :projectId")
                        .param("id", change.entityId())
                        .param("projectId", projectId)
                        .query(String.class)
                        .optional();
                if (title.isEmpty()) {
                    conflicts.add(new ConflictRecord(
                            change.entityId(), "document", ConflictReason.ENTITY_MISSING, null, null));
                    return;
                }
                insertDocument(projectId, deviceId, change.entityId(), content, change);
                applied.add(new AppliedChange(change.entityId(), "document", 1L));
                return;
            }
            // An update for a document the server does not have. Rather than discard the
            // author's text, preserve it as a new item and let them decide.
            UUID copyId = createOrphanCopy(projectId, deviceId, change.entityId(), content);
            conflicts.add(new ConflictRecord(
                    change.entityId(), "document", ConflictReason.ENTITY_MISSING, copyId, null));
            return;
        }

        long serverVersion = current.get();
        Long base = change.baseVersion();

        if ("create".equals(change.op())) {
            String existing = jdbc.sql("""
                    SELECT d.content::text FROM document d
                      JOIN binder_item b ON b.id = d.id
                     WHERE d.id = :id AND b.project_id = :projectId
                    """)
                    .param("id", change.entityId())
                    .param("projectId", projectId)
                    .query(String.class)
                    .single();
            if (jsonEquals(existing, content)) {
                // Re-delivery of a create whose response was lost. Accept silently.
                applied.add(new AppliedChange(change.entityId(), "document", serverVersion));
            } else {
                UUID copyId = createConflictCopy(projectId, deviceId, change.entityId(), content, base);
                conflicts.add(new ConflictRecord(
                        change.entityId(), "document", ConflictReason.DUPLICATE_CREATE, copyId, serverVersion));
            }
            return;
        }

        if (base != null && base == serverVersion) {
            long next = serverVersion + 1;
            jdbc.sql("""
                    UPDATE document
                       SET content = CAST(:content AS jsonb),
                           search_text = :searchText,
                           word_count = :wordCount,
                           version = :next,
                           updated_by_device_id = :deviceId,
                           updated_at = now()
                     WHERE id = :id
                       AND id IN (SELECT id FROM binder_item WHERE project_id = :projectId)
                    """)
                    .param("projectId", projectId)
                    .param("content", content)
                    .param("searchText", optionalText(change, "search_text"))
                    .param("wordCount", optionalInt(change, "word_count", 0))
                    .param("next", next)
                    .param("deviceId", deviceId)
                    .param("id", change.entityId())
                    .update();
            recordChange(projectId, "document", change.entityId(), "update", deviceId);
            applied.add(new AppliedChange(change.entityId(), "document", next));
            return;
        }

        // Stale base_version: someone else moved the document on. Never merge prose.
        UUID copyId = createConflictCopy(projectId, deviceId, change.entityId(), content, base);
        conflicts.add(new ConflictRecord(
                change.entityId(), "document", ConflictReason.VERSION_MISMATCH, copyId, serverVersion));
    }

    // ---------------------------------------------------------- binder_item

    private void applyBinderItem(
            UUID projectId,
            UUID deviceId,
            ChangeRequest change,
            List<AppliedChange> applied,
            List<ConflictRecord> conflicts) {

        Optional<Long> current = jdbc
                .sql("SELECT version FROM binder_item WHERE id = :id AND project_id = :projectId FOR UPDATE")
                .param("id", change.entityId())
                .param("projectId", projectId)
                .query(Long.class)
                .optional();

        if ("delete".equals(change.op())) {
            if (current.isPresent()) {
                softDeleteItem(projectId, deviceId, change.entityId());
            }
            applied.add(new AppliedChange(change.entityId(), "binder_item", current.orElse(0L)));
            return;
        }

        if (current.isEmpty()) {
            if (!"create".equals(change.op())) {
                conflicts.add(new ConflictRecord(
                        change.entityId(), "binder_item", ConflictReason.ENTITY_MISSING, null, null));
                return;
            }
            jdbc.sql("""
                    INSERT INTO binder_item
                        (id, project_id, parent_id, type, title, order_key, version, updated_by_device_id)
                    VALUES (:id, :projectId, :parentId, :type, :title, :orderKey, 1, :deviceId)
                    """)
                    .param("id", change.entityId())
                    .param("projectId", projectId)
                    .param("parentId", optionalUuid(change, "parent_id"))
                    .param("type", requiredText(change, "type"))
                    .param("title", requiredText(change, "title"))
                    .param("orderKey", requiredText(change, "order_key"))
                    .param("deviceId", deviceId)
                    .update();
            recordChange(projectId, "binder_item", change.entityId(), "create", deviceId);
            applied.add(new AppliedChange(change.entityId(), "binder_item", 1L));
            return;
        }

        // A reparent over sync is the same operation as BinderService.move and must obey
        // the same rules. Without this a single push could point an item at its own
        // descendant: the subtree then has no root, renders nowhere on any device, and the
        // change propagates to all of them.
        UUID requestedParent = optionalUuid(change, "parent_id");
        if (requestedParent != null) {
            binder.requireReparentIsSafe(projectId, change.entityId(), requestedParent);
        }

        // Tree structure is last-write-wins: a lost rename or reorder is recoverable in
        // seconds, so it does not justify the friction of a conflict copy. Document
        // CONTENT never takes this path.
        long next = current.get() + 1;
        jdbc.sql("""
                UPDATE binder_item
                   SET title = coalesce(:title, title),
                       parent_id = coalesce(:parentId, parent_id),
                       order_key = coalesce(:orderKey, order_key),
                       version = :next,
                       updated_by_device_id = :deviceId,
                       updated_at = now()
                 -- Scoped as well as gated. The SELECT above already refuses a foreign
                 -- item, so this is defence in depth — but the gate and the write being
                 -- separate statements is exactly the shape that rots when someone adds a
                 -- path that skips one of them.
                 WHERE id = :id AND project_id = :projectId
                """)
                .param("projectId", projectId)
                .param("title", optionalText(change, "title"))
                .param("parentId", optionalUuid(change, "parent_id"))
                .param("orderKey", optionalText(change, "order_key"))
                .param("next", next)
                .param("deviceId", deviceId)
                .param("id", change.entityId())
                .update();
        recordChange(projectId, "binder_item", change.entityId(), "update", deviceId);
        applied.add(new AppliedChange(change.entityId(), "binder_item", next));
    }

    // ------------------------------------------------------- conflict copies

    /**
     * Stores the client's rejected content as a sibling of the original.
     *
     * <p>The original is left untouched. Both versions exist afterwards; the author
     * reconciles them. This is the mechanism that makes "never lose an author's work"
     * true rather than aspirational.
     */
    private UUID createConflictCopy(UUID projectId, UUID deviceId, UUID originalId, String content, Long baseVersion) {
        Map<String, Object> original = jdbc
                .sql("""
                        SELECT project_id, parent_id, title, order_key FROM binder_item
                         WHERE id = :id AND project_id = :projectId
                        """)
                .param("id", originalId)
                .param("projectId", projectId)
                .query()
                .singleRow();

        UUID parentId = (UUID) original.get("parent_id");
        String orderKey = (String) original.get("order_key");
        String title = (String) original.get("title");

        UUID copyId = UUID.randomUUID();
        insertCopy(projectId, parentId, copyId, conflictTitle(title, deviceId),
                nextKeyAfter(projectId, parentId, orderKey), deviceId, originalId, baseVersion);
        insertDocumentRaw(projectId, deviceId, copyId, content);
        return copyId;
    }

    /** Preserves content whose original binder_item no longer exists, at project root. */
    private UUID createOrphanCopy(UUID projectId, UUID deviceId, UUID missingId, String content) {
        UUID copyId = UUID.randomUUID();
        String title = "Recovered document " + missingId.toString().substring(0, 8);
        insertCopy(projectId, null, copyId, conflictTitle(title, deviceId),
                nextKeyAfter(projectId, null, null), deviceId, null, null);
        insertDocumentRaw(projectId, deviceId, copyId, content);
        return copyId;
    }

    private void insertCopy(UUID projectId, UUID parentId, UUID id, String title, String orderKey,
            UUID deviceId, UUID conflictOfId, Long conflictBaseVersion) {
        jdbc.sql("""
                INSERT INTO binder_item
                    (id, project_id, parent_id, type, title, order_key, version, updated_by_device_id,
                     conflict_of_id, conflict_base_version, conflict_created_at)
                VALUES (:id, :projectId, :parentId, 'document', :title, :orderKey, 1, :deviceId,
                        :conflictOfId, :conflictBaseVersion, now())
                """)
                .param("id", id)
                .param("projectId", projectId)
                .param("parentId", parentId)
                .param("title", title)
                .param("orderKey", orderKey)
                .param("deviceId", deviceId)
                .param("conflictOfId", conflictOfId)
                .param("conflictBaseVersion", conflictBaseVersion)
                .update();
        recordChange(projectId, "binder_item", id, "create", deviceId);
    }

    /**
     * Finds a free ordering key after {@code afterKey} among that parent's children.
     *
     * <p>Falls back to appending at the end if the neighbour keys cannot produce a
     * midpoint — losing the copy's position is acceptable; losing the copy is not.
     */
    private String nextKeyAfter(UUID projectId, UUID parentId, String afterKey) {
        String nextSibling = jdbc.sql("""
                SELECT min(order_key) FROM binder_item
                 WHERE project_id = :projectId
                   AND parent_id IS NOT DISTINCT FROM CAST(:parentId AS uuid)
                   AND (:afterKey::text IS NULL OR order_key > :afterKey)
                """)
                .param("projectId", projectId)
                .param("parentId", parentId)
                .param("afterKey", afterKey)
                .query(String.class)
                .optional()
                .orElse(null);

        try {
            return FractionalIndex.between(afterKey, nextSibling);
        } catch (IllegalArgumentException e) {
            String maxKey = jdbc.sql("""
                    SELECT max(order_key) FROM binder_item
                     WHERE project_id = :projectId AND parent_id IS NOT DISTINCT FROM CAST(:parentId AS uuid)
                    """)
                    .param("projectId", projectId)
                    .param("parentId", parentId)
                    .query(String.class)
                    .optional()
                    .orElse(null);
            try {
                return FractionalIndex.between(maxKey, null);
            } catch (IllegalArgumentException fatal) {
                return FractionalIndex.first() + UUID.randomUUID().toString().substring(0, 8);
            }
        }
    }

    private String conflictTitle(String original, UUID deviceId) {
        String stamp = OffsetDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm"));
        String device = deviceId == null ? "unknown device" : deviceId.toString().substring(0, 8);
        return original + " (Conflicted Copy, " + device + ", " + stamp + ")";
    }

    // ------------------------------------------------------------- helpers

    private void insertDocument(UUID projectId, UUID deviceId, UUID id, String content, ChangeRequest change) {
        jdbc.sql("""
                INSERT INTO document (id, content, search_text, word_count, version, updated_by_device_id)
                VALUES (:id, CAST(:content AS jsonb), :searchText, :wordCount, 1, :deviceId)
                """)
                .param("id", id)
                .param("content", content)
                .param("searchText", optionalText(change, "search_text"))
                .param("wordCount", optionalInt(change, "word_count", 0))
                .param("deviceId", deviceId)
                .update();
        recordChange(projectId, "document", id, "create", deviceId);
    }

    private void insertDocumentRaw(UUID projectId, UUID deviceId, UUID id, String content) {
        jdbc.sql("""
                INSERT INTO document (id, content, version, updated_by_device_id)
                VALUES (:id, CAST(:content AS jsonb), 1, :deviceId)
                """)
                .param("id", id)
                .param("content", content)
                .param("deviceId", deviceId)
                .update();
        recordChange(projectId, "document", id, "create", deviceId);
    }

    /**
     * Tombstones an item <b>and everything beneath it</b>.
     *
     * <p>Deleting only the named row left its children live but unreachable — their parent
     * was gone, so no client could render them — and the retention sweep later hard-deleted
     * them through the foreign key cascade with no tombstone and no feed row at all. Every
     * device that had those documents would keep them forever while the server had none,
     * and nothing would ever say so.
     */
    private void softDeleteItem(UUID projectId, UUID deviceId, UUID id) {
        List<UUID> deleted = jdbc.sql("""
                WITH RECURSIVE subtree AS (
                    SELECT id FROM binder_item WHERE id = :id AND project_id = :projectId
                    UNION ALL
                    SELECT b.id FROM binder_item b JOIN subtree s ON b.parent_id = s.id
                )
                UPDATE binder_item
                   SET deleted_at = now(), version = version + 1, updated_by_device_id = :deviceId
                 WHERE id IN (SELECT id FROM subtree) AND deleted_at IS NULL
                RETURNING id
                """)
                .param("deviceId", deviceId)
                .param("id", id)
                .param("projectId", projectId)
                .query(UUID.class)
                .list();

        // One feed row per item: a client learns about each document it holds, not just the
        // folder that contained them.
        for (UUID deletedId : deleted) {
            recordChange(projectId, "binder_item", deletedId, "delete", deviceId);
        }
    }

    /** Appends the feed row. Called inside the same transaction as the write, always. */
    private void recordChange(UUID projectId, String entityType, UUID entityId, String op, UUID deviceId) {
        jdbc.sql("""
                INSERT INTO change_log (project_id, entity_type, entity_id, op, device_id)
                VALUES (:projectId, :entityType, :entityId, :op, :deviceId)
                """)
                .param("projectId", projectId)
                .param("entityType", entityType)
                .param("entityId", entityId)
                .param("op", op)
                .param("deviceId", deviceId)
                .update();
    }

    /** Only messages we authored are echoed; database text carries SQL and column names. */
    private static String describe(RuntimeException e) {
        for (Throwable cause = e; cause != null && cause != cause.getCause(); cause = cause.getCause()) {
            if (cause instanceof com.noveltea.binder.BinderExceptions.BinderCycle
                    || cause instanceof com.noveltea.binder.BinderExceptions.CrossProjectMove
                    || cause instanceof IllegalArgumentException) {
                return cause.getMessage();
            }
        }
        return "this change could not be applied";
    }

    private boolean jsonEquals(String a, String b) {
        try {
            return mapper.readTree(a).equals(mapper.readTree(b));
        } catch (Exception e) {
            return false;
        }
    }

    private String requiredJson(ChangeRequest change, String field) {
        JsonNode node = change.data() == null ? null : change.data().get(field);
        if (node == null || node.isNull()) {
            throw new IllegalArgumentException("missing required field: " + field);
        }
        return node.toString();
    }

    private String requiredText(ChangeRequest change, String field) {
        String value = optionalText(change, field);
        if (value == null) {
            throw new IllegalArgumentException("missing required field: " + field);
        }
        return value;
    }

    private String optionalText(ChangeRequest change, String field) {
        JsonNode node = change.data() == null ? null : change.data().get(field);
        return node == null || node.isNull() ? null : node.asText();
    }

    private UUID optionalUuid(ChangeRequest change, String field) {
        String value = optionalText(change, field);
        return value == null ? null : UUID.fromString(value);
    }

    private int optionalInt(ChangeRequest change, String field, int fallback) {
        JsonNode node = change.data() == null ? null : change.data().get(field);
        return node == null || node.isNull() ? fallback : node.asInt();
    }
}
