package com.noveltea.document;

import static org.assertj.core.api.Assertions.assertThat;

import com.noveltea.binder.BinderService;
import com.noveltea.document.DocumentService.DocumentBody;
import com.noveltea.document.DocumentService.DocumentPage;
import com.noveltea.support.AbstractPostgresTest;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * The endpoint exists so a client told to resync can recover prose the change feed
 * cannot give it. Every test here is a way that could silently fail to happen — a
 * document skipped by a cursor, a trashed one dropped, another project's leaking in.
 */
class DocumentServiceTest extends AbstractPostgresTest {

    @Autowired DocumentService documents;
    @Autowired BinderService binder;

    private UUID writeDocument(String title, String orderKey, String body) {
        return seedDocument(title, orderKey, body);
    }

    private List<String> titlesOf(DocumentPage page) {
        return page.documents().stream().map(DocumentBody::title).toList();
    }

    /** Walks every page, as a client rebuilding would. */
    private List<DocumentBody> readAll(int limit) {
        List<DocumentBody> all = new ArrayList<>();
        UUID after = null;
        for (int guard = 0; guard < 100; guard++) {
            DocumentPage page = documents.bodies(projectId, after, limit);
            all.addAll(page.documents());
            if (!page.hasMore()) return all;
            after = page.nextCursor();
            assertThat(after).as("a page saying hasMore must say where to continue").isNotNull();
        }
        throw new AssertionError("paging did not terminate");
    }

    // ------------------------------------------------------------------ reading

    @Test
    @DisplayName("returns a document's stored body")
    void returnsBody() {
        writeDocument("Chapter One", "a1", "the light swung");

        DocumentPage page = documents.bodies(projectId, null, null);

        assertThat(page.documents()).hasSize(1);
        DocumentBody body = page.documents().get(0);
        assertThat(body.title()).isEqualTo("Chapter One");
        assertThat(body.content().toString()).contains("the light swung");
        assertThat(page.hasMore()).isFalse();
        assertThat(page.nextCursor()).isNull();
    }

    @Test
    @DisplayName("hands the content back exactly as stored, without interpreting it")
    void contentIsOpaque() {
        UUID id = binder.create(projectId, deviceA, null, "document", "Odd", null);
        String exotic = "{\"type\":\"doc\",\"content\":[{\"type\":\"sceneBreak\",\"attrs\":{\"glyph\":\"* * *\"}}]}";
        jdbc.sql("INSERT INTO document (id, content) VALUES (:id, CAST(:c AS jsonb))")
                .param("id", id).param("c", exotic).update();

        DocumentBody body = documents.bodies(projectId, null, null).documents().get(0);

        // A node type this build has never met must survive untouched, or a newer
        // client's work is flattened by an older server.
        assertThat(body.content().at("/content/0/type").asText()).isEqualTo("sceneBreak");
        assertThat(body.content().at("/content/0/attrs/glyph").asText()).isEqualTo("* * *");
    }

    @Test
    @DisplayName("returns nothing for a project with no documents")
    void emptyProject() {
        DocumentPage page = documents.bodies(projectId, null, null);
        assertThat(page.documents()).isEmpty();
        assertThat(page.hasMore()).isFalse();
    }

    // ------------------------------------------------------------------ what is included

    @Test
    @DisplayName("includes a trashed document, because trashing is a move")
    void includesTrashed() {
        UUID id = writeDocument("Discarded", "a1", "still here");
        binder.trash(id, deviceA);

        // The item is still restorable and still syncs. A rebuild that dropped it would
        // empty the author's trash behind their back.
        assertThat(titlesOf(documents.bodies(projectId, null, null))).containsExactly("Discarded");
    }

    @Test
    @DisplayName("excludes a tombstoned document, because that one is gone")
    void excludesTombstoned() {
        UUID kept = writeDocument("Kept", "a1", "one");
        UUID gone = writeDocument("Gone", "a2", "two");
        jdbc.sql("UPDATE binder_item SET deleted_at = now() WHERE id = :id")
                .param("id", gone).update();

        assertThat(titlesOf(documents.bodies(projectId, null, null))).containsExactly("Kept");
        assertThat(kept).isNotNull();
    }

    @Test
    @DisplayName("excludes a folder, which has no body")
    void excludesFolders() {
        binder.create(projectId, deviceA, null, "folder", "Act I", null);
        writeDocument("Chapter One", "a2", "words");

        assertThat(titlesOf(documents.bodies(projectId, null, null))).containsExactly("Chapter One");
    }

    @Test
    @DisplayName("never returns a document from another project")
    void scopedToProject() {
        writeDocument("Mine", "a1", "mine");

        UUID otherUser = UUID.randomUUID();
        UUID otherProject = UUID.randomUUID();
        jdbc.sql("INSERT INTO app_user (id, email) VALUES (:id, :email)")
                .param("id", otherUser).param("email", "other-" + otherUser + "@example.com").update();
        jdbc.sql("INSERT INTO project (id, owner_id, title) VALUES (:id, :owner, 'Theirs')")
                .param("id", otherProject).param("owner", otherUser).update();
        UUID theirs = binder.create(otherProject, deviceA, null, "document", "Theirs", null);
        jdbc.sql("INSERT INTO document (id, content) VALUES (:id, CAST(:c AS jsonb))")
                .param("id", theirs).param("c", doc("secret")).update();

        // `document` has no project_id of its own; it is scoped through its binder item.
        // Without that join this endpoint would hand out every document on the server.
        assertThat(titlesOf(documents.bodies(projectId, null, null))).containsExactly("Mine");
    }

