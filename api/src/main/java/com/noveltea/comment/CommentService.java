package com.noveltea.comment;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.noveltea.comment.CommentExceptions.CommentNotFound;
import com.noveltea.comment.CommentExceptions.NotTheAuthor;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Comments and annotations on a document.
 *
 * <p>A comment can point at a passage. ProseMirror positions shift as the prose is edited,
 * so an anchor stores the quoted text alongside its offsets: when the text at those offsets
 * no longer matches, the comment is reported as <b>orphaned</b> rather than moved or
 * removed. Silently relocating an editor's note to the wrong sentence is worse than
 * admitting it lost its place, and deleting it is worse still.
 *
 * <p>Authorship is always the authenticated caller. Accepting it from a payload would let a
 * client attribute a remark to someone else.
 */
@Service
public class CommentService {

    private final JdbcClient jdbc;
    private final ObjectMapper mapper;
    private final CommentNotifier notifier;

    public CommentService(JdbcClient jdbc, ObjectMapper mapper, CommentNotifier notifier) {
        this.jdbc = jdbc;
        this.mapper = mapper;
        this.notifier = notifier;
    }

    /**
     * @param orphaned the quoted text no longer appears where the anchor says it should
     * @param anchor {from, to, quotedText}, or null for a reply or an unanchored note
     */
    public record Comment(
            UUID id,
            UUID documentId,
            UUID parentCommentId,
            UUID authorUserId,
            String authorEmail,
            String body,
            JsonNode anchor,
            boolean orphaned,
            OffsetDateTime resolvedAt,
            OffsetDateTime createdAt,
            long version) {}

    // ---------------------------------------------------------------- create

    @Transactional
    public UUID create(
            UUID documentId, UUID authorUserId, UUID deviceId,
            String body, JsonNode anchor, UUID parentCommentId) {

        Objects.requireNonNull(documentId, "documentId");
        Objects.requireNonNull(authorUserId, "authorUserId");
        String text = requireBody(body);

        UUID projectId = jdbc.sql("SELECT project_id FROM binder_item WHERE id = :id")
                .param("id", documentId).query(UUID.class).optional()
                .orElseThrow(() -> new CommentNotFound(documentId));

        if (parentCommentId != null) {
            UUID parentDocument = jdbc.sql("SELECT document_id FROM comment WHERE id = :id AND deleted_at IS NULL")
                    .param("id", parentCommentId).query(UUID.class).optional()
                    .orElseThrow(() -> new CommentNotFound(parentCommentId));
            if (!parentDocument.equals(documentId)) {
                throw new IllegalArgumentException("a reply must be on the same document as its thread");
            }
            // A reply inherits the thread's anchor; carrying its own would let a thread
            // point at two places at once.
            anchor = null;
        }

        UUID id = UUID.randomUUID();
        jdbc.sql("""
                INSERT INTO comment
                    (id, project_id, document_id, parent_comment_id, author_user_id, body,
                     anchor, updated_by_device_id)
                VALUES (:id, :projectId, :documentId, :parentId, :authorId, :body,
                        CAST(:anchor AS jsonb), :deviceId)
                """)
                .param("id", id).param("projectId", projectId).param("documentId", documentId)
                .param("parentId", parentCommentId).param("authorId", authorUserId)
                .param("body", text)
                .param("anchor", anchor == null || anchor.isNull() ? null : anchor.toString())
                .param("deviceId", deviceId)
                .update();

        recordChange(projectId, id, "create", deviceId);
        notifier.commentAdded(projectId, documentId, id, authorUserId, text);
        return id;
    }

    // ------------------------------------------------------------------ read

    /** Every live comment on a document, threads in creation order. */
    public List<Comment> forDocument(UUID documentId) {
        Objects.requireNonNull(documentId, "documentId");
        String searchText = jdbc.sql("SELECT search_text FROM document WHERE id = :id")
                .param("id", documentId).query(String.class).optional().orElse(null);

        return jdbc.sql("""
                SELECT c.id, c.document_id, c.parent_comment_id, c.author_user_id, u.email::text AS author_email,
                       c.body, c.anchor::text AS anchor_json, c.resolved_at, c.created_at, c.version
                  FROM comment c
                  LEFT JOIN app_user u ON u.id = c.author_user_id
                 WHERE c.document_id = :id AND c.deleted_at IS NULL
                 ORDER BY coalesce(c.parent_comment_id, c.id), c.created_at
                """)
                .param("id", documentId)
                .query()
                .listOfRows()
                .stream()
                .map(row -> toComment(row, searchText))
                .toList();
    }

    public UUID projectOf(UUID commentId) {
        return jdbc.sql("SELECT project_id FROM comment WHERE id = :id")
                .param("id", commentId).query(UUID.class).optional()
                .orElseThrow(() -> new CommentNotFound(commentId));
    }

    // ---------------------------------------------------------------- modify

