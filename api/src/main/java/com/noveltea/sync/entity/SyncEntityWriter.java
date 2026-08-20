package com.noveltea.sync.entity;

import com.fasterxml.jackson.databind.JsonNode;
import com.noveltea.config.LimitProperties;
import com.noveltea.model.EntityType;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;

/**
 * Writes the entity types that are pure data — taxonomy, metadata, collections, presets.
 *
 * <p>Everything is validated against {@link SyncEntitySpec} before a statement is built,
 * so a malformed change becomes one reported conflict instead of a constraint violation
 * that fails the whole push. Values are always bound, never interpolated; only column and
 * table names come from the spec, and those are compile-time constants.
 *
 * <p>Documents and binder items are deliberately not handled here: they carry conflict
 * copies and tree semantics that no declarative spec should be pretending to own.
 */
@Service
public class SyncEntityWriter {

    private final JdbcClient jdbc;
    private final LimitProperties limits;

    public SyncEntityWriter(JdbcClient jdbc, LimitProperties limits) {
        this.jdbc = jdbc;
        this.limits = limits;
    }

    public boolean supports(EntityType type) {
        return SyncEntitySpec.forType(type).isPresent();
    }

    /** @return the entity's new version */
    public long create(UUID projectId, UUID deviceId, EntityType type, UUID entityId, JsonNode data) {
        SyncEntitySpec spec = require(type);
        Map<String, Object> values = validate(spec, projectId, entityId, data, true);

        List<String> columns = new ArrayList<>(values.keySet());
        String columnList = String.join(", ", columns);
        String placeholders = columns.stream().map(c -> ":" + c).collect(java.util.stream.Collectors.joining(", "));

        StringBuilder sql = new StringBuilder("INSERT INTO ").append(spec.table()).append(" (id");
        if (spec.hasProjectId()) {
            sql.append(", project_id");
        }
        sql.append(", version, updated_by_device_id");
        if (!columns.isEmpty()) {
            sql.append(", ").append(columnList);
        }
        sql.append(") VALUES (:id");
        if (spec.hasProjectId()) {
            sql.append(", :projectId");
        }
        sql.append(", 1, :deviceId");
        if (!columns.isEmpty()) {
            sql.append(", ").append(placeholders);
        }
        sql.append(")");

        var statement = jdbc.sql(applyCasts(sql.toString(), spec, columns))
                .param("id", entityId)
                .param("deviceId", deviceId);
        if (spec.hasProjectId()) {
            statement = statement.param("projectId", projectId);
        }
        for (var entry : values.entrySet()) {
            statement = statement.param(entry.getKey(), entry.getValue());
        }
        statement.update();
        return 1L;
    }

    /** @return the entity's new version */
    public long update(UUID projectId, UUID deviceId, EntityType type, UUID entityId, long currentVersion, JsonNode data) {
        SyncEntitySpec spec = require(type);
        Map<String, Object> values = validate(spec, projectId, entityId, data, false);

        List<String> columns = new ArrayList<>(values.keySet());
        String assignments = columns.stream()
                .map(c -> c + " = " + castFor(spec, c, ":" + c))
                .collect(java.util.stream.Collectors.joining(", "));

        long next = currentVersion + 1;
        String sql = "UPDATE " + spec.table() + " SET "
                + (assignments.isEmpty() ? "" : assignments + ", ")
                + "version = :nextVersion, updated_by_device_id = :deviceId, updated_at = now()"
                + " WHERE id = :id" + spec.scopeClause();

        var statement = jdbc.sql(sql)
                .param("id", entityId)
                .param("scopeProjectId", projectId)
                .param("deviceId", deviceId)
                .param("nextVersion", next);
        for (var entry : values.entrySet()) {
            statement = statement.param(entry.getKey(), entry.getValue());
        }
        statement.update();
        return next;
    }