    // ------------------------------------------------------------------ paging

    @Test
    @DisplayName("walks every document exactly once across pages")
    void pagesWithoutSkippingOrRepeating() {
        for (int i = 0; i < 25; i++) {
            writeDocument("Chapter " + i, String.format("a%02d", i), "body " + i);
        }

        List<DocumentBody> all = readAll(4);

        // The whole point: a rebuild that skips one silently loses a chapter, and one
        // that repeats wastes a connection it may not have much of.
        assertThat(all).hasSize(25);
        assertThat(all.stream().map(DocumentBody::id).distinct()).hasSize(25);
    }

    /**
     * Postgres orders {@code uuid} by its sixteen bytes, unsigned. {@link UUID#compareTo}
     * compares two signed longs, so the two disagree for any uuid with the high bit set —
     * about half of them.
     *
     * <p>That difference does not affect the endpoint: Postgres does both the ordering
     * and the {@code d.id > :after} comparison, so they agree with each other, which is
     * all a cursor needs. It does affect a test that asserts sortedness in Java, which
     * is why this comparator exists rather than {@code isSorted()}.
     */
    private static int comparePostgresUuid(UUID a, UUID b) {
        int high = Long.compareUnsigned(a.getMostSignificantBits(), b.getMostSignificantBits());
        return high != 0 ? high : Long.compareUnsigned(a.getLeastSignificantBits(), b.getLeastSignificantBits());
    }

    @Test
    @DisplayName("orders by id, so a cursor cannot skip a tie")
    void orderedById() {
        for (int i = 0; i < 10; i++) writeDocument("Chapter " + i, String.format("a%02d", i), "x");

        List<UUID> ids = readAll(3).stream().map(DocumentBody::id).toList();

        // updated_at would be the obvious ordering and is not unique; two documents
        // written in the same transaction share one, and a cursor on it skips or repeats.
        assertThat(ids).isSortedAccordingTo(DocumentServiceTest::comparePostgresUuid);
    }

    @Test
    @DisplayName("reports no cursor on the last page")
    void lastPageHasNoCursor() {
        writeDocument("Only", "a1", "x");
        DocumentPage page = documents.bodies(projectId, null, 10);
        assertThat(page.hasMore()).isFalse();
        assertThat(page.nextCursor()).isNull();
    }

    @Test
    @DisplayName("caps an absurd limit rather than serving it")
    void clampsLimit() {
        for (int i = 0; i < 3; i++) writeDocument("Chapter " + i, String.format("a%02d", i), "x");

        // A client asking for a million rows should get a page, not an outage.
        assertThat(documents.bodies(projectId, null, 1_000_000).documents()).hasSize(3);
        assertThat(documents.bodies(projectId, null, 0).documents()).hasSize(3);
        assertThat(documents.bodies(projectId, null, -5).documents()).hasSize(3);
    }

    @Test
    @DisplayName("stops a page on bytes as well as rows")
    void stopsOnBytes() {
        // Each of these is around a megabyte, so the 4MB budget is reached long before
        // the row limit. Without this a page of full documents has no predictable size,
        // which is exactly what breaks a rebuild over mobile data.
        String big = "x".repeat(1_000_000);
        for (int i = 0; i < 8; i++) writeDocument("Chapter " + i, String.format("a%02d", i), big);

        DocumentPage page = documents.bodies(projectId, null, 100);

        assertThat(page.documents().size()).isLessThan(8);
        assertThat(page.hasMore()).isTrue();
        assertThat(page.nextCursor()).isNotNull();
    }

    @Test
    @DisplayName("emits an oversized document alone rather than wedging")
    void oversizedDocumentIsEmittedAlone() {
        // Larger than the whole page budget by itself. Skipping it would make the
        // document permanently unrecoverable; refusing the page would stop the rebuild
        // at that point forever.
        writeDocument("Enormous", "a1", "x".repeat(5_000_000));
        writeDocument("Normal", "a2", "short");

        DocumentPage first = documents.bodies(projectId, null, 100);

        assertThat(titlesOf(first)).containsExactly("Enormous");
        assertThat(first.hasMore()).isTrue();
        assertThat(titlesOf(documents.bodies(projectId, first.nextCursor(), 100)))
                .containsExactly("Normal");
    }

    @Test
    @DisplayName("a whole project can be rebuilt page by page even when every document is large")
    void rebuildTerminates() {
        String big = "x".repeat(2_000_000);
        for (int i = 0; i < 6; i++) writeDocument("Chapter " + i, String.format("a%02d", i), big);

        assertThat(readAll(100)).hasSize(6);
    }
}
