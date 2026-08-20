package com.noveltea.snapshot;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.noveltea.binder.BinderService;
import com.noveltea.support.AbstractPostgresTest;
import com.noveltea.sync.ConflictReason;
import com.noveltea.sync.SyncService;
import com.noveltea.sync.dto.SyncDtos.ChangeRequest;
import com.noveltea.sync.dto.SyncDtos.PushResponse;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/** How snapshots behave over sync, where the manual/automatic split actually bites. */
class SnapshotSyncTest extends AbstractPostgresTest {

    @Autowired SyncService sync;
    @Autowired SnapshotService snapshots;
    @Autowired BinderService binder;
    @Autowired ObjectMapper mapper;

    private ObjectNode snapshotData(UUID documentId, String text, String label) {
        ObjectNode data = mapper.createObjectNode();
        data.put("document_id", documentId.toString());
        try {
            data.set("content", mapper.readTree(doc(text)));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
        data.put("word_count", 3);
        if (label != null) data.put("label", label);
        return data;
    }

    @Test
    @DisplayName("a snapshot pushed from another device is stored as manual")
    void pushedSnapshotsAreManual() {
        UUID docId = seedDocument("Chapter One", "V", "draft");
        UUID snapshotId = UUID.randomUUID();

        PushResponse response = sync.push(projectId, deviceB, List.of(
                new ChangeRequest("snapshot", snapshotId, "create", null,
                        snapshotData(docId, "captured elsewhere", "milestone"))));

        assertThat(response.conflicts()).isEmpty();
        assertThat(jdbc.sql("SELECT is_automatic FROM snapshot WHERE id = :id")
                .param("id", snapshotId).query(Boolean.class).single())
                .as("only manual snapshots travel, so anything arriving is manual by definition")
                .isFalse();
        assertThat(snapshots.get(snapshotId).content().toString()).contains("captured elsewhere");
    }

    @Test
    @DisplayName("a snapshot for another project's document is refused")
    void crossProjectSnapshotRefused() {
        UUID otherUser = UUID.randomUUID();
        UUID otherProject = UUID.randomUUID();
        jdbc.sql("INSERT INTO app_user (id, email) VALUES (:id, :e)")
                .param("id", otherUser).param("e", otherUser + "@example.com").update();
        jdbc.sql("INSERT INTO project (id, owner_id, title) VALUES (:id, :o, 'Theirs')")
                .param("id", otherProject).param("o", otherUser).update();
        UUID foreignDoc = binder.create(otherProject, deviceA, null, "document", "Theirs", null);

        UUID snapshotId = UUID.randomUUID();
        PushResponse response = sync.push(projectId, deviceA, List.of(
                new ChangeRequest("snapshot", snapshotId, "create", null,
                        snapshotData(foreignDoc, "stolen history", null))));

        assertThat(response.conflicts()).singleElement()
                .satisfies(c -> assertThat(c.reason()).isEqualTo(ConflictReason.INVALID_REQUEST));
        assertThat(jdbc.sql("SELECT count(*) FROM snapshot WHERE id = :id")
                .param("id", snapshotId).query(Long.class).single()).isZero();
    }

    @Test
    @DisplayName("a snapshot without content or a document is refused")
    void incompleteSnapshotRefused() {
        PushResponse response = sync.push(projectId, deviceA, List.of(
                new ChangeRequest("snapshot", UUID.randomUUID(), "create", null,
                        mapper.createObjectNode())));

        assertThat(response.conflicts()).singleElement()
                .satisfies(c -> assertThat(c.detail()).contains("document_id"));
    }

    @Test
    @DisplayName("snapshots are immutable: an update is refused rather than rewriting history")
    void updatesAreRefused() {
        UUID docId = seedDocument("Chapter One", "V", "draft");
        UUID snapshotId = snapshots.capture(docId, "keep", false, deviceA);

        PushResponse response = sync.push(projectId, deviceA, List.of(
                new ChangeRequest("snapshot", snapshotId, "update", 1L,
                        snapshotData(docId, "tampered", "keep"))));

        assertThat(response.conflicts()).singleElement()
                .satisfies(c -> assertThat(c.detail()).contains("immutable"));
        assertThat(snapshots.get(snapshotId).content().toString()).doesNotContain("tampered");
    }

    @Test
    @DisplayName("a re-delivered snapshot create is accepted without duplicating it")
    void duplicateCreateIsIdempotent() {
        UUID docId = seedDocument("Chapter One", "V", "draft");
        UUID snapshotId = UUID.randomUUID();
        var change = new ChangeRequest("snapshot", snapshotId, "create", null,
                snapshotData(docId, "captured", "milestone"));

        sync.push(projectId, deviceA, List.of(change));
        PushResponse retry = sync.push(projectId, deviceA, List.of(change));

        assertThat(retry.conflicts()).isEmpty();
        assertThat(jdbc.sql("SELECT count(*) FROM snapshot").query(Long.class).single()).isEqualTo(1);
    }

    @Test
    @DisplayName("THE FEED CARRIES SNAPSHOT METADATA, NEVER ITS CONTENT")
    void feedOmitsSnapshotContent() {
        UUID docId = seedDocument("Chapter One", "V", "draft");
        snapshots.capture(docId, "a milestone worth keeping", false, deviceA);

        var pull = sync.pull(projectId, 0, 100);
        var entry = pull.changes().stream()
                .filter(c -> "snapshot".equals(c.entityType()))
                .findFirst()
                .orElseThrow();

        assertThat(entry.data().has("label")).as("metadata travels").isTrue();
        assertThat(entry.data().get("label").asText()).isEqualTo("a milestone worth keeping");
        assertThat(entry.data().has("content"))
                .as("a full document copy per snapshot would dominate every sync")
                .isFalse();
        assertThat(entry.data().has("word_count")).isTrue();
    }

    @Test
    @DisplayName("an automatic snapshot never reaches the feed at all")
    void automaticSnapshotsAreInvisibleToOtherDevices() {
        UUID docId = seedDocument("Chapter One", "V", "draft");
        snapshots.capture(docId, null, true, deviceA);

        assertThat(sync.pull(projectId, 0, 100).changes())
                .filteredOn(c -> "snapshot".equals(c.entityType()))
                .isEmpty();
    }

    @Test
    @DisplayName("a deleted manual snapshot is removed on the other device too")
    void deletionPropagates() {
        UUID docId = seedDocument("Chapter One", "V", "draft");
        UUID snapshotId = snapshots.capture(docId, "keep", false, deviceA);

        PushResponse response = sync.push(projectId, deviceB, List.of(
                new ChangeRequest("snapshot", snapshotId, "delete", 1L, null)));

        assertThat(response.conflicts()).isEmpty();
        assertThat(jdbc.sql("SELECT count(*) FROM snapshot WHERE id = :id")
                .param("id", snapshotId).query(Long.class).single()).isZero();
        assertThat(sync.pull(projectId, 0, 100).changes())
                .anySatisfy(c -> {
                    assertThat(c.entityType()).isEqualTo("snapshot");
                    assertThat(c.op()).isEqualTo("delete");
                });
    }

    @Test
    @DisplayName("a comment made offline can be pushed, with authorship from the device")
    void commentsCanBePushed() {
        UUID docId = seedDocument("Chapter One", "V", "draft");
        UUID commentId = UUID.randomUUID();
        ObjectNode data = mapper.createObjectNode();
        data.put("document_id", docId.toString()).put("body", "written on the train");

        PushResponse response = sync.push(projectId, deviceA, List.of(
                new ChangeRequest("comment", commentId, "create", null, data)));

        assertThat(response.conflicts()).isEmpty();
        assertThat(jdbc.sql("SELECT body FROM comment WHERE id = :id")
                .param("id", commentId).query(String.class).single())
                .isEqualTo("written on the train");
        assertThat(jdbc.sql("SELECT author_user_id FROM comment WHERE id = :id")
                .param("id", commentId).query(UUID.class).single())
                .as("authorship comes from the pushing device, never the payload")
                .isEqualTo(userId);
    }

    @Test
    @DisplayName("a pushed comment cannot forge its author")
    void pushedCommentCannotForgeAuthor() {
        UUID docId = seedDocument("Chapter One", "V", "draft");
        UUID commentId = UUID.randomUUID();
        ObjectNode data = mapper.createObjectNode();
        data.put("document_id", docId.toString()).put("body", "not mine")
                .put("author_user_id", UUID.randomUUID().toString());

        sync.push(projectId, deviceA, List.of(
                new ChangeRequest("comment", commentId, "create", null, data)));

        assertThat(jdbc.sql("SELECT author_user_id FROM comment WHERE id = :id")
                .param("id", commentId).query(UUID.class).single())
                .as("a client must not be able to attribute a remark to someone else")
                .isEqualTo(userId);
    }

    @Test
    @DisplayName("a comment for another project's document is refused")
    void crossProjectCommentRefused() {
        UUID otherUser = UUID.randomUUID();
        UUID otherProject = UUID.randomUUID();
        jdbc.sql("INSERT INTO app_user (id, email) VALUES (:id, :e)")
                .param("id", otherUser).param("e", otherUser + "@example.com").update();
        jdbc.sql("INSERT INTO project (id, owner_id, title) VALUES (:id, :o, 'Theirs')")
                .param("id", otherProject).param("o", otherUser).update();
        UUID foreignDoc = binder.create(otherProject, deviceA, null, "document", "Theirs", null);

        UUID commentId = UUID.randomUUID();
        ObjectNode data = mapper.createObjectNode();
        data.put("document_id", foreignDoc.toString()).put("body", "intruding");

        PushResponse response = sync.push(projectId, deviceA, List.of(
                new ChangeRequest("comment", commentId, "create", null, data)));

        assertThat(response.conflicts()).hasSize(1);
        assertThat(jdbc.sql("SELECT count(*) FROM comment WHERE id = :id")
                .param("id", commentId).query(Long.class).single()).isZero();
    }

    @Test
    @DisplayName("a pushed comment reaches other devices through the feed")
    void pushedCommentsSync() {
        UUID docId = seedDocument("Chapter One", "V", "draft");
        UUID commentId = UUID.randomUUID();
        ObjectNode data = mapper.createObjectNode();
        data.put("document_id", docId.toString()).put("body", "a note");
        sync.push(projectId, deviceA, List.of(
                new ChangeRequest("comment", commentId, "create", null, data)));

        assertThat(sync.pull(projectId, 0, 100).changes())
                .anySatisfy(c -> assertThat(c.entityType()).isEqualTo("comment"));
    }
}
