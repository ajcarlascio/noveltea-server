package com.noveltea.merge;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.noveltea.merge.MergeExceptions.NotAConflictCopy;
import com.noveltea.merge.MergeExceptions.StaleOriginal;
import com.noveltea.support.AbstractPostgresTest;
import com.noveltea.sync.SyncService;
import com.noveltea.sync.dto.SyncDtos.ChangeRequest;
import com.noveltea.sync.dto.SyncDtos.PushResponse;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

class MergeServiceTest extends AbstractPostgresTest {

    @Autowired MergeService merge;
    @Autowired SyncService sync;
    @Autowired ObjectMapper mapper;

    private JsonNode parse(String json) {
        try {
            return mapper.readTree(json);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private ChangeRequest update(UUID id, Long base, String text) {
        ObjectNode data = mapper.createObjectNode();
        data.set("content", parse(doc(text)));
        return new ChangeRequest("document", id, "update", base, data);
    }

    /** Drives a real conflict through SyncService and returns {originalId, copyId}. */
    private UUID[] provokeConflict() {
        UUID docId = seedDocument("Chapter One", "V", "original");
        sync.push(projectId, deviceA, List.of(update(docId, 1L, "device A text")));
        PushResponse losing = sync.push(projectId, deviceB, List.of(update(docId, 1L, "device B text")));
        return new UUID[] {docId, losing.conflicts().get(0).conflictCopyId()};
    }

    private String contentOf(UUID id) {
        return jdbc.sql("SELECT content::text FROM document WHERE id = :id")
                .param("id", id).query(String.class).single();
    }

    private long versionOf(UUID id) {
        return jdbc.sql("SELECT version FROM document WHERE id = :id")
                .param("id", id).query(Long.class).single();
    }

    @Test
    @DisplayName("a sync conflict records a real edge, not just a title")
    void conflictCopyCarriesAForeignKeyToItsOriginal() {
        UUID[] pair = provokeConflict();

        UUID linked = jdbc.sql("SELECT conflict_of_id FROM binder_item WHERE id = :id")
                .param("id", pair[1]).query(UUID.class).optional().orElse(null);
        assertThat(linked)
                .as("the merge editor must not have to parse a generated title")
                .isEqualTo(pair[0]);

        Long forkedFrom = jdbc.sql("SELECT conflict_base_version FROM binder_item WHERE id = :id")
                .param("id", pair[1]).query(Long.class).optional().orElse(null);
        assertThat(forkedFrom).as("records how far behind the losing client was").isEqualTo(1L);
    }

    @Test
    @DisplayName("conflicts are found by the edge even when titles are rewritten")
    void listingSurvivesTitleEdits() {
        UUID[] pair = provokeConflict();
        jdbc.sql("UPDATE binder_item SET title = 'Renamed by the author' WHERE id = :id")
                .param("id", pair[1]).update();

        assertThat(merge.listConflicts(projectId))
                .singleElement()
                .satisfies(c -> {
                    assertThat(c.copyId()).isEqualTo(pair[1]);
                    assertThat(c.originalId()).isEqualTo(pair[0]);
                    assertThat(c.copyTitle()).isEqualTo("Renamed by the author");
                });
    }

    @Test
    @DisplayName("detail returns both sides, distinctly")
    void detailReturnsBothVersions() {
        UUID[] pair = provokeConflict();

        MergeService.ConflictDetail detail = merge.get(pair[1]);

        assertThat(detail.originalContent().toString()).contains("device A text");
        assertThat(detail.copyContent().toString()).contains("device B text");
        assertThat(detail.originalVersion()).isEqualTo(2);
        assertThat(detail.forkedFromVersion()).isEqualTo(1);
    }

    @Test
    @DisplayName("resolving writes the merged text and retires the copy")
    void resolveWritesMergedContent() {
        UUID[] pair = provokeConflict();
        JsonNode merged = parse(doc("device A text and device B text, reconciled"));

        long newVersion = merge.resolve(pair[1], merged, 2L, deviceA);

        assertThat(newVersion).isEqualTo(3);
        assertThat(contentOf(pair[0])).contains("reconciled");
        assertThat(versionOf(pair[0])).isEqualTo(3);
        assertThat(merge.listConflicts(projectId)).as("no longer an open conflict").isEmpty();
    }

    @Test
    @DisplayName("resolving TRASHES the copy rather than destroying it")
    void resolveTrashesRatherThanDeletes() {
        UUID[] pair = provokeConflict();
        merge.resolve(pair[1], parse(doc("merged")), 2L, deviceA);

        UUID trashId = jdbc.sql("SELECT id FROM binder_item WHERE project_id = :p AND type = 'trash'")
                .param("p", projectId).query(UUID.class).single();
        UUID copyParent = jdbc.sql("SELECT parent_id FROM binder_item WHERE id = :id")
                .param("id", pair[1]).query(UUID.class).optional().orElse(null);

        assertThat(copyParent).as("a bad merge must remain recoverable").isEqualTo(trashId);
        assertThat(contentOf(pair[1])).contains("device B text");
        assertThat(jdbc.sql("SELECT deleted_at FROM binder_item WHERE id = :id")
                .param("id", pair[1]).query(java.time.OffsetDateTime.class).optional())
                .as("trashed, not tombstoned")
                .isEmpty();
    }

    @Test
    @DisplayName("a stale merge is refused and changes NOTHING")
    void staleResolveIsAtomicallyRefused() {
        UUID[] pair = provokeConflict();

        // A third device edits the original while the author is merging.
        sync.push(projectId, deviceA, List.of(update(pair[0], 2L, "moved on without you")));
        String contentBefore = contentOf(pair[0]);
        long versionBefore = versionOf(pair[0]);

        assertThatThrownBy(() -> merge.resolve(pair[1], parse(doc("stale merge")), 2L, deviceA))
                .isInstanceOf(StaleOriginal.class);

        assertThat(contentOf(pair[0])).as("original untouched").isEqualTo(contentBefore);
        assertThat(versionOf(pair[0])).isEqualTo(versionBefore);
        assertThat(merge.listConflicts(projectId))
                .as("the conflict must still be open so the author can retry")
                .hasSize(1);
        assertThat(jdbc.sql("SELECT parent_id FROM binder_item WHERE id = :id")
                .param("id", pair[1]).query(UUID.class).optional().orElse(null))
                .as("copy must not have been trashed by a failed resolve")
                .isNull();
    }

    @Test
    @DisplayName("resolving something that is not a conflict copy is refused")
    void resolvingAnOrdinaryDocumentIsRefused() {
        UUID plain = seedDocument("Ordinary", "V", "text");
        assertThatThrownBy(() -> merge.resolve(plain, parse(doc("x")), 1L, deviceA))
                .isInstanceOf(NotAConflictCopy.class);
        assertThat(contentOf(plain)).contains("text");
    }

    @Test
    @DisplayName("resolving twice is refused the second time")
    void doubleResolveIsRefused() {
        UUID[] pair = provokeConflict();
        merge.resolve(pair[1], parse(doc("merged once")), 2L, deviceA);

        assertThatThrownBy(() -> merge.resolve(pair[1], parse(doc("merged twice")), 3L, deviceA))
                .isInstanceOf(NotAConflictCopy.class);
        assertThat(contentOf(pair[0])).contains("merged once");
    }

    @Test
    @DisplayName("resolving records feed rows for both documents")
    void resolveIsVisibleToOtherDevices() {
        UUID[] pair = provokeConflict();
        long before = jdbc.sql("SELECT count(*) FROM change_log WHERE project_id = :p")
                .param("p", projectId).query(Long.class).single();

        merge.resolve(pair[1], parse(doc("merged")), 2L, deviceA);

        long after = jdbc.sql("SELECT count(*) FROM change_log WHERE project_id = :p")
                .param("p", projectId).query(Long.class).single();
        assertThat(after - before)
                .as("original update, copy unlink, copy trash — and the trash node's creation")
                .isGreaterThanOrEqualTo(3);

        assertThat(jdbc.sql("""
                SELECT count(*) FROM change_log
                 WHERE project_id = :p AND entity_type = 'document' AND entity_id = :id
                """).param("p", projectId).param("id", pair[0]).query(Long.class).single())
                .as("other devices must learn the original changed")
                .isGreaterThan(0L);
    }
}
