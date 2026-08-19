package com.noveltea.snapshot;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.noveltea.snapshot.SnapshotExceptions.SnapshotNotFound;
import com.noveltea.snapshot.SnapshotExceptions.StaleDocument;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Point-in-time copies of a document, so a revision pass can be undone.
 *
 * <p><b>Manual snapshots sync; automatic ones do not.</b> A snapshot is a full copy of a
 * document: syncing every autosave capture across three devices would put hundreds of
 * megabytes of history on a phone for prose it may never open. A manual snapshot is a
 * deliberate "keep this version", rare enough that copying it is cheap — and without it a
 * lost laptop takes its entire revision history with it.
 *
 * <p>Only manual snapshots therefore append to {@code change_log}.
 */
@Service
public class SnapshotService {

    private final JdbcClient jdbc;
    private final ObjectMapper mapper;
    private final SnapshotProperties properties;

    public SnapshotService(JdbcClient jdbc, ObjectMapper mapper, SnapshotProperties properties) {
        this.jdbc = jdbc;
        this.mapper = mapper;
        this.properties = properties;
    }

    /** Listing never carries content: a revision list must not ship the whole manuscript. */
    public record SnapshotSummary(
            UUID id,
            UUID documentId,
            String label,
            boolean automatic,
            int wordCount,
            UUID createdByDeviceId,
            OffsetDateTime createdAt) {}

    public record SnapshotDetail(
            UUID id, UUID documentId, String label, boolean automatic,
            int wordCount, JsonNode content, OffsetDateTime createdAt) {}

    // ---------------------------------------------------------------- create

    /**
     * Captures the document's current content.
     *
     * @param label author's name for it; only meaningful on a manual snapshot
     * @param automatic true for an editor-triggered capture, which stays on this device
     */
    @Transactional
    public UUID capture(UUID documentId, String label, boolean automatic, UUID deviceId) {
        Objects.requireNonNull(documentId, "documentId");

        Map<String, Object> document = jdbc.sql("""
                SELECT d.content::text AS content, d.word_count, b.project_id
                  FROM document d JOIN binder_item b ON b.id = d.id
                 WHERE d.id = :id
                """)
                .param("id", documentId)
                .query()
                .listOfRows()
                .stream()
                .findFirst()
                .orElseThrow(() -> new SnapshotNotFound(documentId));

        UUID projectId = (UUID) document.get("project_id");
        UUID snapshotId = UUID.randomUUID();

        jdbc.sql("""
                INSERT INTO snapshot
                    (id, project_id, document_id, content, word_count, label, is_automatic,
                     created_by_device_id, updated_by_device_id)
                VALUES (:id, :projectId, :documentId, CAST(:content AS jsonb), :wordCount,
                        :label, :automatic, :deviceId, :deviceId)
                """)
                .param("id", snapshotId)
                .param("projectId", projectId)
                .param("documentId", documentId)
                .param("content", document.get("content"))
                .param("wordCount", document.get("word_count"))
                .param("label", label == null || label.isBlank() ? null : label.trim())
                .param("automatic", automatic)
                .param("deviceId", deviceId)
                .update();

        if (!automatic) {
            recordChange(projectId, snapshotId, "create", deviceId);
        }
        pruneAutomatic(documentId);
        return snapshotId;
    }

    /**
     * Keeps automatic captures bounded.
     *
     * <p>Only automatic ones are pruned. A manual snapshot is something the author asked
     * for, and deleting it on their behalf is not this service's decision.
     */
    @Transactional
    public int pruneAutomatic(UUID documentId) {
        return jdbc.sql("""
                DELETE FROM snapshot
                 WHERE id IN (
                     SELECT id FROM snapshot
                      WHERE document_id = :documentId AND is_automatic = true
                      ORDER BY created_at DESC
                      OFFSET :keep)
                """)
                .param("documentId", documentId)
                .param("keep", properties.keepAutomaticPerDocument())
                .update();
    }

    // ------------------------------------------------------------------ read

    public List<SnapshotSummary> list(UUID documentId) {
        Objects.requireNonNull(documentId, "documentId");
        return jdbc.sql("""
                SELECT id, document_id, label, is_automatic, word_count,
                       created_by_device_id, created_at
                  FROM snapshot WHERE document_id = :id ORDER BY created_at DESC
                """)
                .param("id", documentId)
                .query()
                .listOfRows()
                .stream()
                .map(row -> new SnapshotSummary(
                        (UUID) row.get("id"),
                        (UUID) row.get("document_id"),
                        (String) row.get("label"),
                        (Boolean) row.get("is_automatic"),
                        ((Number) row.get("word_count")).intValue(),
                        (UUID) row.get("created_by_device_id"),
                        toOffset(row.get("created_at"))))
                .toList();
    }

