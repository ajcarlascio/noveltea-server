package com.noveltea.sync;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.noveltea.support.AbstractPostgresTest;
import com.noveltea.sync.ProjectStateService.EntityRow;
import com.noveltea.sync.dto.SyncDtos.ChangeRecord;
import com.noveltea.sync.dto.SyncDtos.ChangeRequest;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * The endpoint exists so a rebuild restores everything hung on the tree, not only the tree.
 * Each test is a way that could silently fail: a type left out, a row arriving before the
 * one it references, another project's rows leaking in, or a shape the client cannot apply.
 */
class ProjectStateServiceTest extends AbstractPostgresTest {

    @Autowired ProjectStateService state;
    @Autowired SyncService sync;
    @Autowired ObjectMapper mapper;

    private UUID push(String type, ObjectNode data) {
        UUID id = UUID.randomUUID();
        assertThat(sync.push(projectId, deviceA, List.of(new ChangeRequest(type, id, "create", null, data)))
                .conflicts()).as("seeding %s", type).isEmpty();
        return id;
    }

    private UUID label(String name) {
        return push("taxonomy", mapper.createObjectNode()
                .put("kind", "label").put("name", name).put("order_key", "V"));
    }

    private UUID field(String name) {
        return push("custom_metadata_field", mapper.createObjectNode()
                .put("name", name).put("field_type", "text").put("order_key", "V"));
    }

    private UUID collection(String name) {
        return push("collection", mapper.createObjectNode().put("name", name).put("order_key", "V"));
    }

    private UUID comment(UUID documentId, UUID parentId, String body) {
        UUID id = UUID.randomUUID();
        jdbc.sql("""
                INSERT INTO comment (id, project_id, document_id, parent_comment_id, body)
                VALUES (:id, :projectId, :documentId, :parentId, :body)
                """)
                .param("id", id).param("projectId", projectId).param("documentId", documentId)
                .param("parentId", parentId).param("body", body).update();
        return id;
    }

    private UUID snapshot(UUID documentId, boolean automatic) {
        UUID id = UUID.randomUUID();
        jdbc.sql("""
                INSERT INTO snapshot (id, document_id, project_id, content, is_automatic)
                VALUES (:id, :documentId, :projectId, CAST(:content AS jsonb), :automatic)
                """)
                .param("id", id).param("documentId", documentId).param("projectId", projectId)
                .param("content", doc("kept")).param("automatic", automatic).update();
        return id;
    }

    private List<UUID> idsOf(String type) {
        return state.state(projectId).stream()
                .filter(row -> row.entityType().equals(type)).map(EntityRow::entityId).toList();
    }

    @Test
    @DisplayName("returns every type the binder and documents leave out, in insertion order")
    void everyTypeInOrder() {
        UUID chapter = seedDocument("Chapter One", "a1", "words");
        UUID label = label("Draft");
        UUID field = field("POV");
        UUID collection = collection("Act I");
        push("compile_preset", mapper.createObjectNode().put("name", "Manuscript").put("format", "md")
                .set("included_binder_items", mapper.createArrayNode().add(chapter.toString())));
        push("custom_metadata_value", mapper.createObjectNode()
                .put("binder_item_id", chapter.toString()).put("field_id", field.toString()));
        push("collection_item", mapper.createObjectNode()
                .put("collection_id", collection.toString()).put("binder_item_id", chapter.toString())
                .put("order_key", "V"));
        UUID thread = comment(chapter, null, "Tighten this");
        comment(chapter, thread, "Agreed");
        snapshot(chapter, false);

        List<String> types = state.state(projectId).stream().map(EntityRow::entityType).toList();

        // Everything a row references comes before it, so the client inserts in this order
        // without a foreign key failure; the last four need binder items, which come first.
        assertThat(types).containsExactly(
                "taxonomy", "custom_metadata_field", "collection", "compile_preset",
                "custom_metadata_value", "collection_item", "comment", "comment", "snapshot");
        assertThat(label).isNotNull();
    }

    @Test
    @DisplayName("each row carries what the change feed carries for the same entity")
    void sameShapeAsTheFeed() {
        UUID label = label("Draft");

        ChangeRecord fed = sync.pull(projectId, 0, 100).changes().stream()
                .filter(c -> c.entityId().equals(label)).findFirst().orElseThrow();
        EntityRow row = state.state(projectId).get(0);

        // The client applies these with the code that applies a pull. A shape of its own
        // would be a second translation to keep in step with the first.
        assertThat(row.entityType()).isEqualTo(fed.entityType());
        assertThat(row.entityId()).isEqualTo(label);
        assertThat(row.data()).isEqualTo(fed.data());
    }

