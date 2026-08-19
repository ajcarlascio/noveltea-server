package com.noveltea.robustness;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.noveltea.binder.BinderService;
import com.noveltea.merge.MergeService;
import com.noveltea.support.AbstractPostgresTest;
import com.noveltea.sync.ConflictReason;
import com.noveltea.sync.SyncService;
import com.noveltea.sync.dto.SyncDtos.ChangeRequest;
import com.noveltea.sync.dto.SyncDtos.PushResponse;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * Malformed input must be reported, not fatal.
 *
 * <p>A client can send anything: a field the serialiser dropped, a partially built
 * request, a bug in a release you no longer control. The rule these pin down is that one
 * bad change is reported as one conflict — it never fails the batch, never returns a 500,
 * and never leaves a partial write behind.
 */
class NullSafetyTest extends AbstractPostgresTest {

    @Autowired SyncService sync;
    @Autowired BinderService binder;
    @Autowired MergeService merge;
    @Autowired ObjectMapper mapper;

    private long documentCount() {
        return jdbc.sql("SELECT count(*) FROM document").query(Long.class).single();
    }

    @Test
    @DisplayName("a null entity type is reported, not thrown")
    void nullEntityTypeIsReported() {
        // Set.of(...).contains(null) throws NullPointerException, so an unguarded lookup
        // here fails the entire batch with a 500.
        PushResponse response = sync.push(projectId, deviceA,
                List.of(new ChangeRequest(null, UUID.randomUUID(), "update", 1L, null)));

        assertThat(response.conflicts()).singleElement()
                .satisfies(c -> assertThat(c.reason()).isEqualTo(ConflictReason.INVALID_REQUEST));
        assertThat(response.applied()).isEmpty();
    }

    @Test
    @DisplayName("null id, null op, and unknown op are all reported")
    void otherMalformedFieldsAreReported() {
        PushResponse response = sync.push(projectId, deviceA, List.of(
                new ChangeRequest("document", null, "update", 1L, null),
                new ChangeRequest("document", UUID.randomUUID(), null, 1L, null),
                new ChangeRequest("document", UUID.randomUUID(), "obliterate", 1L, null)));

        assertThat(response.conflicts()).hasSize(3)
                .allSatisfy(c -> assertThat(c.reason()).isEqualTo(ConflictReason.INVALID_REQUEST));
    }

    @Test
    @DisplayName("a malformed change does not prevent a valid one in the same batch")
    void malformedChangeDoesNotPoisonTheBatch() {
        UUID docId = seedDocument("Chapter One", "V", "before");
        var data = mapper.createObjectNode();
        try {
            data.set("content", mapper.readTree(doc("after")));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }

        PushResponse response = sync.push(projectId, deviceA, List.of(
                new ChangeRequest(null, null, null, null, null),
                new ChangeRequest("document", docId, "update", 1L, data)));

        assertThat(response.conflicts()).hasSize(1);
        assertThat(response.applied()).hasSize(1);
        assertThat(jdbc.sql("SELECT content::text FROM document WHERE id = :id")
                .param("id", docId).query(String.class).single()).contains("after");
    }

    @Test
    @DisplayName("a null element inside the change list is survivable")
    void nullElementInListIsSurvivable() {
        PushResponse response = sync.push(projectId, deviceA, Arrays.asList((ChangeRequest) null));
        assertThat(response.conflicts()).hasSize(1);
    }

    @Test
    @DisplayName("a null change list is treated as empty")
    void nullChangeListIsEmpty() {
        PushResponse response = sync.push(projectId, deviceA, null);
        assertThat(response.applied()).isEmpty();
        assertThat(response.conflicts()).isEmpty();
    }

    @Test
    @DisplayName("null project id fails fast with a named argument, not deep in SQL")
    void nullProjectIdFailsFast() {
        assertThatThrownBy(() -> sync.pull(null, 0, 10))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("projectId");
        assertThatThrownBy(() -> binder.tree(null))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("projectId");
        assertThatThrownBy(() -> merge.listConflicts(null))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("projectId");
    }

    @Test
    @DisplayName("blank or null titles are rejected before reaching the database")
    void blankTitlesAreRejected() {
        assertThatThrownBy(() -> binder.create(projectId, deviceA, null, "folder", null, null))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("title");
        assertThatThrownBy(() -> binder.create(projectId, deviceA, null, "folder", "   ", null))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("title");
        assertThat(binder.tree(projectId)).isEmpty();
    }

    @Test
    @DisplayName("an unknown item type is rejected with a readable message, not a constraint violation")
    void unknownTypeIsRejectedReadably() {
        assertThatThrownBy(() -> binder.create(projectId, deviceA, null, "chapter", "X", null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("type must be one of");
    }

    @Test
    @DisplayName("resolving with null content is refused rather than blanking the document")
    void nullMergedContentIsRefused() {
        UUID docId = seedDocument("Chapter One", "V", "precious words");
        assertThatThrownBy(() -> merge.resolve(docId, null, 1L, deviceA))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("must not be null");
        assertThat(jdbc.sql("SELECT content::text FROM document WHERE id = :id")
                .param("id", docId).query(String.class).single()).contains("precious words");
        assertThat(documentCount()).isEqualTo(1);
    }
}