    public SnapshotDetail get(UUID snapshotId) {
        Objects.requireNonNull(snapshotId, "snapshotId");
        Map<String, Object> row = jdbc.sql("""
                SELECT id, document_id, label, is_automatic, word_count,
                       content::text AS content, created_at
                  FROM snapshot WHERE id = :id
                """)
                .param("id", snapshotId)
                .query()
                .listOfRows()
                .stream()
                .findFirst()
                .orElseThrow(() -> new SnapshotNotFound(snapshotId));

        return new SnapshotDetail(
                (UUID) row.get("id"),
                (UUID) row.get("document_id"),
                (String) row.get("label"),
                (Boolean) row.get("is_automatic"),
                ((Number) row.get("word_count")).intValue(),
                readJson((String) row.get("content")),
                toOffset(row.get("created_at")));
    }

    /** The project a snapshot belongs to, for authorization. */
    public UUID projectOf(UUID snapshotId) {
        return jdbc.sql("SELECT project_id FROM snapshot WHERE id = :id")
                .param("id", snapshotId)
                .query(UUID.class)
                .optional()
                .orElseThrow(() -> new SnapshotNotFound(snapshotId));
    }

    // --------------------------------------------------------------- restore

    /**
     * Puts a snapshot's content back into its document.
     *
     * <p>Takes an automatic snapshot of the current state first, so restoring is itself
     * undoable — an author who reverts to the wrong version has not lost the newer one.
     *
     * @param baseVersion the document version the author was shown; a mismatch is refused
     *     rather than overwriting an edit made elsewhere in the meantime
     * @return the document's new version
     */
    @Transactional
    public long restore(UUID snapshotId, long baseVersion, UUID deviceId) {
        SnapshotDetail snapshot = get(snapshotId);
        UUID documentId = snapshot.documentId();

        long currentVersion = jdbc.sql("SELECT version FROM document WHERE id = :id FOR UPDATE")
                .param("id", documentId)
                .query(Long.class)
                .optional()
                .orElseThrow(() -> new SnapshotNotFound(documentId));

        if (currentVersion != baseVersion) {
            throw new StaleDocument(documentId, baseVersion, currentVersion);
        }

        capture(documentId, "Before restore", true, deviceId);

        long next = currentVersion + 1;
        jdbc.sql("""
                UPDATE document
                   SET content = CAST(:content AS jsonb), word_count = :wordCount,
                       version = :next, updated_by_device_id = :deviceId, updated_at = now()
                 WHERE id = :id
                """)
                .param("content", snapshot.content().toString())
                .param("wordCount", snapshot.wordCount())
                .param("next", next)
                .param("deviceId", deviceId)
                .param("id", documentId)
                .update();

        recordChange(projectOf(snapshotId), documentId, "update", deviceId, "document");
        return next;
    }

    @Transactional
    public void delete(UUID snapshotId, UUID deviceId) {
        UUID projectId = projectOf(snapshotId);
        boolean automatic = Boolean.TRUE.equals(jdbc
                .sql("SELECT is_automatic FROM snapshot WHERE id = :id")
                .param("id", snapshotId).query(Boolean.class).single());

        jdbc.sql("DELETE FROM snapshot WHERE id = :id").param("id", snapshotId).update();
        if (!automatic) {
            recordChange(projectId, snapshotId, "delete", deviceId);
        }
    }

    // ------------------------------------------------------------- internals

    private void recordChange(UUID projectId, UUID entityId, String op, UUID deviceId) {
        recordChange(projectId, entityId, op, deviceId, "snapshot");
    }

    private void recordChange(UUID projectId, UUID entityId, String op, UUID deviceId, String entityType) {
        jdbc.sql("""
                INSERT INTO change_log (project_id, entity_type, entity_id, op, device_id)
                VALUES (:projectId, :entityType, :entityId, :op, :deviceId)
                """)
                .param("projectId", projectId).param("entityType", entityType)
                .param("entityId", entityId).param("op", op).param("deviceId", deviceId)
                .update();
    }

    private JsonNode readJson(String json) {
        try {
            return json == null ? mapper.createObjectNode() : mapper.readTree(json);
        } catch (Exception e) {
            throw new IllegalStateException("unreadable snapshot content", e);
        }
    }

    private static OffsetDateTime toOffset(Object value) {
        if (value == null) return null;
        return value instanceof OffsetDateTime odt
                ? odt
                : ((java.sql.Timestamp) value).toInstant().atOffset(java.time.ZoneOffset.UTC);
    }
}
