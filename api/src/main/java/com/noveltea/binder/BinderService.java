package com.noveltea.binder;

import com.noveltea.binder.BinderExceptions.BinderCycle;
import com.noveltea.binder.BinderExceptions.BinderItemNotFound;
import com.noveltea.binder.BinderExceptions.CrossProjectMove;
import com.noveltea.order.FractionalIndex;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Structural operations on the binder tree.
 *
 * <p>Every mutation here honours the four-step contract: verify, bump {@code version},
 * stamp {@code updated_by_device_id}, and append a {@code change_log} row — all inside
 * one transaction. Skipping the last step is invisible to an HTTP-level test and means
 * other devices never learn the change happened.
 *
 * <p><b>Trash is a move, not a delete.</b> Trashing reparents an item to the project's
 * trash node and records where it came from; the item keeps syncing and stays
 * restorable. {@code deleted_at} is reserved for the tombstone written when the trash
 * is emptied.
 */
@Service
public class BinderService {

    private final JdbcClient jdbc;

    public BinderService(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    // ------------------------------------------------------------- reading

    /** Every live item in the project, parents before children, siblings in order. */
    public List<BinderNode> tree(UUID projectId) {
        return jdbc.sql("""
                SELECT id, parent_id, type, title, order_key, label_id, status_id,
                       trashed_from_parent_id, version, updated_at
                  FROM binder_item
                 WHERE project_id = :projectId AND deleted_at IS NULL
                 ORDER BY parent_id NULLS FIRST, order_key
                """)
                .param("projectId", projectId)
                .query(BinderNode.class)
                .list();
    }

    // ------------------------------------------------------------ mutation

    /**
     * Creates an item positioned after {@code afterSiblingId}, or first when null.
     */
    @Transactional
    public UUID create(UUID projectId, UUID deviceId, UUID parentId, String type, String title, UUID afterSiblingId) {
        if (parentId != null) {
            requireSameProject(projectId, parentId);
        }
        UUID id = UUID.randomUUID();
        String orderKey = orderKeyFor(projectId, parentId, afterSiblingId, null);

        jdbc.sql("""
                INSERT INTO binder_item (id, project_id, parent_id, type, title, order_key, updated_by_device_id)
                VALUES (:id, :projectId, :parentId, :type, :title, :orderKey, :deviceId)
                """)
                .param("id", id).param("projectId", projectId).param("parentId", parentId)
                .param("type", type).param("title", title).param("orderKey", orderKey)
                .param("deviceId", deviceId)
                .update();
        recordChange(projectId, id, "create", deviceId);
        return id;
    }

    @Transactional
    public void rename(UUID itemId, String title, UUID deviceId) {
        UUID projectId = requireProjectOf(itemId);
        jdbc.sql("""
                UPDATE binder_item
                   SET title = :title, version = version + 1,
                       updated_by_device_id = :deviceId, updated_at = now()
                 WHERE id = :id
                """)
                .param("title", title).param("deviceId", deviceId).param("id", itemId)
                .update();
        recordChange(projectId, itemId, "update", deviceId);
    }

    /**
     * Reparents and repositions an item.
     *
     * <p>The cycle check is the reason this lives in a service rather than in SQL: no
     * CHECK constraint can express "this item is not among its own descendants", and
     * without it a mis-ordered drag can detach an entire subtree from the tree, leaving
     * chapters that exist in the database but appear nowhere in the binder.
     */
    @Transactional
    public void move(UUID itemId, UUID newParentId, UUID afterSiblingId, UUID deviceId) {
        UUID projectId = requireProjectOf(itemId);

        if (newParentId != null) {
            requireSameProject(projectId, newParentId);
            if (wouldCycle(itemId, newParentId)) {
                throw new BinderCycle(itemId, newParentId);
            }
        }

        String orderKey = orderKeyFor(projectId, newParentId, afterSiblingId, itemId);
        jdbc.sql("""
                UPDATE binder_item
                   SET parent_id = :parentId, order_key = :orderKey, version = version + 1,
                       updated_by_device_id = :deviceId, updated_at = now()
                 WHERE id = :id
                """)
                .param("parentId", newParentId).param("orderKey", orderKey)
                .param("deviceId", deviceId).param("id", itemId)
                .update();
        recordChange(projectId, itemId, "update", deviceId);
    }

    /** Moves an item to the trash, remembering where it came from. */
    @Transactional
    public void trash(UUID itemId, UUID deviceId) {
        UUID projectId = requireProjectOf(itemId);
        UUID trashId = ensureTrash(projectId, deviceId);
        if (itemId.equals(trashId)) {
            throw new BinderCycle(itemId, trashId);
        }

        UUID currentParent = jdbc.sql("SELECT parent_id FROM binder_item WHERE id = :id")
                .param("id", itemId).query(UUID.class).optional().orElse(null);

        String orderKey = orderKeyFor(projectId, trashId, null, itemId);
        jdbc.sql("""
                UPDATE binder_item
                   SET parent_id = :trashId, trashed_from_parent_id = :from, order_key = :orderKey,
                       version = version + 1, updated_by_device_id = :deviceId, updated_at = now()
                 WHERE id = :id
                """)
                .param("trashId", trashId).param("from", currentParent).param("orderKey", orderKey)
                .param("deviceId", deviceId).param("id", itemId)
                .update();
        recordChange(projectId, itemId, "update", deviceId);
    }

    /** Returns an item from the trash to where it was trashed from. */
    @Transactional
    public void restore(UUID itemId, UUID deviceId) {
        UUID projectId = requireProjectOf(itemId);
        UUID origin = jdbc.sql("SELECT trashed_from_parent_id FROM binder_item WHERE id = :id")
                .param("id", itemId).query(UUID.class).optional().orElse(null);

        // If the original parent is gone or itself trashed, restore to root rather than
        // refuse — the author can always move it again, but a refusal strands the item.
        UUID target = (origin != null && isLiveAndOutsideTrash(projectId, origin)) ? origin : null;

        String orderKey = orderKeyFor(projectId, target, null, itemId);
        jdbc.sql("""
                UPDATE binder_item
                   SET parent_id = :parentId, trashed_from_parent_id = NULL, order_key = :orderKey,
                       version = version + 1, updated_by_device_id = :deviceId, updated_at = now()
                 WHERE id = :id
                """)
                .param("parentId", target).param("orderKey", orderKey)
                .param("deviceId", deviceId).param("id", itemId)
                .update();
        recordChange(projectId, itemId, "update", deviceId);
    }

    /**
     * Hard-deletes everything in the trash, writing a tombstone per item.
     *
     * <p>Rows are retained with {@code deleted_at} set rather than removed, because a
     * client that has been offline still needs to learn the item is gone. Purging the
     * rows outright would leave those clients with orphans forever.
     */
    @Transactional
    public int emptyTrash(UUID projectId, UUID deviceId) {
        Optional<UUID> trashId = findTrash(projectId);
        if (trashId.isEmpty()) {
            return 0;
        }
        List<UUID> deleted = jdbc.sql("""
                WITH RECURSIVE subtree AS (
                    SELECT id FROM binder_item WHERE parent_id = :trashId
                    UNION ALL
                    SELECT b.id FROM binder_item b JOIN subtree s ON b.parent_id = s.id
                )
                UPDATE binder_item
                   SET deleted_at = now(), version = version + 1,
                       updated_by_device_id = :deviceId, updated_at = now()
                 WHERE id IN (SELECT id FROM subtree) AND deleted_at IS NULL
                RETURNING id
                """)
                .param("trashId", trashId.get()).param("deviceId", deviceId)
                .query(UUID.class)
                .list();

        deleted.forEach(id -> recordChange(projectId, id, "delete", deviceId));
        return deleted.size();
    }

    /** Returns the project's trash node, creating it on first use. */
    @Transactional
    public UUID ensureTrash(UUID projectId, UUID deviceId) {
        return findTrash(projectId).orElseGet(() -> {
            UUID id = UUID.randomUUID();
            jdbc.sql("""
                    INSERT INTO binder_item (id, project_id, parent_id, type, title, order_key, updated_by_device_id)
                    VALUES (:id, :projectId, NULL, 'trash', 'Trash', :orderKey, :deviceId)
                    """)
                    .param("id", id).param("projectId", projectId)
                    .param("orderKey", orderKeyFor(projectId, null, lastRootChild(projectId), null))
                    .param("deviceId", deviceId)
                    .update();
            recordChange(projectId, id, "create", deviceId);
            return id;
        });
    }

    // -------------------------------------------------------------- internals

    private Optional<UUID> findTrash(UUID projectId) {
        return jdbc.sql("SELECT id FROM binder_item WHERE project_id = :projectId AND type = 'trash'")
                .param("projectId", projectId).query(UUID.class).optional();
    }

    private boolean wouldCycle(UUID itemId, UUID candidateParentId) {
        return Boolean.TRUE.equals(jdbc.sql("""
                WITH RECURSIVE subtree AS (
                    SELECT id FROM binder_item WHERE id = :itemId
                    UNION ALL
                    SELECT b.id FROM binder_item b JOIN subtree s ON b.parent_id = s.id
                )
                SELECT EXISTS (SELECT 1 FROM subtree WHERE id = :candidate)
                """)
                .param("itemId", itemId).param("candidate", candidateParentId)
                .query(Boolean.class).single());
    }

    private boolean isLiveAndOutsideTrash(UUID projectId, UUID itemId) {
        return Boolean.TRUE.equals(jdbc.sql("""
                WITH RECURSIVE ancestors AS (
                    SELECT id, parent_id, type, deleted_at FROM binder_item WHERE id = :id
                    UNION ALL
                    SELECT b.id, b.parent_id, b.type, b.deleted_at
                      FROM binder_item b JOIN ancestors a ON b.id = a.parent_id
                )
                SELECT NOT EXISTS (SELECT 1 FROM ancestors WHERE type = 'trash' OR deleted_at IS NOT NULL)
                   AND EXISTS (SELECT 1 FROM binder_item WHERE id = :id AND project_id = :projectId)
                """)
                .param("id", itemId).param("projectId", projectId)
                .query(Boolean.class).single());
    }

    private UUID lastRootChild(UUID projectId) {
        return jdbc.sql("""
                SELECT id FROM binder_item
                 WHERE project_id = :projectId AND parent_id IS NULL AND deleted_at IS NULL
                 ORDER BY order_key DESC LIMIT 1
                """)
                .param("projectId", projectId).query(UUID.class).optional().orElse(null);
    }

    /** Computes an ordering key placing an item after {@code afterSiblingId}. */
    private String orderKeyFor(UUID projectId, UUID parentId, UUID afterSiblingId, UUID excludeId) {
        String afterKey = afterSiblingId == null ? null
                : jdbc.sql("SELECT order_key FROM binder_item WHERE id = :id")
                        .param("id", afterSiblingId).query(String.class).optional().orElse(null);

        String beforeKey = jdbc.sql("""
                SELECT min(order_key) FROM binder_item
                 WHERE project_id = :projectId
                   AND parent_id IS NOT DISTINCT FROM CAST(:parentId AS uuid)
                   AND (CAST(:afterKey AS text) IS NULL OR order_key > CAST(:afterKey AS text))
                   AND (CAST(:excludeId AS uuid) IS NULL OR id <> CAST(:excludeId AS uuid))
                """)
                .param("projectId", projectId).param("parentId", parentId)
                .param("afterKey", afterKey).param("excludeId", excludeId)
                .query(String.class).optional().orElse(null);

        return FractionalIndex.between(afterKey, beforeKey);
    }

    private UUID requireProjectOf(UUID itemId) {
        return jdbc.sql("SELECT project_id FROM binder_item WHERE id = :id")
                .param("id", itemId).query(UUID.class).optional()
                .orElseThrow(() -> new BinderItemNotFound(itemId));
    }

    private void requireSameProject(UUID projectId, UUID otherItemId) {
        UUID otherProject = jdbc.sql("SELECT project_id FROM binder_item WHERE id = :id")
                .param("id", otherItemId).query(UUID.class).optional()
                .orElseThrow(() -> new BinderItemNotFound(otherItemId));
        if (!projectId.equals(otherProject)) {
            throw new CrossProjectMove(projectId, otherItemId);
        }
    }

    /** Appends the feed row. Never optional, never outside the write's transaction. */
    private void recordChange(UUID projectId, UUID itemId, String op, UUID deviceId) {
        jdbc.sql("""
                INSERT INTO change_log (project_id, entity_type, entity_id, op, device_id)
                VALUES (:projectId, 'binder_item', :entityId, :op, :deviceId)
                """)
                .param("projectId", projectId).param("entityId", itemId)
                .param("op", op).param("deviceId", deviceId)
                .update();
    }
}
