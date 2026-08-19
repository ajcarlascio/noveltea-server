package com.noveltea.sync;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
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
 * Conflict handling, judged by one standard: <b>after any sequence of pushes, every
 * piece of text a client sent must still be readable from the database.</b>
 *
 * <p>These assert against the database directly rather than against the service's own
 * read path, so a bug that corrupted both writing and reading symmetrically would still
 * be caught.
 */
class SyncPushConflictTest extends AbstractPostgresTest {

    @Autowired SyncService sync;
    @Autowired ObjectMapper mapper;

    private ChangeRequest documentUpdate(UUID id, Long baseVersion, String text) {
        ObjectNode data = mapper.createObjectNode();
        data.set("content", parse(doc(text)));
        data.put("search_text", text);
        data.put("word_count", text.split("\\s+").length);
        return new ChangeRequest("document", id, "update", baseVersion, data);
    }

    private com.fasterxml.jackson.databind.JsonNode parse(String json) {
        try {
            return mapper.readTree(json);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    @Test
    @DisplayName("a stale write becomes a conflict copy; neither version is lost")
    void staleWriteProducesConflictCopyAndKeepsBothVersions() {
        UUID docId = seedDocument("Chapter One", "V", "original");

        PushResponse first = sync.push(projectId, deviceA,
                List.of(documentUpdate(docId, 1L, "device A rewrite")));
        assertThat(first.conflicts()).isEmpty();
        assertThat(first.applied()).singleElement().satisfies(a -> assertThat(a.version()).isEqualTo(2));

        // Device B never saw version 2 — it is still working from version 1.
        PushResponse second = sync.push(projectId, deviceB,
                List.of(documentUpdate(docId, 1L, "device B rewrite")));

        assertThat(second.applied()).isEmpty();
        assertThat(second.conflicts()).singleElement().satisfies(c -> {
            assertThat(c.reason()).isEqualTo(ConflictReason.VERSION_MISMATCH);
            assertThat(c.conflictCopyId()).as("client's text must be preserved somewhere").isNotNull();
            assertThat(c.serverVersion()).isEqualTo(2);
        });

        String liveContent = jdbc.sql("SELECT content::text FROM document WHERE id = :id")
                .param("id", docId).query(String.class).single();
        assertThat(liveContent)
                .as("the winning write must be intact and untouched by the conflict")
                .contains("device A rewrite");

        UUID copyId = second.conflicts().get(0).conflictCopyId();
        String copyContent = jdbc.sql("SELECT content::text FROM document WHERE id = :id")
                .param("id", copyId).query(String.class).single();
        assertThat(copyContent).contains("device B rewrite");

        assertThat(allStoredText())
                .as("both authors' text survives")
                .anySatisfy(t -> assertThat(t).contains("device A rewrite"))
                .anySatisfy(t -> assertThat(t).contains("device B rewrite"));
    }

    @Test
    @DisplayName("the conflict copy is a titled sibling of the original, not a replacement")
    void conflictCopyIsASiblingWithADistinguishingTitle() {
        UUID docId = seedDocument("Chapter One", "V", "original");
        sync.push(projectId, deviceA, List.of(documentUpdate(docId, 1L, "A")));
        PushResponse response = sync.push(projectId, deviceB, List.of(documentUpdate(docId, 1L, "B")));
        UUID copyId = response.conflicts().get(0).conflictCopyId();

        var copy = jdbc.sql("SELECT title, parent_id, order_key, type FROM binder_item WHERE id = :id")
                .param("id", copyId).query().singleRow();
        var original = jdbc.sql("SELECT title, parent_id, order_key FROM binder_item WHERE id = :id")
                .param("id", docId).query().singleRow();

        assertThat((String) copy.get("title")).contains("Conflicted Copy").contains("Chapter One");
        assertThat((String) original.get("title"))
                .as("the original's title must not be rewritten")
                .isEqualTo("Chapter One");
        assertThat(copy.get("parent_id")).isEqualTo(original.get("parent_id"));
        assertThat((String) copy.get("order_key"))
                .as("copy sorts immediately after the original")
                .isGreaterThan((String) original.get("order_key"));
        assertThat((String) copy.get("type")).isEqualTo("document");
    }

    @Test
    @DisplayName("re-delivered identical create is accepted without duplicating anything")
    void duplicateCreateWithIdenticalContentIsIdempotent() {
        UUID itemId = UUID.randomUUID();
        jdbc.sql("""
                INSERT INTO binder_item (id, project_id, type, title, order_key, updated_by_device_id)
                VALUES (:id, :p, 'document', 'New', 'V', :d)
                """)
                .param("id", itemId).param("p", projectId).param("d", deviceA).update();

        ObjectNode data = mapper.createObjectNode();
        data.set("content", parse(doc("hello")));
        ChangeRequest create = new ChangeRequest("document", itemId, "create", null, data);

        sync.push(projectId, deviceA, List.of(create));
        PushResponse retry = sync.push(projectId, deviceA, List.of(create));

        assertThat(retry.conflicts())
                .as("a lost ACK must not be punished with a spurious conflict copy")
                .isEmpty();
        assertThat(retry.applied()).hasSize(1);
        assertThat(jdbc.sql("SELECT count(*) FROM document").query(Long.class).single()).isEqualTo(1);
    }

    @Test
    @DisplayName("re-delivered create with DIFFERENT content preserves both")
    void duplicateCreateWithDifferentContentPreservesBoth() {
        UUID itemId = UUID.randomUUID();
        jdbc.sql("""
                INSERT INTO binder_item (id, project_id, type, title, order_key, updated_by_device_id)
                VALUES (:id, :p, 'document', 'New', 'V', :d)
                """)
                .param("id", itemId).param("p", projectId).param("d", deviceA).update();

        ObjectNode first = mapper.createObjectNode();
        first.set("content", parse(doc("first text")));
        sync.push(projectId, deviceA, List.of(new ChangeRequest("document", itemId, "create", null, first)));

        ObjectNode second = mapper.createObjectNode();
        second.set("content", parse(doc("second text")));
        PushResponse response = sync.push(
                projectId, deviceB, List.of(new ChangeRequest("document", itemId, "create", null, second)));

        assertThat(response.conflicts()).singleElement().satisfies(c ->
                assertThat(c.reason()).isEqualTo(ConflictReason.DUPLICATE_CREATE));
        assertThat(allStoredText())
                .anySatisfy(t -> assertThat(t).contains("first text"))
                .anySatisfy(t -> assertThat(t).contains("second text"));
    }

    @Test
    @DisplayName("an update for a document the server lost is recovered, not discarded")
    void updateForMissingDocumentIsPreserved() {
        UUID ghostId = UUID.randomUUID();

        PushResponse response = sync.push(projectId, deviceA,
                List.of(documentUpdate(ghostId, 3L, "words that must not vanish")));

        assertThat(response.conflicts()).singleElement().satisfies(c -> {
            assertThat(c.reason()).isEqualTo(ConflictReason.ENTITY_MISSING);
            assertThat(c.conflictCopyId()).isNotNull();
        });
        assertThat(allStoredText()).anySatisfy(t -> assertThat(t).contains("words that must not vanish"));
    }

    @Test
    @DisplayName("deleting something already gone succeeds")
    void deleteOfMissingEntityIsIdempotent() {
        PushResponse response = sync.push(projectId, deviceA,
                List.of(new ChangeRequest("document", UUID.randomUUID(), "delete", null, null)));

        assertThat(response.conflicts()).isEmpty();
        assertThat(response.applied()).hasSize(1);
    }

    @Test
    @DisplayName("one conflict does not prevent other changes in the batch from applying")
    void batchAppliesIndependently() {
        UUID contested = seedDocument("Contested", "V", "original");
        UUID clean = seedDocument("Clean", "k", "original");

        sync.push(projectId, deviceA, List.of(documentUpdate(contested, 1L, "A wins")));

        PushResponse mixed = sync.push(projectId, deviceB, List.of(
                documentUpdate(contested, 1L, "B loses"),
                documentUpdate(clean, 1L, "B wins here")));

        assertThat(mixed.conflicts()).hasSize(1);
        assertThat(mixed.applied()).hasSize(1);
        assertThat(jdbc.sql("SELECT content::text FROM document WHERE id = :id")
                .param("id", clean).query(String.class).single()).contains("B wins here");
    }

    @Test
    @DisplayName("PROPERTY: every push is either the live version at that moment, or preserved as a copy")
    void everyPushIsEitherAppliedOrPreserved() {
        UUID docId = seedDocument("Chapter One", "V", "seed");

        List<String> mustSurviveToTheEnd = new java.util.ArrayList<>();
        long knownA = 1;
        long knownB = 1;

        for (int i = 0; i < 30; i++) {
            knownA = pushAndVerify(docId, deviceA, knownA, "A-edit-" + i, mustSurviveToTheEnd);
            // Device B pushes from a stale version half the time, forcing real conflicts.
            long baseB = (i % 2 == 0) ? knownB : knownA;
            knownB = pushAndVerify(docId, deviceB, baseB, "B-edit-" + i, mustSurviveToTheEnd);
            knownA = Math.max(knownA, knownB);
        }

        assertThat(mustSurviveToTheEnd)
                .as("the run must actually have produced conflicts, or it proves nothing")
                .isNotEmpty();

        String finalState = String.join("\n", allStoredText());
        assertThat(mustSurviveToTheEnd)
                .as("every rejected edit is still readable after all subsequent activity")
                .allSatisfy(text -> assertThat(finalState).contains(text));
    }

    /**
     * Pushes one edit and asserts the outcome contract:
     * applied  -> that text IS the live document right now;
     * conflict -> that text is stored under the returned copy id.
     * Returns the version this device now knows about.
     */
    private long pushAndVerify(UUID docId, UUID device, long baseVersion, String text, List<String> preserved) {
        PushResponse response = sync.push(projectId, device, List.of(documentUpdate(docId, baseVersion, text)));

        if (!response.applied().isEmpty()) {
            assertThat(jdbc.sql("SELECT content::text FROM document WHERE id = :id")
                    .param("id", docId).query(String.class).single())
                    .as("an applied push must be the live content immediately afterwards")
                    .contains(text);
            return response.applied().get(0).version();
        }

        ConflictRecord conflict = response.conflicts().get(0);
        assertThat(conflict.conflictCopyId()).as("a rejected edit must be preserved").isNotNull();
        assertThat(jdbc.sql("SELECT content::text FROM document WHERE id = :id")
                .param("id", conflict.conflictCopyId()).query(String.class).single())
                .contains(text);
        preserved.add(text);
        return baseVersion;
    }
}
