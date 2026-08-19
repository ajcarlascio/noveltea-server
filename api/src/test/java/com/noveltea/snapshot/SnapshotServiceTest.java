package com.noveltea.snapshot;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.noveltea.snapshot.SnapshotExceptions.SnapshotNotFound;
import com.noveltea.snapshot.SnapshotExceptions.StaleDocument;
import com.noveltea.support.AbstractPostgresTest;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

class SnapshotServiceTest extends AbstractPostgresTest {

    @Autowired SnapshotService snapshots;
    @Autowired SnapshotProperties properties;

    private long feedRowsFor(UUID entityId) {
        return jdbc.sql("""
                SELECT count(*) FROM change_log
                 WHERE entity_type = 'snapshot' AND entity_id = :id
                """).param("id", entityId).query(Long.class).single();
    }

    private String contentOf(UUID documentId) {
        return jdbc.sql("SELECT content::text FROM document WHERE id = :id")
                .param("id", documentId).query(String.class).single();
    }

    // ------------------------------------------------------- the whole point

    @Test
    @DisplayName("a MANUAL snapshot syncs: it appears in the change feed")
    void manualSnapshotsSync() {
        UUID docId = seedDocument("Chapter One", "V", "first draft");
        UUID snapshotId = snapshots.capture(docId, "before the edit pass", false, deviceA);

        assertThat(feedRowsFor(snapshotId))
                .as("a deliberate 'keep this version' must survive losing the device")
                .isEqualTo(1);
    }

    @Test
    @DisplayName("an AUTOMATIC snapshot does not sync: no feed row at all")
    void automaticSnapshotsStayLocal() {
        UUID docId = seedDocument("Chapter One", "V", "first draft");
        UUID snapshotId = snapshots.capture(docId, null, true, deviceA);

        assertThat(jdbc.sql("SELECT count(*) FROM snapshot WHERE id = :id")
                .param("id", snapshotId).query(Long.class).single())
                .as("it is still stored")
                .isEqualTo(1);
        assertThat(feedRowsFor(snapshotId))
                .as("syncing every autosave capture would put a manuscript's history on a phone")
                .isZero();
    }

    @Test
    @DisplayName("deleting a manual snapshot syncs; deleting an automatic one does not")
    void deletionFollowsTheSameRule() {
        UUID docId = seedDocument("Chapter One", "V", "draft");
        UUID manual = snapshots.capture(docId, "keep", false, deviceA);
        UUID automatic = snapshots.capture(docId, null, true, deviceA);

        snapshots.delete(manual, deviceA);
        snapshots.delete(automatic, deviceA);

        assertThat(feedRowsFor(manual)).as("create and delete").isEqualTo(2);
        assertThat(feedRowsFor(automatic)).isZero();
    }

    // ------------------------------------------------------------- capturing

    @Test
    @DisplayName("a snapshot captures the content as it stands")
    void capturesCurrentContent() {
        UUID docId = seedDocument("Chapter One", "V", "the original words");
        UUID snapshotId = snapshots.capture(docId, "v1", false, deviceA);

        jdbc.sql("UPDATE document SET content = CAST(:c AS jsonb), version = 2 WHERE id = :id")
                .param("c", doc("completely rewritten")).param("id", docId).update();

        assertThat(snapshots.get(snapshotId).content().toString())
                .as("a snapshot must not follow the document it was taken from")
                .contains("the original words");
    }

    @Test
    @DisplayName("listing carries no content, so a revision list is cheap")
    void listingOmitsContent() {
        UUID docId = seedDocument("Chapter One", "V", "a very long manuscript indeed");
        snapshots.capture(docId, "v1", false, deviceA);

        var summaries = snapshots.list(docId);
        assertThat(summaries).hasSize(1);
        assertThat(summaries.toString())
                .as("shipping content in a list is how an editor becomes unusable")
                .doesNotContain("a very long manuscript indeed");
    }

    @Test
    @DisplayName("snapshots of an unknown document are refused")
    void unknownDocumentRefused() {
        assertThatThrownBy(() -> snapshots.capture(UUID.randomUUID(), "x", false, deviceA))
                .isInstanceOf(SnapshotNotFound.class);
        assertThatThrownBy(() -> snapshots.capture(null, "x", false, deviceA))
                .isInstanceOf(NullPointerException.class).hasMessageContaining("documentId");
    }

    // --------------------------------------------------------------- restore

    @Test
    @DisplayName("restoring puts the old content back and bumps the version")
    void restorePutsContentBack() {
        UUID docId = seedDocument("Chapter One", "V", "the original words");
        UUID snapshotId = snapshots.capture(docId, "v1", false, deviceA);

        jdbc.sql("UPDATE document SET content = CAST(:c AS jsonb), version = 2 WHERE id = :id")
                .param("c", doc("a rewrite I regret")).param("id", docId).update();

        long version = snapshots.restore(snapshotId, 2L, deviceA);

        assertThat(version).isEqualTo(3);
        assertThat(contentOf(docId)).contains("the original words");
    }

