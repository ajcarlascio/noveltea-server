package com.noveltea.search;

import com.noveltea.config.LimitProperties;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;

/**
 * Full-text search across a project's binder.
 *
 * <p>Searches titles, synopses, body text and notes. Synopses and notes are never
 * exported, but they are exactly what an author searches to find a scene again — leaving
 * them out would make them write-only.
 *
 * <p>Results are weighted: a title match outranks a synopsis match, which outranks body
 * text, which outranks a note. Someone typing "lighthouse" usually wants the scene called
 * that, not the twentieth paragraph that mentions one.
 */
@Service
public class SearchService {

    /** Titles are indexed separately, so their weight is applied here rather than in the column. */
    private static final int TITLE_WEIGHT = 4;

    private final JdbcClient jdbc;
    private final LimitProperties limits;

    public SearchService(JdbcClient jdbc, LimitProperties limits) {
        this.jdbc = jdbc;
        this.limits = limits;
    }

    /**
     * @param snippet the matching passage with the terms marked, or null when the match was
     *     in the title alone
     * @param matchedTitle whether the title itself matched, so a client can say why
     */
    public record SearchHit(
            UUID id,
            String title,
            String type,
            UUID parentId,
            double rank,
            String snippet,
            boolean matchedTitle,
            boolean inTrash,
            OffsetDateTime updatedAt) {}

    public record SearchResults(String query, List<SearchHit> hits, boolean truncated) {}

    /**
     * @param query author input, parsed by {@code websearch_to_tsquery}: quoted phrases,
     *     {@code or}, and {@code -exclusion} all work, and malformed input yields no
     *     results rather than an error
     */
    public SearchResults search(UUID projectId, String query, boolean includeTrashed, int limit) {
        Objects.requireNonNull(projectId, "projectId");
        if (query == null || query.isBlank()) {
            throw new IllegalArgumentException("a search needs something to look for");
        }
        int capped = Math.min(Math.max(limit, 1), limits.maxSyncPageSize());

        List<Map<String, Object>> rows = jdbc.sql("""
                WITH RECURSIVE tsq AS (
                    SELECT websearch_to_tsquery('english', :query) AS q
                ),
                trashed AS (
                    SELECT id FROM binder_item
                     WHERE project_id = :projectId AND type = 'trash'
                    UNION ALL
                    SELECT b.id FROM binder_item b JOIN trashed t ON b.parent_id = t.id
                )
                SELECT b.id, b.title, b.type, b.parent_id, b.updated_at,
                       b.title_tsv @@ tsq.q AS matched_title,
                       b.id IN (SELECT id FROM trashed) AS in_trash,
                       ts_rank(b.title_tsv, tsq.q) * :titleWeight
                           + ts_rank(coalesce(d.search_tsv, ''::tsvector), tsq.q) AS rank,
                       CASE WHEN d.search_text IS NOT NULL AND d.search_tsv @@ tsq.q
                            THEN ts_headline('english', d.search_text, tsq.q,
                                             'MaxFragments=2,MinWords=6,MaxWords=20,FragmentDelimiter= … ')
                            WHEN d.synopsis IS NOT NULL AND d.synopsis <> ''
                            THEN ts_headline('english', d.synopsis, tsq.q,
                                             'MaxFragments=1,MinWords=6,MaxWords=20')
                       END AS snippet
                  FROM binder_item b
                  LEFT JOIN document d ON d.id = b.id
                  CROSS JOIN tsq
                 WHERE b.project_id = :projectId
                   AND b.deleted_at IS NULL
                   AND (b.title_tsv @@ tsq.q OR d.search_tsv @@ tsq.q)
                   AND (:includeTrashed OR b.id NOT IN (SELECT id FROM trashed))
                 ORDER BY rank DESC, b.updated_at DESC
                 LIMIT :limit
                """)
                .param("query", query.trim())
                .param("projectId", projectId)
                .param("titleWeight", TITLE_WEIGHT)
                .param("includeTrashed", includeTrashed)
                .param("limit", capped + 1)
                .query()
                .listOfRows();

        boolean truncated = rows.size() > capped;
        if (truncated) {
            rows = rows.subList(0, capped);
        }

        List<SearchHit> hits = rows.stream()
                .map(row -> new SearchHit(
                        (UUID) row.get("id"),
                        (String) row.get("title"),
                        (String) row.get("type"),
                        (UUID) row.get("parent_id"),
                        ((Number) row.get("rank")).doubleValue(),
                        (String) row.get("snippet"),
                        Boolean.TRUE.equals(row.get("matched_title")),
                        Boolean.TRUE.equals(row.get("in_trash")),
                        toOffset(row.get("updated_at"))))
                .toList();

        return new SearchResults(query.trim(), hits, truncated);
    }

    private static OffsetDateTime toOffset(Object value) {
        if (value == null) return null;
        return value instanceof OffsetDateTime odt
                ? odt
                : ((java.sql.Timestamp) value).toInstant().atOffset(java.time.ZoneOffset.UTC);
    }
}
