package com.noveltea.sync;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.noveltea.binder.BinderService;
import com.noveltea.support.AbstractPostgresTest;
import com.noveltea.sync.dto.SyncDtos.ChangeRequest;
import com.noveltea.sync.dto.SyncDtos.ConflictRecord;
import com.noveltea.sync.dto.SyncDtos.PushResponse;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * Regression tests for the audit findings (findings.md, C1 and C2).
 *
 * <p>C1: a client whose binder_item create push lost an order_key race gets INVALID_REQUEST,
 * clears the pending row, then re-queues the displaced item. That re-queue must land — as a
 * create — or the item exists only on one device until a resync erases it.
 *
 * <p>C2: a document write arriving after its binder item was deleted must never be accepted
 * into the tombstoned row (retention would destroy the words), and a conflict copy forked
 * from a trashed original must not be placed inside the trash node.
 */
class SyncAuditFindingsTest extends AbstractPostgresTest {

    @Autowired SyncService sync;
    @Autowired BinderService binder;
    @Autowired ObjectMapper mapper;

    private ChangeRequest documentUpdate(UUID id, Long baseVersion, String text) {
        ObjectNode data = mapper.createObjectNode();
        try {
            data.set("content", mapper.readTree(doc(text)));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
        data.put("search_text", text);
        data.put("word_count", text.split("\\s+").length);
        return new ChangeRequest("document", id, "update", baseVersion, data);
    }

    private UUID trashNodeId() {
        return jdbc.sql("SELECT id FROM binder_item WHERE project_id = :p AND type = 'trash'")
                .param("p", projectId).query(UUID.class).single();
    }

    @Test
    @DisplayName("C1: an update for a never-seen binder item with create fields is applied as a create")
    void updateOfMissingBinderItemWithCreateFieldsIsUpserted() {
        UUID itemId = UUID.randomUUID();
        ObjectNode data = mapper.createObjectNode();
        data.put("type", "folder");
        data.put("title", "Displaced Scene");
        data.put("order_key", "zzzz");

        PushResponse response = sync.push(projectId, deviceB,
                List.of(new ChangeRequest("binder_item", itemId, "update", 1L, data)));

        assertThat(response.conflicts()).isEmpty();
        assertThat(response.applied()).singleElement().satisfies(a -> {
            assertThat(a.entityId()).isEqualTo(itemId);
            assertThat(a.version()).isEqualTo(1);
        });

        assertThat(jdbc.sql("""
                SELECT count(*) FROM binder_item
                 WHERE id = :id AND project_id = :p AND deleted_at IS NULL AND type = 'folder'
                """).param("id", itemId).param("p", projectId).query(Long.class).single())
                .as("the item must exist live on the server")
                .isEqualTo(1);
    }

    @Test
    @DisplayName("C1: an update for a never-seen binder item WITHOUT create fields is still ENTITY_MISSING")
    void updateOfMissingBinderItemWithoutCreateFieldsIsRejected() {
        UUID itemId = UUID.randomUUID();
        ObjectNode data = mapper.createObjectNode();
        data.put("title", "Only a title");

        PushResponse response = sync.push(projectId, deviceB,
                List.of(new ChangeRequest("binder_item", itemId, "update", 1L, data)));

        assertThat(response.applied()).isEmpty();
        assertThat(response.conflicts()).singleElement().satisfies(c ->
                assertThat(c.reason()).isEqualTo(ConflictReason.ENTITY_MISSING));
    }

    @Test
    @DisplayName("C2: a document update after delete-and-empty-trash is preserved, not applied into the tombstone")
    void staleDocumentUpdateAfterDeletionIsPreservedLive() {
        UUID docId = seedDocument("Chapter One", "V", "original");

        binder.trash(docId, deviceA);
        binder.emptyTrash(projectId, deviceA);

        // Device B never heard about the delete; its base still matches document.version,
        // which a delete never bumps. Before the fix this write was silently accepted into
        // the tombstoned row and destroyed by retention.
        PushResponse response = sync.push(projectId, deviceB,
                List.of(documentUpdate(docId, 1L, "device B final words")));

        assertThat(response.applied()).isEmpty();
        assertThat(response.conflicts()).singleElement().satisfies(c -> {
            assertThat(c.reason()).isEqualTo(ConflictReason.ENTITY_MISSING);
            assertThat(c.conflictCopyId()).as("the words must be preserved somewhere").isNotNull();
        });

        UUID copyId = response.conflicts().get(0).conflictCopyId();
        assertThat(jdbc.sql("SELECT content::text FROM document WHERE id = :id")
                .param("id", copyId).query(String.class).single())
                .contains("device B final words");

        // The copy must be live and reachable — not tombstoned, not under the trash node.
        assertThat(jdbc.sql("""
                SELECT count(*) FROM binder_item b
                 WHERE b.id = :id AND b.deleted_at IS NULL
                   AND (b.parent_id IS NULL OR b.parent_id <> :trash)
                """).param("id", copyId).param("trash", trashNodeId())
                .query(Long.class).single())
                .as("the preserved copy must be live and outside the trash")
                .isEqualTo(1);
    }

    @Test
    @DisplayName("C2: a conflict copy forked from an original inside a trashed FOLDER is also placed at root")
    void conflictCopyOfNestedTrashedOriginalLandsAtRoot() {
        // Trashing is a move, so a document inside a trashed folder keeps that folder as
        // its parent — the folder is what moved. Checking only the immediate parent for
        // "is this the trash node" therefore misses every nested item, and "empty trash"
        // recurses over the whole subtree, so a copy placed beside such an original is
        // tombstoned with it and destroyed by retention.
        UUID folderId = UUID.randomUUID();
        jdbc.sql("""
                INSERT INTO binder_item (id, project_id, type, title, order_key, updated_by_device_id)
                VALUES (:id, :p, 'folder', 'Act One', 'm', :d)
                """).param("id", folderId).param("p", projectId).param("d", deviceA).update();

        UUID docId = seedDocument("Chapter One", "V", "original");
        jdbc.sql("UPDATE binder_item SET parent_id = :f WHERE id = :id")
                .param("f", folderId).param("id", docId).update();

        sync.push(projectId, deviceA, List.of(documentUpdate(docId, 1L, "device A rewrite")));
        binder.trash(folderId, deviceA);

        PushResponse response = sync.push(projectId, deviceB,
                List.of(documentUpdate(docId, 1L, "device B unsent words")));

        UUID copyId = response.conflicts().get(0).conflictCopyId();
        assertThat(copyId).isNotNull();
        assertThat(jdbc.sql("SELECT parent_id FROM binder_item WHERE id = :id")
                .param("id", copyId).query(UUID.class).optional().orElse(null))
                .as("the copy must sit at project root, not inside the trashed folder")
                .isNull();

        // The check that actually matters: emptying the trash must not take it.
        binder.emptyTrash(projectId, deviceA);
        assertThat(jdbc.sql("SELECT deleted_at FROM binder_item WHERE id = :id")
                .param("id", copyId).query(java.time.OffsetDateTime.class).optional().orElse(null))
                .as("emptying the trash must not tombstone the rescued words")
                .isNull();
        assertThat(jdbc.sql("SELECT content::text FROM document WHERE id = :id")
                .param("id", copyId).query(String.class).single())
                .contains("device B unsent words");
    }

    @Test
    @DisplayName("C2: a conflict copy forked from a trashed original is placed at root, not inside the trash")
    void conflictCopyOfTrashedOriginalLandsAtRoot() {
        UUID docId = seedDocument("Chapter One", "V", "original");

        // Another device edits first, moving the document on...
        sync.push(projectId, deviceA, List.of(documentUpdate(docId, 1L, "device A rewrite")));
        // ...then the item is trashed (but the trash is not emptied).
        binder.trash(docId, deviceA);

        // Device B pushes from the stale base. The original is live-but-trashed, so this
        // is a version mismatch: the copy must not be created under the trash node, where
        // the next "empty trash" would destroy it.
        PushResponse response = sync.push(projectId, deviceB,
                List.of(documentUpdate(docId, 1L, "device B rewrite")));

        assertThat(response.conflicts()).singleElement().satisfies(c ->
                assertThat(c.reason()).isEqualTo(ConflictReason.VERSION_MISMATCH));

        UUID copyId = response.conflicts().get(0).conflictCopyId();
        UUID parent = jdbc.sql("SELECT parent_id FROM binder_item WHERE id = :id")
                .param("id", copyId).query(UUID.class).optional().orElse(null);
        assertThat(parent)
                .as("the copy must sit at project root, not under the trash node")
                .isNull();

        assertThat(jdbc.sql("SELECT content::text FROM document WHERE id = :id")
                .param("id", copyId).query(String.class).single())
                .contains("device B rewrite");
    }
}
