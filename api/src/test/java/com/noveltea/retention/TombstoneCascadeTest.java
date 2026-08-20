package com.noveltea.retention;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.noveltea.binder.BinderService;
import com.noveltea.support.AbstractPostgresTest;
import com.noveltea.sync.SyncService;
import com.noveltea.sync.dto.SyncDtos.ChangeRequest;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * Deleting a folder must not quietly take its contents with it.
 *
 * <p>`binder_item.parent_id` cascades, so any hard delete of a folder removes its children
 * from the database outright — with no tombstone and no feed row. Every device holding
 * those documents keeps them forever while the server has none, and nothing ever says so.
 */
class TombstoneCascadeTest extends AbstractPostgresTest {

    @Autowired SyncService sync;
    @Autowired BinderService binder;
    @Autowired RetentionService retention;
    @Autowired ObjectMapper mapper;

    private record Tree(UUID folder, UUID child, UUID grandchild) {}

    private Tree seedTree() {
        UUID folder = binder.create(projectId, deviceA, null, "folder", "Act One", null);
        UUID child = binder.create(projectId, deviceA, folder, "document", "Chapter One", null);
        UUID grandchild = binder.create(projectId, deviceA, child, "document", "Scene", null);
        return new Tree(folder, child, grandchild);
    }

    private boolean tombstoned(UUID id) {
        return jdbc.sql("SELECT deleted_at IS NOT NULL FROM binder_item WHERE id = :id")
                .param("id", id).query(Boolean.class).optional().orElse(false);
    }

    private long deleteRowsFor(UUID id) {
        return jdbc.sql("""
                SELECT count(*) FROM change_log
                 WHERE entity_type = 'binder_item' AND entity_id = :id AND op = 'delete'
                """).param("id", id).query(Long.class).single();
    }

    @Test
    @DisplayName("deleting a folder through sync tombstones its whole subtree")
    void deleteTombstonesDescendants() {
        Tree tree = seedTree();

        sync.push(projectId, deviceA, List.of(
                new ChangeRequest("binder_item", tree.folder(), "delete", 1L, null)));

        assertThat(tombstoned(tree.folder())).isTrue();
        assertThat(tombstoned(tree.child()))
                .as("a child left live under a deleted parent is unreachable in every client")
                .isTrue();
        assertThat(tombstoned(tree.grandchild())).isTrue();
    }

    @Test
    @DisplayName("EVERY DELETED ITEM GETS ITS OWN FEED ROW")
    void everyDescendantIsAnnounced() {
        Tree tree = seedTree();

        sync.push(projectId, deviceA, List.of(
                new ChangeRequest("binder_item", tree.folder(), "delete", 1L, null)));

        for (UUID id : List.of(tree.folder(), tree.child(), tree.grandchild())) {
            assertThat(deleteRowsFor(id))
                    .as("a device holding this document learns it is gone only from its own row")
                    .isEqualTo(1);
        }
    }

    @Test
    @DisplayName("retention never hard-deletes a tombstone that still has live children")
    void cascadeCannotTakeLiveChildren() {
        Tree tree = seedTree();

        // The state the old delete path produced: parent tombstoned, children still live.
        jdbc.sql("UPDATE binder_item SET deleted_at = now() - interval '400 days' WHERE id = :id")
                .param("id", tree.folder()).update();
        jdbc.sql("DELETE FROM change_log WHERE entity_id = :id").param("id", tree.folder()).update();

        retention.purgeTombstones();

        assertThat(jdbc.sql("SELECT count(*) FROM binder_item WHERE id = :id")
                .param("id", tree.child()).query(Long.class).single())
                .as("the cascade would destroy an author's document with no trace")
                .isEqualTo(1);
        assertThat(jdbc.sql("SELECT count(*) FROM binder_item WHERE id = :id")
                .param("id", tree.folder()).query(Long.class).single())
                .as("the parent is held back until its children are gone too")
                .isEqualTo(1);
    }

    @Test
    @DisplayName("a fully tombstoned subtree is still collectable, deepest first")
    void fullyTombstonedSubtreeIsCollected() {
        Tree tree = seedTree();
        jdbc.sql("UPDATE binder_item SET deleted_at = now() - interval '400 days' WHERE project_id = :p")
                .param("p", projectId).update();
        jdbc.sql("DELETE FROM change_log WHERE project_id = :p").param("p", projectId).update();

        // Leaves first, then their parents once nothing live remains beneath them.
        int removed = 0;
        for (int pass = 0; pass < 4; pass++) {
            removed += retention.purgeTombstones();
        }

        assertThat(removed).as("retention must eventually reclaim the space").isGreaterThanOrEqualTo(3);
        assertThat(jdbc.sql("SELECT count(*) FROM binder_item WHERE project_id = :p")
                .param("p", projectId).query(Long.class).single()).isZero();
    }

    @Test
    @DisplayName("deleting a leaf still works and touches nothing else")
    void deletingALeafIsUnaffected() {
        Tree tree = seedTree();

        sync.push(projectId, deviceA, List.of(
                new ChangeRequest("binder_item", tree.grandchild(), "delete", 1L, null)));

        assertThat(tombstoned(tree.grandchild())).isTrue();
        assertThat(tombstoned(tree.child())).isFalse();
        assertThat(tombstoned(tree.folder())).isFalse();
    }
}
