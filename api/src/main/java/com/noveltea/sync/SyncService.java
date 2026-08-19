package com.noveltea.sync;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.noveltea.order.FractionalIndex;
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

    /** Entity types the push path can write. See {@link ConflictReason#NOT_IMPLEMENTED}. */
    private static final Set<String> WRITABLE = Set.of("binder_item", "document");

    /** entity_type -> table. Fixed map; never interpolate a caller-supplied name. */
    private static final Map<String, String> TABLES = Map.of(
            "binder_item", "binder_item",
            "document", "document",
            "taxonomy", "taxonomy",
            "custom_metadata_field", "custom_metadata_field",
            "custom_metadata_value", "custom_metadata_value",
            "collection", "collection",
            "collection_item", "collection_item",
            "compile_preset", "compile_preset");

    private static final int MAX_LIMIT = 500;

    private final JdbcClient jdbc;
    private final TransactionTemplate tx;
    private final ObjectMapper mapper;

    public SyncService(JdbcClient jdbc, TransactionTemplate tx, ObjectMapper mapper) {
        this.jdbc = jdbc;
        this.tx = tx;
        this.mapper = mapper;
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
        int capped = Math.min(Math.max(limit, 1), MAX_LIMIT);

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
        for (Map<String, Object> row : rows) {
            long id = ((Number) row.get("id")).longValue();
            String type = (String) row.get("entity_type");
            UUID entityId = (UUID) row.get("entity_id");
            JsonNode data = hydrated.getOrDefault(type, Map.of()).get(entityId);
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
        return new PullResponse(changes, latest, hasMore);
    }

    /**
     * Loads current entity state as JSON.
     *
     * <p>Postgres builds the JSON with {@code to_jsonb(t)} and hands it back as text, so
     * this never touches driver-specific column types and every column — including nested
     * jsonb and uuid arrays — arrives correctly typed without per-column translation.
     */
    private Map<UUID, JsonNode> loadEntities(String entityType, List<UUID> ids) {
        String table = TABLES.get(entityType);
        if (table == null || ids.isEmpty()) {
            return Map.of();
        }
        List<Map<String, Object>> rows = jdbc
                .sql("SELECT id, to_jsonb(t)::text AS row_json FROM " + table + " t WHERE id = ANY(:ids)")
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
        List<AppliedChange> applied = new ArrayList<>();
        List<ConflictRecord> conflicts = new ArrayList<>();

        for (ChangeRequest change : changes) {
            tx.executeWithoutResult(status -> applyOne(projectId, deviceId, change, applied, conflicts));
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

        if (!WRITABLE.contains(change.entityType())) {
            conflicts.add(new ConflictRecord(
                    change.entityId(), change.entityType(), ConflictReason.NOT_IMPLEMENTED, null, null));
            return;
        }

        switch (change.entityType()) {
            case "document" -> applyDocument(projectId, deviceId, change, applied, conflicts);
            case "binder_item" -> applyBinderItem(projectId, deviceId, change, applied, conflicts);
            default -> throw new IllegalStateException("unreachable: " + change.entityType());
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
                .sql("SELECT version FROM document WHERE id = :id FOR UPDATE")
                .param("id", change.entityId())
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
                        .sql("SELECT title FROM binder_item WHERE id = :id")
                        .param("id", change.entityId())
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
            String existing = jdbc.sql("SELECT content::text FROM document WHERE id = :id")
                    .param("id", change.entityId())
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
                    """)
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
                .sql("SELECT version FROM binder_item WHERE id = :id FOR UPDATE")
                .param("id", change.entityId())
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
                 WHERE id = :id
                """)
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
                .sql("SELECT project_id, parent_id, title, order_key FROM binder_item WHERE id = :id")
                .param("id", originalId)
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

    private void softDeleteItem(UUID projectId, UUID deviceId, UUID id) {
        jdbc.sql("""
                UPDATE binder_item
                   SET deleted_at = now(), version = version + 1, updated_by_device_id = :deviceId
                 WHERE id = :id AND deleted_at IS NULL
                """)
                .param("deviceId", deviceId)
                .param("id", id)
                .update();
        recordChange(projectId, "binder_item", id, "delete", deviceId);
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