    /** Soft delete where the table supports it, hard delete where it does not. */
    public void delete(UUID projectId, EntityType type, UUID entityId, UUID deviceId) {
        SyncEntitySpec spec = require(type);
        boolean soft = spec.column("deleted_at").isPresent();
        if (soft) {
            jdbc.sql("UPDATE " + spec.table()
                            + " SET deleted_at = now(), version = version + 1,"
                            + " updated_by_device_id = :deviceId, updated_at = now()"
                            + " WHERE id = :id AND deleted_at IS NULL" + spec.scopeClause())
                    .param("deviceId", deviceId).param("id", entityId)
                    .param("scopeProjectId", projectId).update();
        } else {
            jdbc.sql("DELETE FROM " + spec.table() + " WHERE id = :id" + spec.scopeClause())
                    .param("id", entityId).param("scopeProjectId", projectId).update();
        }
    }

    /** Scoped: an entity outside this project must look absent, not merely unwritable. */
    public Optional<Long> currentVersion(UUID projectId, EntityType type, UUID entityId) {
        SyncEntitySpec spec = require(type);
        return jdbc.sql("SELECT version FROM " + spec.table() + " WHERE id = :id" + spec.scopeClause())
                .param("id", entityId).param("scopeProjectId", projectId)
                .query(Long.class).optional();
    }

    // ---------------------------------------------------------- validation

    private Map<String, Object> validate(
            SyncEntitySpec spec, UUID projectId, UUID entityId, JsonNode data, boolean creating) {

        Map<String, Object> values = new LinkedHashMap<>();

        for (ColumnSpec column : spec.columns()) {
            boolean present = data != null && data.has(column.column());
            if (!present) {
                if (creating && column.requiredOnCreate()) {
                    throw new EntityValidationException("missing required field: " + column.column());
                }
                continue;
            }
            JsonNode value = data.get(column.column());
            values.put(column.column(), convert(column, value));
        }

        for (var ref : spec.parentRefs()) {
            boolean present = data != null && data.hasNonNull(ref.column());
            if (!present) {
                if (creating && ref.requiredOnCreate()) {
                    throw new EntityValidationException("missing required field: " + ref.column());
                }
                continue;
            }
            UUID parentId = parseUuid(ref.column(), data.get(ref.column()).asText());
            requireParentInProject(ref, parentId, projectId);
            values.put(ref.column(), parentId);
        }

        for (var invariant : spec.invariants()) {
            // Invariants are only meaningful against the full row, so on update they run
            // against the merged view rather than the partial patch.
            JsonNode subject = creating ? data : mergedView(spec, entityId, data);
            invariant.check().apply(subject)
                    .ifPresent(message -> {
                        throw new EntityValidationException(message);
                    });
        }

        return values;
    }

    /**
     * A partial update must be judged against the row as it will be, not the patch alone.
     * Checking the patch would let {@code {"is_smart": true}} through on a collection that
     * has no query, which Postgres would then reject.
     */
    private JsonNode mergedView(SyncEntitySpec spec, UUID entityId, JsonNode patch) {
        String columns = spec.columns().stream().map(ColumnSpec::column)
                .collect(java.util.stream.Collectors.joining(", "));
        if (columns.isEmpty()) {
            return patch;
        }
        var existing = jdbc.sql("SELECT to_jsonb(t)::text FROM (SELECT " + columns
                        + " FROM " + spec.table() + " WHERE id = :id) t")
                .param("id", entityId)
                .query(String.class)
                .optional();
        if (existing.isEmpty()) {
            return patch;
        }
        try {
            com.fasterxml.jackson.databind.node.ObjectNode merged =
                    (com.fasterxml.jackson.databind.node.ObjectNode)
                            new com.fasterxml.jackson.databind.ObjectMapper().readTree(existing.get());
            if (patch != null && patch.isObject()) {
                patch.fields().forEachRemaining(e -> merged.set(e.getKey(), e.getValue()));
            }
            return merged;
        } catch (Exception e) {
            return patch;
        }
    }

    private void requireParentInProject(SyncEntitySpec.ParentRef ref, UUID parentId, UUID projectId) {
        String sql = switch (ref.table()) {
            // collection_item points at a collection, which carries project_id directly.
            case "collection", "binder_item", "custom_metadata_field" ->
                    "SELECT EXISTS (SELECT 1 FROM " + ref.table()
                            + " WHERE id = :id AND project_id = :projectId)";
            default -> throw new IllegalStateException("unmapped parent table: " + ref.table());
        };
        boolean ok = Boolean.TRUE.equals(jdbc.sql(sql)
                .param("id", parentId).param("projectId", projectId)
                .query(Boolean.class).single());
        if (!ok) {
            // Deliberately vague: a caller must not learn whether the row exists elsewhere.
            throw new EntityValidationException(ref.column() + " does not refer to anything in this project");
        }
    }