    @Test
    @DisplayName("RESTORING IS ITSELF UNDOABLE: the pre-restore state is captured first")
    void restoreIsUndoable() {
        UUID docId = seedDocument("Chapter One", "V", "the original words");
        UUID snapshotId = snapshots.capture(docId, "v1", false, deviceA);
        jdbc.sql("UPDATE document SET content = CAST(:c AS jsonb), version = 2 WHERE id = :id")
                .param("c", doc("the newer version")).param("id", docId).update();

        snapshots.restore(snapshotId, 2L, deviceA);

        assertThat(snapshots.list(docId))
                .as("an author who reverts to the wrong version must not lose the newer one")
                .anySatisfy(s -> {
                    assertThat(s.automatic()).isTrue();
                    assertThat(s.label()).isEqualTo("Before restore");
                });
        boolean newerSurvives = snapshots.list(docId).stream()
                .anyMatch(s -> snapshots.get(s.id()).content().toString().contains("the newer version"));
        assertThat(newerSurvives).isTrue();
    }

    @Test
    @DisplayName("a stale restore is refused and changes nothing")
    void staleRestoreRefused() {
        UUID docId = seedDocument("Chapter One", "V", "the original words");
        UUID snapshotId = snapshots.capture(docId, "v1", false, deviceA);
        jdbc.sql("UPDATE document SET content = CAST(:c AS jsonb), version = 5 WHERE id = :id")
                .param("c", doc("written on another device")).param("id", docId).update();

        assertThatThrownBy(() -> snapshots.restore(snapshotId, 2L, deviceA))
                .isInstanceOf(StaleDocument.class);

        assertThat(contentOf(docId))
                .as("restoring must never overwrite an edit made elsewhere")
                .contains("written on another device");
    }

    @Test
    @DisplayName("restoring tells other devices the document changed")
    void restoreIsVisibleToOtherDevices() {
        UUID docId = seedDocument("Chapter One", "V", "original");
        UUID snapshotId = snapshots.capture(docId, "v1", false, deviceA);
        jdbc.sql("UPDATE document SET version = 2 WHERE id = :id").param("id", docId).update();

        snapshots.restore(snapshotId, 2L, deviceA);

        assertThat(jdbc.sql("""
                SELECT count(*) FROM change_log
                 WHERE entity_type = 'document' AND entity_id = :id AND op = 'update'
                """).param("id", docId).query(Long.class).single()).isGreaterThan(0);
    }

    // ------------------------------------------------------------- retention

    @Test
    @DisplayName("automatic snapshots are pruned to the configured ceiling")
    void automaticSnapshotsArePruned() {
        UUID docId = seedDocument("Chapter One", "V", "draft");
        int keep = properties.keepAutomaticPerDocument();

        for (int i = 0; i < keep + 10; i++) {
            snapshots.capture(docId, null, true, deviceA);
        }

        long automatic = jdbc.sql("""
                SELECT count(*) FROM snapshot WHERE document_id = :id AND is_automatic = true
                """).param("id", docId).query(Long.class).single();
        assertThat(automatic).isEqualTo(keep);
    }

    @Test
    @DisplayName("MANUAL snapshots are never pruned, however many there are")
    void manualSnapshotsSurvivePruning() {
        UUID docId = seedDocument("Chapter One", "V", "draft");
        int keep = properties.keepAutomaticPerDocument();

        for (int i = 0; i < 5; i++) {
            snapshots.capture(docId, "milestone " + i, false, deviceA);
        }
        for (int i = 0; i < keep + 20; i++) {
            snapshots.capture(docId, null, true, deviceA);
        }

        long manual = jdbc.sql("""
                SELECT count(*) FROM snapshot WHERE document_id = :id AND is_automatic = false
                """).param("id", docId).query(Long.class).single();
        assertThat(manual)
                .as("the author asked for these; deleting them is not the server's decision")
                .isEqualTo(5);
    }

    @Test
    @DisplayName("pruning keeps the most recent automatic captures, not the oldest")
    void pruningKeepsTheNewest() {
        UUID docId = seedDocument("Chapter One", "V", "draft");
        for (int i = 0; i < properties.keepAutomaticPerDocument() + 5; i++) {
            jdbc.sql("UPDATE document SET content = CAST(:c AS jsonb) WHERE id = :id")
                    .param("c", doc("revision " + i)).param("id", docId).update();
            snapshots.capture(docId, null, true, deviceA);
        }

        var newest = snapshots.list(docId).get(0);
        assertThat(snapshots.get(newest.id()).content().toString()).contains("revision");
        assertThat(snapshots.list(docId).size())
                .isEqualTo(properties.keepAutomaticPerDocument());
    }
}
