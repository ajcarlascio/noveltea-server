package com.noveltea.sync;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.noveltea.config.LimitProperties;
import com.noveltea.support.AbstractPostgresTest;
import com.noveltea.sync.dto.SyncDtos.ChangeRequest;
import com.noveltea.sync.dto.SyncDtos.PushResponse;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * The bounds in {@link LimitProperties} that the hand-written push paths were not
 * applying.
 *
 * <p>Spec-driven entities have been size-checked since they were written, because
 * {@code SyncEntityWriter} validates against a declared shape. Documents and binder items
 * are deliberately not spec-driven — conflict copies and tree semantics do not belong in a
 * declarative table — so every bound they obey has to be written out, and three of them
 * never were. A bound that exists in configuration and nowhere else is not a bound.
 *
 * <p>Each of these is checked against the <em>configured</em> value rather than a literal,
 * so lowering a limit in {@code application.yml} cannot leave a test asserting the old one.
 */
class SyncAdmissionLimitsTest extends AbstractPostgresTest {

    @Autowired SyncService sync;
    @Autowired ObjectMapper mapper;
    @Autowired LimitProperties limits;

    private JsonNode parse(String json) {
        try {
            return mapper.readTree(json);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    /**
     * Siblings carry a unique index on {@code order_key}, so every create in a batch needs
     * its own. Zero-padded to keep the keys in the order they were made, which is what a
     * fractional index would have produced anyway.
     */
    private ChangeRequest binderCreate(UUID id, String title, int ordinal) {
        ObjectNode data = mapper.createObjectNode();
        data.put("type", "document");
        data.put("title", title);
        data.put("order_key", String.format("V%05d", ordinal));
        return new ChangeRequest("binder_item", id, "create", null, data);
    }

    private ChangeRequest binderCreate(UUID id, String title) {
        return binderCreate(id, title, 0);
    }

    private ChangeRequest documentUpdate(UUID id, Long baseVersion, String text) {
        ObjectNode data = mapper.createObjectNode();
        data.set("content", parse(doc(text)));
        return new ChangeRequest("document", id, "update", baseVersion, data);
    }

    // ------------------------------------------------------------ title length

    @Test
    @DisplayName("a title past the limit is refused, and the row is not written")
    void oversizedTitleOnCreateIsRefused() {
        UUID id = UUID.randomUUID();
        String tooLong = "x".repeat(limits.maxTitleLength() + 1);

        PushResponse response = sync.push(projectId, deviceA, List.of(binderCreate(id, tooLong)));

        assertThat(response.applied()).isEmpty();
        assertThat(response.conflicts()).singleElement().satisfies(c -> {
            assertThat(c.reason()).isEqualTo(ConflictReason.INVALID_REQUEST);
            // The message is ours, so it may be echoed — and it has to name the number,
            // because a client cannot truncate to a bound it was not told.
            assertThat(c.detail()).contains(String.valueOf(limits.maxTitleLength()));
        });
        assertThat(jdbc.sql("SELECT count(*) FROM binder_item WHERE id = :id")
                .param("id", id).query(Long.class).single()).isZero();
    }

    @Test
    @DisplayName("the same bound applies on update, not only on create")
    void oversizedTitleOnUpdateIsRefused() {
        UUID id = seedDocument("Chapter One", "V", "original");
        ObjectNode data = mapper.createObjectNode();
        data.put("title", "y".repeat(limits.maxTitleLength() + 1));

        PushResponse response = sync.push(projectId, deviceA,
                List.of(new ChangeRequest("binder_item", id, "update", 1L, data)));

        assertThat(response.applied()).isEmpty();
        assertThat(response.conflicts()).singleElement()
                .satisfies(c -> assertThat(c.reason()).isEqualTo(ConflictReason.INVALID_REQUEST));
        assertThat(jdbc.sql("SELECT title FROM binder_item WHERE id = :id")
                .param("id", id).query(String.class).single()).isEqualTo("Chapter One");
    }

    @Test
    @DisplayName("a title exactly at the limit is accepted")
    void titleAtTheLimitIsAccepted() {
        UUID id = UUID.randomUUID();
        String exact = "x".repeat(limits.maxTitleLength());

        PushResponse response = sync.push(projectId, deviceA, List.of(binderCreate(id, exact)));

        assertThat(response.conflicts()).isEmpty();
        assertThat(response.applied()).hasSize(1);
    }

    @Test
    @DisplayName("a conflict copy of a maximum-length title stays within the limit")
    void conflictCopyTitleIsTrimmedToFit() {
        // The copy's title is the original plus a suffix naming the device and the time.
        // An original already at the bound would push the pair over it, which is how a
        // limit enforced only on input gets exceeded by the server's own writes.
        UUID id = seedDocument("x".repeat(limits.maxTitleLength()), "V", "original");

        // Device A moves the document to version 2 …
        assertThat(sync.push(projectId, deviceA, List.of(documentUpdate(id, 1L, "first"))).conflicts())
                .isEmpty();
        // … and device B, still holding version 1, loses. Its text becomes the copy.
        PushResponse stale = sync.push(projectId, deviceB, List.of(documentUpdate(id, 1L, "second")));

        assertThat(stale.conflicts()).singleElement()
                .satisfies(c -> assertThat(c.reason()).isEqualTo(ConflictReason.VERSION_MISMATCH));
        UUID copyId = stale.conflicts().get(0).conflictCopyId();
        assertThat(copyId).isNotNull();

        String copyTitle = jdbc.sql("SELECT title FROM binder_item WHERE id = :id")
                .param("id", copyId).query(String.class).single();
        assertThat(copyTitle).hasSizeLessThanOrEqualTo(limits.maxTitleLength());
        // Trimmed from the original end, so the part that says which copy this is survives.
        assertThat(copyTitle).contains("Conflicted Copy");
    }

    // ----------------------------------------------------------- document size

    @Test
    @DisplayName("document content past the limit is refused, and the stored text is untouched")
    void oversizedDocumentIsRefused() {
        UUID id = seedDocument("Chapter One", "V", "original");
        // Comfortably past the bound without building anything enormous: the payload is
        // JSON, so the text alone exceeding it is sufficient.
        String huge = "a".repeat(limits.maxDocumentBytes() + 1);

        PushResponse response = sync.push(projectId, deviceA, List.of(documentUpdate(id, 1L, huge)));

        assertThat(response.applied()).isEmpty();
        assertThat(response.conflicts()).singleElement().satisfies(c -> {
            assertThat(c.reason()).isEqualTo(ConflictReason.INVALID_REQUEST);
            assertThat(c.detail()).contains(String.valueOf(limits.maxDocumentBytes()));
            // No conflict copy: refusing a write is not the same as losing a race, and a
            // copy would store the very payload just declared too large.
            assertThat(c.conflictCopyId()).isNull();
        });
        assertThat(allStoredText()).allSatisfy(text -> assertThat(text).contains("original"));
    }

    @Test
    @DisplayName("the document bound is bytes, not characters")
    void documentLimitCountsUtf8Bytes() {
        UUID id = seedDocument("Chapter One", "V", "original");
        // Three bytes per character in UTF-8, one per char in UTF-16. Measured with
        // String.length() this passes at a third of its real weight — which is exactly
        // what prose looks like: curly quotes, em dashes, accented names.
        int chars = (limits.maxDocumentBytes() / 3) + 1;
        String multibyte = "中".repeat(chars);

        PushResponse response = sync.push(projectId, deviceA, List.of(documentUpdate(id, 1L, multibyte)));

        assertThat(multibyte.length()).isLessThan(limits.maxDocumentBytes());
        assertThat(response.applied()).isEmpty();
        assertThat(response.conflicts()).singleElement()
                .satisfies(c -> assertThat(c.reason()).isEqualTo(ConflictReason.INVALID_REQUEST));
    }

    // -------------------------------------------------------------- batch size

    @Test
    @DisplayName("a push past the batch limit is refused whole, before anything is applied")
    void oversizedBatchIsRefusedBeforeAnyWrite() {
        List<ChangeRequest> changes = new ArrayList<>();
        for (int i = 0; i <= limits.maxPushBatchSize(); i++) {
            changes.add(binderCreate(UUID.randomUUID(), "Scene " + i, i));
        }

        assertThatThrownBy(() -> sync.push(projectId, deviceA, changes))
                .isInstanceOf(SyncExceptions.PushBatchTooLarge.class)
                .satisfies(e -> {
                    SyncExceptions.PushBatchTooLarge tooLarge = (SyncExceptions.PushBatchTooLarge) e;
                    assertThat(tooLarge.limit()).isEqualTo(limits.maxPushBatchSize());
                    assertThat(tooLarge.sent()).isEqualTo(changes.size());
                });

        // Nothing committed. Each change applies in its own transaction, so a batch
        // abandoned partway would leave rows behind for a request the client must resend.
        assertThat(jdbc.sql("SELECT count(*) FROM binder_item WHERE project_id = :p")
                .param("p", projectId).query(Long.class).single()).isZero();
    }

    @Test
    @DisplayName("a batch exactly at the limit is accepted")
    void batchAtTheLimitIsAccepted() {
        List<ChangeRequest> changes = new ArrayList<>();
        for (int i = 0; i < limits.maxPushBatchSize(); i++) {
            changes.add(binderCreate(UUID.randomUUID(), "Scene " + i, i));
        }

        PushResponse response = sync.push(projectId, deviceA, changes);

        assertThat(response.conflicts()).isEmpty();
        assertThat(response.applied()).hasSize(limits.maxPushBatchSize());
    }
}
