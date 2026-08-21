package com.noveltea.document;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.noveltea.config.LimitProperties;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Reads document bodies for a project.
 *
 * <p>This exists for one situation the change feed cannot serve: a client told to
 * resync. The feed carries a document's content, but only on rows appended since the
 * client's cursor — so a client that has been asked to rebuild has no way to recover
 * the body of a document nobody has touched recently. Without this it can restore the
 * binder's structure and not its prose.
 *
 * <p>Paged the same way the feed is, and for the same reason: a page stops at whichever
 * comes first, rows or bytes, because 500 rows of full documents has no predictable
 * size on mobile data. A single oversized document is always emitted alone rather than
 * wedging the endpoint permanently.
 *
 * <p>The JVM does not interpret the content — it is read as text and handed back as
 * opaque JSON, exactly as it is stored.
 */
@Service
public class DocumentService {

    private final JdbcClient jdbc;
    private final ObjectMapper mapper;
    private final LimitProperties limits;

    public DocumentService(JdbcClient jdbc, ObjectMapper mapper, LimitProperties limits) {
        this.jdbc = jdbc;
        this.mapper = mapper;
        this.limits = limits;
    }

    /** One document's stored state. `content` is opaque ProseMirror JSON. */
    public record DocumentBody(
            UUID id,
            String title,
            JsonNode content,
            String searchText,
            long wordCount,
            long version,
            OffsetDateTime updatedAt) {}

    /**
     * @param nextCursor pass as {@code after} to continue; null when the page is the last.
     */
    public record DocumentPage(List<DocumentBody> documents, UUID nextCursor, boolean hasMore) {}

    /**
     * Bodies for a project, ordered by id so a cursor is stable.
     *
     * <p>Ordered by id rather than by {@code updated_at}: a timestamp is not unique, and
     * two documents sharing one would let a cursor skip or repeat rows. Ids are arbitrary
     * but total, which is all a cursor needs.
     *
     * <p>Trashed documents are included. Trashing is a move, not a delete — the item is
     * still restorable and still syncs, so a rebuild that dropped it would empty the
     * author's trash behind their back. Tombstoned ones are excluded: those are gone.
     */
    @Transactional(readOnly = true)
    public DocumentPage bodies(UUID projectId, UUID after, Integer requestedLimit) {
        int limit = clamp(requestedLimit);

        // One more than asked for, so "is there another page" needs no second query.
        List<Map<String, Object>> rows = jdbc.sql("""
                SELECT d.id, b.title, d.content::text AS content, d.search_text,
                       d.word_count, d.version, d.updated_at
                  FROM document d
                  JOIN binder_item b ON b.id = d.id
                 WHERE b.project_id = :projectId
                   AND b.deleted_at IS NULL
                   AND (CAST(:after AS uuid) IS NULL OR d.id > CAST(:after AS uuid))
                 ORDER BY d.id
                 LIMIT :limit
                """)
                .param("projectId", projectId)
                .param("after", after)
                .param("limit", limit + 1)
                .query()
                .listOfRows();

        boolean hasMore = rows.size() > limit;
        List<DocumentBody> documents = new ArrayList<>();
        long bytesSoFar = 0;

        for (Map<String, Object> row : rows.subList(0, Math.min(rows.size(), limit))) {
            String content = (String) row.get("content");
            long rowBytes = content == null ? 0 : content.length();

            // Always emit at least one, or a single oversized document would wedge this
            // endpoint the way it would wedge the feed.
            if (!documents.isEmpty() && bytesSoFar + rowBytes > limits.maxSyncPageBytes()) {
                hasMore = true;
                break;
            }
            bytesSoFar += rowBytes;

            documents.add(new DocumentBody(
                    (UUID) row.get("id"),
                    (String) row.get("title"),
                    parse(content),
                    (String) row.get("search_text"),
                    ((Number) row.get("word_count")).longValue(),
                    ((Number) row.get("version")).longValue(),
                    toOffsetDateTime(row.get("updated_at"))));
        }

        UUID nextCursor = hasMore && !documents.isEmpty()
                ? documents.get(documents.size() - 1).id()
                : null;
        return new DocumentPage(documents, nextCursor, hasMore);
    }

    private int clamp(Integer requested) {
        if (requested == null || requested <= 0) return limits.syncPageSize();
        return Math.min(requested, limits.maxSyncPageSize());
    }

    /**
     * Content is stored as jsonb and was valid when written, so this cannot normally
     * fail. If it somehow does, an empty document is a worse answer than an error: it
     * would look like the author's chapter is empty rather than unreadable.
     */
    private JsonNode parse(String content) {
        if (content == null) return mapper.nullNode();
        try {
            return mapper.readTree(content);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Stored document content is not valid JSON", e);
        }
    }

    private static OffsetDateTime toOffsetDateTime(Object value) {
        return value instanceof OffsetDateTime odt
                ? odt
                : ((java.sql.Timestamp) value).toInstant().atOffset(ZoneOffset.UTC);
    }
}
