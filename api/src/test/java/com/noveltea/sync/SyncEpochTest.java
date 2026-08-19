package com.noveltea.sync;

import static org.assertj.core.api.Assertions.assertThat;

import com.noveltea.binder.BinderService;
import com.noveltea.support.AbstractPostgresTest;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * The epoch exists for one scenario: the server was restored from a backup.
 *
 * <p>A restore rewinds change_log while devices keep cursors past the restored maximum.
 * They pull, receive nothing, and conclude they are current — while the server has rolled
 * back underneath them. The purge check cannot catch it, because it only detects a cursor
 * that is too low; this detects a server that moved backwards.
 */
class SyncEpochTest extends AbstractPostgresTest {

    @Autowired SyncService sync;
    @Autowired BinderService binder;

    private long epoch() {
        return jdbc.sql("SELECT sync_epoch FROM project WHERE id = :id")
                .param("id", projectId).query(Long.class).single();
    }

    private void bumpEpoch() {
        jdbc.sql("UPDATE project SET sync_epoch = sync_epoch + 1 WHERE id = :id")
                .param("id", projectId).update();
    }

    @Test
    @DisplayName("the epoch is returned so a client can store it")
    void epochIsReturned() {
        binder.create(projectId, deviceA, null, "document", "Chapter One", null);
        var response = sync.pull(projectId, deviceA, 0, 100, null);

        assertThat(response.syncEpoch()).isEqualTo(1);
        assertThat(response.resyncRequired()).isFalse();
    }

    @Test
    @DisplayName("a matching epoch syncs normally")
    void matchingEpochSyncsNormally() {
        binder.create(projectId, deviceA, null, "document", "Chapter One", null);
        var response = sync.pull(projectId, deviceA, 0, 100, 1L);

        assertThat(response.resyncRequired()).isFalse();
        assertThat(response.changes()).isNotEmpty();
    }

    @Test
    @DisplayName("BUMPING THE EPOCH FORCES EVERY CLIENT TO REBUILD")
    void bumpForcesResync() {
        binder.create(projectId, deviceA, null, "document", "Chapter One", null);
        long cursor = sync.pull(projectId, deviceA, 0, 100, 1L).latestId();

        // What an operator runs after restoring a backup.
        bumpEpoch();

        var response = sync.pull(projectId, deviceA, cursor, 100, 1L);
        assertThat(response.resyncRequired())
                .as("a client ahead of a restored server must not be told it is current")
                .isTrue();
        assertThat(response.changes()).isEmpty();
        assertThat(response.syncEpoch()).isEqualTo(2);
    }

    @Test
    @DisplayName("a client that adopts the new epoch syncs again")
    void adoptingTheNewEpochRecovers() {
        binder.create(projectId, deviceA, null, "document", "Chapter One", null);
        bumpEpoch();

        long newEpoch = sync.pull(projectId, deviceA, 0, 100, 1L).syncEpoch();
        var recovered = sync.pull(projectId, deviceA, 0, 100, newEpoch);

        assertThat(recovered.resyncRequired())
                .as("the instruction must be followable, or the client loops forever")
                .isFalse();
        assertThat(recovered.changes()).isNotEmpty();
    }

    @Test
    @DisplayName("a client that has never synced is not forced to resync")
    void firstSyncIsUnaffected() {
        binder.create(projectId, deviceA, null, "document", "Chapter One", null);
        bumpEpoch();

        var response = sync.pull(projectId, deviceA, 0, 100, null);
        assertThat(response.resyncRequired())
                .as("since=0 with no epoch is already a full rebuild")
                .isFalse();
        assertThat(response.changes()).isNotEmpty();
    }

    @Test
    @DisplayName("a stale cursor is reported even when the epoch matches")
    void purgeCheckStillApplies() {
        binder.create(projectId, deviceA, null, "document", "Chapter One", null);
        jdbc.sql("UPDATE project SET change_log_purged_below = 999999 WHERE id = :id")
                .param("id", projectId).update();

        var response = sync.pull(projectId, deviceA, 1L, 100, 1L);
        assertThat(response.resyncRequired()).isTrue();
        assertThat(response.syncEpoch()).isEqualTo(1);
    }

    @Test
    @DisplayName("the epoch is per project, so one restore does not disturb another")
    void epochIsPerProject() {
        bumpEpoch();
        assertThat(epoch()).isEqualTo(2);

        java.util.UUID other = java.util.UUID.randomUUID();
        jdbc.sql("INSERT INTO project (id, owner_id, title) VALUES (:id, :o, 'Other')")
                .param("id", other).param("o", userId).update();

        assertThat(jdbc.sql("SELECT sync_epoch FROM project WHERE id = :id")
                .param("id", other).query(Long.class).single()).isEqualTo(1);
    }
}