    /** Only the author may edit. An editor rewording someone else's note is not a merge. */
    @Transactional
    public void edit(UUID commentId, UUID callerUserId, String body, UUID deviceId) {
        String text = requireBody(body);
        UUID author = requireAuthor(commentId);
        if (!author.equals(callerUserId)) {
            throw new NotTheAuthor();
        }
        jdbc.sql("""
                UPDATE comment SET body = :body, version = version + 1, updated_by_device_id = :deviceId
                 WHERE id = :id
                """)
                .param("body", text).param("deviceId", deviceId).param("id", commentId).update();
        recordChange(projectOf(commentId), commentId, "update", deviceId);
    }

    /** Anyone who can write to the project may resolve: that is a shared editorial act. */
    @Transactional
    public void setResolved(UUID commentId, UUID callerUserId, boolean resolved, UUID deviceId) {
        UUID projectId = projectOf(commentId);
        jdbc.sql("""
                UPDATE comment
                   SET resolved_at = CASE WHEN :resolved THEN now() END,
                       resolved_by_user_id = CASE WHEN :resolved THEN CAST(:userId AS uuid) END,
                       version = version + 1, updated_by_device_id = :deviceId
                 WHERE id = :id
                """)
                .param("resolved", resolved).param("userId", callerUserId)
                .param("deviceId", deviceId).param("id", commentId).update();
        recordChange(projectId, commentId, "update", deviceId);
    }

    /** Soft delete, so other devices learn about it and the thread keeps its shape. */
    @Transactional
    public void delete(UUID commentId, UUID callerUserId, UUID deviceId) {
        UUID author = requireAuthor(commentId);
        if (!author.equals(callerUserId)) {
            throw new NotTheAuthor();
        }
        UUID projectId = projectOf(commentId);
        jdbc.sql("""
                UPDATE comment SET deleted_at = now(), version = version + 1,
                       updated_by_device_id = :deviceId
                 WHERE id = :id AND deleted_at IS NULL
                """)
                .param("deviceId", deviceId).param("id", commentId).update();
        recordChange(projectId, commentId, "delete", deviceId);
    }

    // ------------------------------------------------------------- internals

    private Comment toComment(Map<String, Object> row, String searchText) {
        JsonNode anchor = readJson(row.get("anchor_json"));
        return new Comment(
                (UUID) row.get("id"),
                (UUID) row.get("document_id"),
                (UUID) row.get("parent_comment_id"),
                (UUID) row.get("author_user_id"),
                (String) row.get("author_email"),
                (String) row.get("body"),
                anchor,
                isOrphaned(anchor, searchText),
                toOffset(row.get("resolved_at")),
                toOffset(row.get("created_at")),
                ((Number) row.get("version")).longValue());
    }

    /**
     * An anchor is orphaned when its quoted text is no longer in the document.
     *
     * <p>Checked against the document's flattened text rather than its offsets, because
     * offsets drift with every edit while the words usually survive. A comment with no
     * anchor is never orphaned — it was never pointing anywhere in particular.
     */
    private boolean isOrphaned(JsonNode anchor, String searchText) {
        if (anchor == null || anchor.isNull() || !anchor.hasNonNull("quotedText")) {
            return false;
        }
        String quoted = anchor.get("quotedText").asText().trim();
        if (quoted.isEmpty()) {
            return false;
        }
        return searchText == null || !searchText.contains(quoted);
    }

    private UUID requireAuthor(UUID commentId) {
        return jdbc.sql("SELECT author_user_id FROM comment WHERE id = :id AND deleted_at IS NULL")
                .param("id", commentId).query(UUID.class).optional()
                .orElseThrow(() -> new CommentNotFound(commentId));
    }

    private static String requireBody(String body) {
        if (body == null || body.isBlank()) {
            throw new IllegalArgumentException("a comment needs something in it");
        }
        return body.trim();
    }

    /**
     * Reads a jsonb column whatever shape the driver hands back.
     *
     * <p>Casting to String is not safe even with an explicit {@code ::text}: the driver can
     * still return a PGobject, and a hard cast turns that into a 500 on a read path.
     */
    private JsonNode readJson(Object value) {
        if (value == null) {
            return null;
        }
        try {
            return mapper.readTree(value.toString());
        } catch (Exception e) {
            return null;
        }
    }

    private void recordChange(UUID projectId, UUID commentId, String op, UUID deviceId) {
        jdbc.sql("""
                INSERT INTO change_log (project_id, entity_type, entity_id, op, device_id)
                VALUES (:projectId, 'comment', :entityId, :op, :deviceId)
                """)
                .param("projectId", projectId).param("entityId", commentId)
                .param("op", op).param("deviceId", deviceId).update();
    }

    private static OffsetDateTime toOffset(Object value) {
        if (value == null) return null;
        return value instanceof OffsetDateTime odt
                ? odt
                : ((java.sql.Timestamp) value).toInstant().atOffset(java.time.ZoneOffset.UTC);
    }
}
