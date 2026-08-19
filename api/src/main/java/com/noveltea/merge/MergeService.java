package com.noveltea.merge;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.noveltea.binder.BinderService;
import com.noveltea.merge.MergeExceptions.NotAConflictCopy;
import com.noveltea.merge.MergeExceptions.StaleOriginal;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Server support for reconciling a conflict copy with its original.
 *
 * <p><b>No diff is computed here.</b> Document content is ProseMirror JSON, and the only
 * component that understands its schema is the editor — which the client already has.
 * The server returns both documents and their provenance; the client renders the merge.
 * A server-side structural diff would be a second, divergent implementation of the
 * document model.
 *
 * <p>Resolving <b>trashes</b> the copy rather than deleting it. If the author merges
 * badly, their rejected text is still recoverable from the trash — the same principle
 * that made the copy exist in the first place.
 */
@Service
public class MergeService {

    private final JdbcClient jdbc;
    private final BinderService binder;
    private final ObjectMapper mapper;

    public MergeService(JdbcClient jdbc, BinderService binder, ObjectMapper mapper) {
        this.jdbc = jdbc;
        this.binder = binder;
        this.mapper = mapper;
    }

    /**
     * @param originalVersion the DOCUMENT's version, which is what {@link #resolve} checks.
     *     binder_item carries its own independent version for structural edits; sending
     *     that one back would produce a baseVersion that can never match.
     */
    public record ConflictSummary(
            UUID copyId,
            UUID originalId,
            String originalTitle,
            String copyTitle,
            Long forkedFromVersion,
            long originalVersion,
            OffsetDateTime forkedAt) {}

    public record ConflictDetail(
            UUID copyId,
            UUID originalId,
            String originalTitle,
            JsonNode originalContent,
            long originalVersion,
            JsonNode copyContent,
            Long forkedFromVersion,
            OffsetDateTime forkedAt) {}

    /** Unresolved conflict copies in a project, oldest fork first. */
    public List<ConflictSummary> listConflicts(UUID projectId) {
        Objects.requireNonNull(projectId, "projectId");
        return jdbc.sql("""
                SELECT copy.id            AS copy_id,
                       orig.id            AS original_id,
                       orig.title         AS original_title,
                       copy.title         AS copy_title,
                       copy.conflict_base_version AS forked_from_version,
                       origdoc.version    AS original_version,
                       copy.conflict_created_at   AS forked_at
                  FROM binder_item copy
                  JOIN binder_item orig    ON orig.id = copy.conflict_of_id
                  JOIN document    origdoc ON origdoc.id = orig.id
                 WHERE copy.project_id = :projectId
                   AND copy.conflict_of_id IS NOT NULL
                   AND copy.deleted_at IS NULL
                 ORDER BY copy.conflict_created_at
                """)
                .param("projectId", projectId)
                .query(ConflictSummary.class)
                .list();
    }

    public ConflictDetail get(UUID copyId) {
        Objects.requireNonNull(copyId, "copyId");
        Map<String, Object> row = jdbc.sql("""
                SELECT copy.id AS copy_id, orig.id AS original_id, orig.title AS original_title,
                       origdoc.content::text AS original_content, origdoc.version AS original_version,
                       copydoc.content::text AS copy_content,
                       copy.conflict_base_version AS forked_from_version,
                       copy.conflict_created_at AS forked_at
                  FROM binder_item copy
                  JOIN binder_item orig     ON orig.id = copy.conflict_of_id
                  JOIN document    copydoc  ON copydoc.id = copy.id
                  JOIN document    origdoc  ON origdoc.id = orig.id
                 WHERE copy.id = :copyId AND copy.conflict_of_id IS NOT NULL
                """)
                .param("copyId", copyId)
                .query()
                .listOfRows()
                .stream()
                .findFirst()
                .orElseThrow(() -> new NotAConflictCopy(copyId));

        return new ConflictDetail(
                (UUID) row.get("copy_id"),
                (UUID) row.get("original_id"),
                (String) row.get("original_title"),
                parse((String) row.get("original_content")),
                ((Number) row.get("original_version")).longValue(),
                parse((String) row.get("copy_content")),
                row.get("forked_from_version") == null
                        ? null
                        : ((Number) row.get("forked_from_version")).longValue(),
                toOffsetDateTime(row.get("forked_at")));
    }

