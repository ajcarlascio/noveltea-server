package com.noveltea.project;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.noveltea.project.ProjectExceptions.ProjectNotDeleted;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Project lifecycle.
 *
 * <p>Deletion is two-step. {@link #delete} hides the project; only an already-hidden
 * project can be {@link #purge}d, and purging cascades to every item and document it
 * contains. One mistyped request should not be able to destroy a novel.
 */
@Service
public class ProjectService {

    private static final int MAX_TITLE = 500;

    private final JdbcClient jdbc;
    private final ObjectMapper mapper;

    public ProjectService(JdbcClient jdbc, ObjectMapper mapper) {
        this.jdbc = jdbc;
        this.mapper = mapper;
    }

    @Transactional
    public Project create(UUID ownerId, String title, JsonNode settings) {
        return create(ownerId, null, title, settings);
    }

    @Transactional
    public Project create(UUID ownerId, UUID clientId, String title, JsonNode settings) {
        Objects.requireNonNull(ownerId, "ownerId");
        String cleanTitle = requireTitle(title);
        String cleanSettings = normaliseSettings(settings);

        // A client that already has a local project supplies its own id so sync can
        // address it afterwards. Idempotent: if the same owner sends the same id twice
        // (a retry after a lost response), the existing project is returned unchanged.
        // A different owner naming the same id gets a fresh one — telling them the id
        // is taken would confirm it exists.
        UUID id = clientId != null ? clientId : UUID.randomUUID();

        if (clientId != null) {
            var existing = jdbc.sql("SELECT owner_id FROM project WHERE id = :id")
                    .param("id", id).query().listOfRows();
            if (!existing.isEmpty()) {
                UUID existingOwner = (UUID) existing.getFirst().get("owner_id");
                if (existingOwner.equals(ownerId)) {
                    return get(id, true);
                }
                // Collision with another owner: fall through with a fresh id so the
                // caller gets a working project instead of an error.
                id = UUID.randomUUID();
            }
        }

        jdbc.sql("""
                INSERT INTO project (id, owner_id, title, settings)
                VALUES (:id, :ownerId, :title, CAST(:settings AS jsonb))
                """)
                .param("id", id).param("ownerId", ownerId)
                .param("title", cleanTitle).param("settings", cleanSettings)
                .update();
        return get(id, true);
    }

    /** Live projects owned by this user, most recently updated first. */
    public List<Project> list(UUID ownerId) {
        Objects.requireNonNull(ownerId, "ownerId");
        return jdbc.sql(baseSelect() + """
                 WHERE p.owner_id = :ownerId AND p.deleted_at IS NULL
                 ORDER BY p.updated_at DESC
                """)
                .param("ownerId", ownerId)
                .query()
                .listOfRows()
                .stream()
                .map(this::toProject)
                .toList();
    }

    /** Projects the owner has deleted but not yet purged. */
    public List<Project> listDeleted(UUID ownerId) {
        Objects.requireNonNull(ownerId, "ownerId");
        return jdbc.sql(baseSelect() + """
                 WHERE p.owner_id = :ownerId AND p.deleted_at IS NOT NULL
                 ORDER BY p.deleted_at DESC
                """)
                .param("ownerId", ownerId)
                .query()
                .listOfRows()
                .stream()
                .map(this::toProject)
                .toList();
    }

    public Project get(UUID projectId, boolean includeDeleted) {
        Objects.requireNonNull(projectId, "projectId");
        return jdbc.sql(baseSelect() + """
                 WHERE p.id = :id AND (:includeDeleted OR p.deleted_at IS NULL)
                """)
                .param("id", projectId)
                .param("includeDeleted", includeDeleted)
                .query()
                .listOfRows()
                .stream()
                .findFirst()
                .map(this::toProject)
                .orElseThrow(() -> new com.noveltea.auth.AuthExceptions.AccessDenied("no such project"));
    }

    /** Null fields are left untouched, so a partial update cannot blank a title. */
    @Transactional
    public Project update(UUID projectId, String title, JsonNode settings) {
        Objects.requireNonNull(projectId, "projectId");
        String cleanTitle = title == null ? null : requireTitle(title);
        String cleanSettings = settings == null ? null : normaliseSettings(settings);

        jdbc.sql("""
                UPDATE project
                   SET title = coalesce(:title, title),
                       settings = coalesce(CAST(:settings AS jsonb), settings),
                       updated_at = now()
                 WHERE id = :id AND deleted_at IS NULL
                """)
                .param("title", cleanTitle).param("settings", cleanSettings).param("id", projectId)
                .update();
        return get(projectId, false);
    }

    /** Hides the project. Reversible with {@link #restore}. */
    @Transactional
    public void delete(UUID projectId) {
        Objects.requireNonNull(projectId, "projectId");
        jdbc.sql("UPDATE project SET deleted_at = now(), updated_at = now() WHERE id = :id AND deleted_at IS NULL")
                .param("id", projectId).update();
    }

    @Transactional
    public Project restore(UUID projectId) {
        Objects.requireNonNull(projectId, "projectId");
        jdbc.sql("UPDATE project SET deleted_at = NULL, updated_at = now() WHERE id = :id")
                .param("id", projectId).update();
        return get(projectId, false);
    }

    /**
     * Destroys the project and everything in it. Refuses unless it is already deleted:
     * the two-step is the whole safety mechanism, so this must never be a shortcut.
     */
    @Transactional
    public void purge(UUID projectId) {
        Objects.requireNonNull(projectId, "projectId");
        boolean deleted = Boolean.TRUE.equals(jdbc
                .sql("SELECT EXISTS (SELECT 1 FROM project WHERE id = :id AND deleted_at IS NOT NULL)")
                .param("id", projectId).query(Boolean.class).single());
        if (!deleted) {
            throw new ProjectNotDeleted(projectId);
        }
        jdbc.sql("DELETE FROM project WHERE id = :id AND deleted_at IS NOT NULL")
                .param("id", projectId).update();
    }

    // ------------------------------------------------------------- internals

    /** Counts come from the binder so a project list can show progress without a second call. */
    private String baseSelect() {
        return """
                SELECT p.id, p.owner_id, p.title, p.settings::text AS settings_json,
                       p.created_at, p.updated_at, p.deleted_at,
                       coalesce(stats.document_count, 0) AS document_count,
                       coalesce(stats.word_count, 0)     AS word_count
                  FROM project p
                  LEFT JOIN LATERAL (
                      SELECT count(*) AS document_count, sum(d.word_count) AS word_count
                        FROM binder_item b
                        JOIN document d ON d.id = b.id
                       WHERE b.project_id = p.id AND b.deleted_at IS NULL
                  ) stats ON true
                """;
    }

    private Project toProject(Map<String, Object> row) {
        return new Project(
                (UUID) row.get("id"),
                (UUID) row.get("owner_id"),
                (String) row.get("title"),
                parse((String) row.get("settings_json")),
                ((Number) row.get("document_count")).intValue(),
                ((Number) row.get("word_count")).longValue(),
                toOffset(row.get("created_at")),
                toOffset(row.get("updated_at")),
                toOffset(row.get("deleted_at")));
    }

    private JsonNode parse(String json) {
        try {
            return json == null ? mapper.createObjectNode() : mapper.readTree(json);
        } catch (Exception e) {
            throw new IllegalStateException("unreadable settings json", e);
        }
    }

    private static String requireTitle(String title) {
        if (title == null || title.isBlank()) {
            throw new IllegalArgumentException("title must not be null or blank");
        }
        String trimmed = title.trim();
        if (trimmed.length() > MAX_TITLE) {
            throw new IllegalArgumentException("title must be at most " + MAX_TITLE + " characters");
        }
        return trimmed;
    }

    /** Settings must be a JSON object: an array or scalar here would break every reader. */
    private String normaliseSettings(JsonNode settings) {
        if (settings == null || settings.isNull()) {
            return "{}";
        }
        if (!settings.isObject()) {
            throw new IllegalArgumentException("settings must be a JSON object");
        }
        return settings.toString();
    }

    private static OffsetDateTime toOffset(Object value) {
        if (value == null) {
            return null;
        }
        return value instanceof OffsetDateTime odt
                ? odt
                : ((java.sql.Timestamp) value).toInstant().atOffset(java.time.ZoneOffset.UTC);
    }
}
