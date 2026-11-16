package com.noveltea.sync;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.noveltea.model.EntityType;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

/**
 * The current rows of every synced type that {@code GET /binder} and {@code GET /documents}
 * do not cover, for a client told to resync.
 *
 * <p>Without this a rebuild restores the tree and the prose and nothing hung on them: a
 * new device on a project whose feed retention has trimmed never learns its labels,
 * statuses, collections, metadata, presets, comments or manual snapshots, because the only
 * rows that described them have been purged.
 *
 * <p>Each row is shaped exactly as the change feed hydrates it — {@code to_jsonb} of the
 * table row, snapshots without their content — so a client applies it the way it applies
 * a pulled change.
 */
@Service
public class ProjectStateService {

    private final JdbcClient jdbc;
    private final ObjectMapper mapper;

    public ProjectStateService(JdbcClient jdbc, ObjectMapper mapper) {
        this.jdbc = jdbc;
        this.mapper = mapper;
    }

    /** One entity's stored state, as a change-feed row would carry it. */
    public record EntityRow(String entityType, UUID entityId, JsonNode data) {}

    /**
     * What each type is read from, in the order a client must insert it: every row comes
     * after anything it references, binder items aside — those come from {@code /binder},
     * and the rows that need them are last.
     *
     * <p>Tombstoned rows are included. They are what a device that followed the feed from
     * the start holds, and leaving them out would strand whatever still points at one — a
     * binder item's retired label, a reply under a deleted comment, a value for a removed
     * field — on a device that has never seen the row it names.
     *
     * <p>Rows hanging off a tombstoned binder item are not, because {@code /binder} does
     * not list that item and a new device has nothing for them to attach to.
     *
     * <p>Every clause is scoped to the project, through a parent where the table has no
     * project_id of its own. A collection_item is checked on both sides, so a row cannot
     * leak in through a collection or an item that belongs somewhere else.
     */
    private static final Map<EntityType, String> SOURCES = new LinkedHashMap<>();

    static {
        SOURCES.put(EntityType.TAXONOMY, "FROM taxonomy t WHERE t.project_id = :projectId ORDER BY t.id");
        SOURCES.put(EntityType.CUSTOM_METADATA_FIELD,
                "FROM custom_metadata_field t WHERE t.project_id = :projectId ORDER BY t.id");
        SOURCES.put(EntityType.COLLECTION, "FROM collection t WHERE t.project_id = :projectId ORDER BY t.id");
        SOURCES.put(EntityType.COMPILE_PRESET,
                "FROM compile_preset t WHERE t.project_id = :projectId ORDER BY t.id");
        SOURCES.put(EntityType.CUSTOM_METADATA_VALUE, """
                FROM custom_metadata_value t
                JOIN binder_item b ON b.id = t.binder_item_id
               WHERE b.project_id = :projectId AND b.deleted_at IS NULL
               ORDER BY t.id""");
        SOURCES.put(EntityType.COLLECTION_ITEM, """
                FROM collection_item t
                JOIN collection c ON c.id = t.collection_id
                JOIN binder_item b ON b.id = t.binder_item_id
               WHERE c.project_id = :projectId AND b.project_id = :projectId
                 AND b.deleted_at IS NULL
               ORDER BY t.id""");
        // Threads before replies, so a reply never arrives ahead of the comment it answers.
        SOURCES.put(EntityType.COMMENT, """
                FROM comment t
                JOIN binder_item b ON b.id = t.document_id
               WHERE t.project_id = :projectId AND b.deleted_at IS NULL
               ORDER BY t.parent_comment_id IS NOT NULL, t.created_at, t.id""");
        // Manual only, as in the feed: automatic captures stay on the device that made them.
        SOURCES.put(EntityType.SNAPSHOT, """
                FROM snapshot t
                JOIN binder_item b ON b.id = t.document_id
               WHERE t.project_id = :projectId AND t.is_automatic = false
                 AND b.deleted_at IS NULL
               ORDER BY t.id""");
    }

    /**
     * Repeatable read, so every table is read from one snapshot. At read committed each
     * statement sees its own, and a collection deleted between two of them would come back
     * as items with no collection.
     *
     * <p>Any failure here — a statement, an unreadable row — escapes as an exception, so the
     * client gets the API's ordinary 500 error body and never a partial list. That matters:
     * the client prunes whatever a successful answer leaves out.
     */
    // ponytail: one unpaginated response, materialised in one transaction; fine for a
    // novel's worth of labels, comments and snapshot headers. Page by type, the way
    // GET /documents pages by bytes, once a project's state outgrows one response.
    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public List<EntityRow> state(UUID projectId) {
        List<EntityRow> out = new ArrayList<>();
        SOURCES.forEach((type, source) -> {
            // Content is left out for the reason the feed leaves it out: a snapshot is a
            // whole document, fetched from GET /snapshots/{id} when the author opens it.
            String projection = type == EntityType.SNAPSHOT
                    ? "(to_jsonb(t) - 'content')::text"
                    : "to_jsonb(t)::text";
            jdbc.sql("SELECT t.id, " + projection + " AS row_json " + source)
                    .param("projectId", projectId)
                    .query()
                    .listOfRows()
                    .forEach(row -> out.add(new EntityRow(
                            type.wire(), (UUID) row.get("id"), parse((String) row.get("row_json")))));
        });
        return out;
    }

    private JsonNode parse(String json) {
        try {
            return mapper.readTree(json);
        } catch (Exception e) {
            throw new IllegalStateException("unreadable row json", e);
        }
    }
}