    private Object convert(ColumnSpec column, JsonNode value) {
        if (value == null || value.isNull()) {
            return null;
        }
        return switch (column.kind()) {
            case TEXT -> requireLength(column.column(), value.asText(), limits.maxNameLength());
            case LONG_TEXT -> value.asText();
            case UUID -> parseUuid(column.column(), value.asText());
            case BOOLEAN -> {
                if (!value.isBoolean()) {
                    throw new EntityValidationException(column.column() + " must be true or false");
                }
                yield value.asBoolean();
            }
            case INTEGER -> {
                if (!value.isIntegralNumber()) {
                    throw new EntityValidationException(column.column() + " must be a whole number");
                }
                yield value.asLong();
            }
            case TIMESTAMP -> parseTimestamp(column.column(), value.asText());
            case JSON_OBJECT -> {
                if (!value.isObject()) {
                    throw new EntityValidationException(column.column() + " must be a JSON object");
                }
                yield requireSize(column.column(), value.toString());
            }
            case JSON_ANY -> requireSize(column.column(), value.toString());
            case UUID_ARRAY -> toUuidArrayLiteral(column.column(), value);
            case ENUM -> {
                String raw = value.asText();
                Object resolved = column.enumLookup().apply(raw)
                        .orElseThrow(() -> new EntityValidationException(
                                "unknown value for " + column.column() + ": " + raw));
                yield resolved;
            }
        };
    }

    /** Postgres accepts {@code '{a,b}'::uuid[]}; every element is parsed first. */
    private String toUuidArrayLiteral(String field, JsonNode value) {
        if (!value.isArray()) {
            throw new EntityValidationException(field + " must be an array of ids");
        }
        List<String> ids = new ArrayList<>();
        value.forEach(element -> ids.add(parseUuid(field, element.asText()).toString()));
        return "{" + String.join(",", ids) + "}";
    }

    private String requireLength(String field, String value, int max) {
        if (value != null && value.length() > max) {
            throw new EntityValidationException(field + " must be at most " + max + " characters");
        }
        return value;
    }

    private String requireSize(String field, String json) {
        if (json.length() > limits.maxDocumentBytes()) {
            throw new EntityValidationException(field + " is too large");
        }
        return json;
    }

    private static UUID parseUuid(String field, String value) {
        try {
            return UUID.fromString(value);
        } catch (Exception e) {
            throw new EntityValidationException(field + " must be a valid id");
        }
    }

    private static OffsetDateTime parseTimestamp(String field, String value) {
        try {
            return OffsetDateTime.parse(value);
        } catch (Exception e) {
            throw new EntityValidationException(field + " must be an ISO-8601 timestamp");
        }
    }

    /** jsonb and uuid[] columns need an explicit cast around their bound parameter. */
    private String applyCasts(String sql, SyncEntitySpec spec, List<String> columns) {
        String result = sql;
        for (String column : columns) {
            result = result.replace(":" + column + ",", castFor(spec, column, ":" + column) + ",")
                    .replace(":" + column + ")", castFor(spec, column, ":" + column) + ")");
        }
        return result;
    }

    private String castFor(SyncEntitySpec spec, String column, String placeholder) {
        return spec.column(column).map(ColumnSpec::kind).map(kind -> switch (kind) {
            case JSON_OBJECT, JSON_ANY -> "CAST(" + placeholder + " AS jsonb)";
            case UUID_ARRAY -> "CAST(" + placeholder + " AS uuid[])";
            default -> placeholder;
        }).orElse(placeholder);
    }

    private SyncEntitySpec require(EntityType type) {
        return SyncEntitySpec.forType(type)
                .orElseThrow(() -> new IllegalStateException("no spec for " + type));
    }

}