    @Test
    @DisplayName("includes tombstones, so nothing that references one is left dangling")
    void includesTombstones() {
        UUID chapter = seedDocument("Chapter One", "a1", "words");
        UUID retired = label("Retired");
        jdbc.sql("UPDATE binder_item SET label_id = :label WHERE id = :id")
                .param("label", retired).param("id", chapter).update();
        jdbc.sql("UPDATE taxonomy SET deleted_at = now() WHERE id = :id").param("id", retired).update();
        UUID thread = comment(chapter, null, "Cut this");
        UUID reply = comment(chapter, thread, "Done");
        jdbc.sql("UPDATE comment SET deleted_at = now() WHERE id = :id").param("id", thread).update();

        // The binder still names the retired label and the reply still names its thread.
        // A device that has never seen either would fail the foreign key on insert.
        assertThat(idsOf("taxonomy")).containsExactly(retired);
        assertThat(idsOf("comment")).containsExactly(thread, reply);
        assertThat(state.state(projectId).get(0).data().get("deleted_at").isNull()).isFalse();
    }

    @Test
    @DisplayName("leaves out rows hanging off a tombstoned binder item, which /binder does not list")
    void excludesChildrenOfTombstonedItems() {
        UUID kept = seedDocument("Kept", "a1", "one");
        UUID gone = seedDocument("Gone", "a2", "two");
        UUID field = field("POV");
        UUID keptValue = push("custom_metadata_value", mapper.createObjectNode()
                .put("binder_item_id", kept.toString()).put("field_id", field.toString()));
        push("custom_metadata_value", mapper.createObjectNode()
                .put("binder_item_id", gone.toString()).put("field_id", field.toString()));
        UUID keptComment = comment(kept, null, "Here");
        comment(gone, null, "Gone with it");
        jdbc.sql("UPDATE binder_item SET deleted_at = now() WHERE id = :id").param("id", gone).update();

        assertThat(idsOf("custom_metadata_value")).containsExactly(keptValue);
        assertThat(idsOf("comment")).containsExactly(keptComment);
    }

    @Test
    @DisplayName("returns manual snapshots without their content, and never automatic ones")
    void manualSnapshotsOnly() {
        UUID chapter = seedDocument("Chapter One", "a1", "words");
        UUID manual = snapshot(chapter, false);
        snapshot(chapter, true);

        List<EntityRow> snapshots = state.state(projectId).stream()
                .filter(row -> row.entityType().equals("snapshot")).toList();

        // Automatic captures stay on the device that made them; the feed never carries
        // them and neither does this.
        assertThat(snapshots).extracting(EntityRow::entityId).containsExactly(manual);
        assertThat(snapshots.get(0).data().has("content")).isFalse();
    }

    @Test
    @DisplayName("never returns a row from another project")
    void scopedToProject() {
        UUID mine = seedDocument("Mine", "a1", "mine");
        UUID myCollection = collection("Mine");

        UUID otherUser = UUID.randomUUID();
        UUID otherProject = UUID.randomUUID();
        jdbc.sql("INSERT INTO app_user (id, email) VALUES (:id, :email)")
                .param("id", otherUser).param("email", "other-" + otherUser + "@example.com").update();
        jdbc.sql("INSERT INTO project (id, owner_id, title) VALUES (:id, :owner, 'Theirs')")
                .param("id", otherProject).param("owner", otherUser).update();
        jdbc.sql("""
                INSERT INTO taxonomy (project_id, kind, name, order_key)
                VALUES (:projectId, 'label', 'Secret', 'V')
                """).param("projectId", otherProject).update();
        UUID theirCollection = UUID.randomUUID();
        jdbc.sql("INSERT INTO collection (id, project_id, name, order_key) VALUES (:id, :p, 'Theirs', 'V')")
                .param("id", theirCollection).param("p", otherProject).update();
        // Planted across the boundary, which the push path refuses: one of mine in their
        // collection. Neither side's listing may surface it.
        jdbc.sql("""
                INSERT INTO collection_item (collection_id, binder_item_id, order_key)
                VALUES (:c, :b, 'V')
                """).param("c", theirCollection).param("b", mine).update();

        List<EntityRow> rows = state.state(projectId);

        assertThat(rows).extracting(EntityRow::entityId).containsExactly(myCollection);
    }
}