    /**
     * Writes the author's merged text to the original and retires the copy.
     *
     * <p>{@code baseVersion} is the original's version the author was shown. If another
     * device moved the original on in the meantime, this throws rather than creating a
     * further conflict copy: merging is interactive, so the author can re-open the
     * updated pair. Forking again here would let copies breed without bound.
     */
    @Transactional
    public long resolve(UUID copyId, JsonNode mergedContent, long baseVersion, UUID deviceId) {
        Objects.requireNonNull(copyId, "copyId");
        if (mergedContent == null || mergedContent.isNull()) {
            throw new IllegalArgumentException("merged content must not be null — refusing to blank a document");
        }
        Map<String, Object> copy = jdbc.sql("""
                SELECT project_id, conflict_of_id FROM binder_item
                 WHERE id = :id AND conflict_of_id IS NOT NULL AND deleted_at IS NULL
                """)
                .param("id", copyId)
                .query()
                .listOfRows()
                .stream()
                .findFirst()
                .orElseThrow(() -> new NotAConflictCopy(copyId));

        UUID projectId = (UUID) copy.get("project_id");
        UUID originalId = (UUID) copy.get("conflict_of_id");

        long currentVersion = jdbc.sql("SELECT version FROM document WHERE id = :id FOR UPDATE")
                .param("id", originalId)
                .query(Long.class)
                .single();
        if (currentVersion != baseVersion) {
            throw new StaleOriginal(originalId, baseVersion, currentVersion);
        }

        long next = currentVersion + 1;
        jdbc.sql("""
                UPDATE document
                   SET content = CAST(:content AS jsonb), version = :next,
                       updated_by_device_id = :deviceId, updated_at = now()
                 WHERE id = :id
                """)
                .param("content", mergedContent.toString())
                .param("next", next)
                .param("deviceId", deviceId)
                .param("id", originalId)
                .update();
        recordChange(projectId, "document", originalId, "update", deviceId);

        // Clear the edge first so the copy stops appearing as an open conflict, then trash
        // it. Trash rather than delete: a bad merge must still be recoverable.
        jdbc.sql("""
                UPDATE binder_item SET conflict_of_id = NULL, version = version + 1,
                       updated_by_device_id = :deviceId, updated_at = now()
                 WHERE id = :id
                """)
                .param("deviceId", deviceId).param("id", copyId)
                .update();
        recordChange(projectId, "binder_item", copyId, "update", deviceId);
        binder.trash(copyId, deviceId);

        return next;
    }

    /** JDBC hands timestamptz back as java.sql.Timestamp under listOfRows(). */
    private static OffsetDateTime toOffsetDateTime(Object value) {
        if (value == null) {
            return null;
        }
        return value instanceof OffsetDateTime odt
                ? odt
                : ((java.sql.Timestamp) value).toInstant().atOffset(java.time.ZoneOffset.UTC);
    }

    private JsonNode parse(String json) {
        try {
            return mapper.readTree(json);
        } catch (Exception e) {
            throw new IllegalStateException("unreadable document content", e);
        }
    }

    private void recordChange(UUID projectId, String entityType, UUID entityId, String op, UUID deviceId) {
        jdbc.sql("""
                INSERT INTO change_log (project_id, entity_type, entity_id, op, device_id)
                VALUES (:projectId, :entityType, :entityId, :op, :deviceId)
                """)
                .param("projectId", projectId).param("entityType", entityType)
                .param("entityId", entityId).param("op", op).param("deviceId", deviceId)
                .update();
    }
}
