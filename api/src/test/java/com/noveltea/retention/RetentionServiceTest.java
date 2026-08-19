package com.noveltea.retention;

import static org.assertj.core.api.Assertions.assertThat;

import com.noveltea.binder.BinderService;
import com.noveltea.compile.CompileProperties;
import com.noveltea.support.AbstractPostgresTest;
import com.noveltea.sync.SyncService;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * Retention has one dangerous failure mode: purging a delete row an offline client has not
 * read. That client keeps a document its author threw away, and no later sync corrects it,
 * because the only record that it was deleted is the row just removed. Most of these tests
 * exist to pin the conditions under which a row is NOT safe to remove.
 */
class RetentionServiceTest extends AbstractPostgresTest {

    @Autowired RetentionService retention;
    @Autowired SyncService sync;
    @Autowired BinderService binder;
    @Autowired RetentionProperties properties;
    @Autowired CompileProperties compileProperties;

    private long feedRows() {
        return jdbc.sql("SELECT count(*) FROM change_log WHERE project_id = :p")
                .param("p", projectId).query(Long.class).single();
    }

    /** Backdates every feed row so it falls outside the retention window. */
    private void ageFeed() {
        jdbc.sql("UPDATE change_log SET created_at = now() - interval '365 days' WHERE project_id = :p")
                .param("p", projectId).update();
    }

    private void deviceHasRead(UUID deviceId, long upTo) {
        jdbc.sql("UPDATE device SET last_seen_change_id = :n WHERE id = :id")
                .param("n", upTo).param("id", deviceId).update();
    }

    private long maxFeedId() {
        return jdbc.sql("SELECT coalesce(max(id), 0) FROM change_log WHERE project_id = :p")
                .param("p", projectId).query(Long.class).single();
    }

    // ------------------------------------------------- when purging is unsafe

    @Test
    @DisplayName("a device that has read nothing blocks purging entirely")
    void unreadDeviceBlocksPurge() {
        binder.create(projectId, deviceA, null, "document", "Chapter One", null);
        ageFeed();
        // deviceA and deviceB both exist with a null cursor: they have seen nothing.

        assertThat(retention.purgeChangeLog(projectId))
                .as("a client that has never synced must still be able to learn everything")
                .isZero();
        assertThat(feedRows()).isPositive();
    }

    @Test
    @DisplayName("the slowest device decides, not the fastest")
    void slowestDeviceDecides() {
        for (int i = 0; i < 6; i++) {
            binder.create(projectId, deviceA, null, "document", "Chapter " + i, null);
        }
        ageFeed();
        long max = maxFeedId();

        deviceHasRead(deviceA, max);
        deviceHasRead(deviceB, 2);

        retention.purgeChangeLog(projectId);

        assertThat(jdbc.sql("SELECT min(id) FROM change_log WHERE project_id = :p")
                .param("p", projectId).query(Long.class).single())
                .as("rows the slow device has not read must survive")
                .isGreaterThan(2L);
        assertThat(feedRows()).isPositive();
    }

    @Test
    @DisplayName("rows inside the retention window are kept however far ahead the devices are")
    void recentRowsAreKept() {
        binder.create(projectId, deviceA, null, "document", "Chapter One", null);
        long before = feedRows();
        deviceHasRead(deviceA, maxFeedId());
        deviceHasRead(deviceB, maxFeedId());

        assertThat(retention.purgeChangeLog(projectId)).isZero();
        assertThat(feedRows()).isEqualTo(before);
    }

    @Test
    @DisplayName("a revoked device stops holding the feed back")
    void revokedDevicesAreIgnored() {
        binder.create(projectId, deviceA, null, "document", "Chapter One", null);
        ageFeed();
        deviceHasRead(deviceA, maxFeedId());
        jdbc.sql("UPDATE device SET revoked_at = now(), last_seen_change_id = 0 WHERE id = :id")
                .param("id", deviceB).update();

        assertThat(retention.purgeChangeLog(projectId))
                .as("an abandoned device must not preserve history forever")
                .isPositive();
    }

    // -------------------------------------------------- the resync guarantee

    @Test
    @DisplayName("A CLIENT BEHIND THE PURGE POINT IS TOLD TO RESYNC, NEVER GIVEN A PARTIAL FEED")
    void staleClientIsToldToResync() {
        for (int i = 0; i < 5; i++) {
            binder.create(projectId, deviceA, null, "document", "Chapter " + i, null);
        }
        ageFeed();
        long max = maxFeedId();
        deviceHasRead(deviceA, max);
        deviceHasRead(deviceB, max);
        retention.purgeChangeLog(projectId);

        long purgedBelow = jdbc.sql("SELECT change_log_purged_below FROM project WHERE id = :id")
                .param("id", projectId).query(Long.class).single();
        assertThat(purgedBelow).isPositive();

        var response = sync.pull(projectId, 1L, 100);

        assertThat(response.resyncRequired())
                .as("a partial feed would leave the client holding deleted documents forever")
                .isTrue();
        assertThat(response.changes()).isEmpty();
        assertThat(response.latestId())
                .as("resuming below the purge point would put the client back into a resync loop")
                .isGreaterThanOrEqualTo(purgedBelow);

        // The resume point must actually escape the loop.
        assertThat(sync.pull(projectId, response.latestId(), 100).resyncRequired())
                .as("a client that follows the instruction must not be told to resync again")
                .isFalse();
    }

    @Test
    @DisplayName("a fresh client is told to resync too, rather than replaying a truncated history")
    void freshClientAlsoResyncs() {
        binder.create(projectId, deviceA, null, "document", "Chapter One", null);
        ageFeed();
        deviceHasRead(deviceA, maxFeedId());
        deviceHasRead(deviceB, maxFeedId());
        retention.purgeChangeLog(projectId);

        assertThat(sync.pull(projectId, 0L, 100).resyncRequired())
                .as("since=0 cannot rebuild state from a feed missing its creates")
                .isTrue();
    }

    @Test
    @DisplayName("a client ahead of the purge point syncs normally")
    void currentClientIsUnaffected() {
        binder.create(projectId, deviceA, null, "document", "Chapter One", null);
        ageFeed();
        deviceHasRead(deviceA, maxFeedId());
        deviceHasRead(deviceB, maxFeedId());
        retention.purgeChangeLog(projectId);
        long purgedBelow = jdbc.sql("SELECT change_log_purged_below FROM project WHERE id = :id")
                .param("id", projectId).query(Long.class).single();

        binder.create(projectId, deviceA, null, "document", "Chapter Two", null);

        var response = sync.pull(projectId, purgedBelow, 100);
        assertThat(response.resyncRequired()).isFalse();
        assertThat(response.changes()).isNotEmpty();
    }

    @Test
    @DisplayName("pulling records how far a device has read, which is what makes purging safe")
    void pullAdvancesTheDeviceCursor() {
        binder.create(projectId, deviceA, null, "document", "Chapter One", null);

        sync.pull(projectId, deviceA, 0, 100);

        assertThat(jdbc.sql("SELECT last_seen_change_id FROM device WHERE id = :id")
                .param("id", deviceA).query(Long.class).optional())
                .contains(maxFeedId());
    }

    @Test
    @DisplayName("a device cursor only ever moves forward")
    void cursorNeverGoesBackwards() {
        binder.create(projectId, deviceA, null, "document", "Chapter One", null);
        long max = maxFeedId();
        deviceHasRead(deviceA, max);

        sync.pull(projectId, deviceA, 0, 100);

        assertThat(jdbc.sql("SELECT last_seen_change_id FROM device WHERE id = :id")
                .param("id", deviceA).query(Long.class).single()).isEqualTo(max);
    }

    // ------------------------------------------------------------ tombstones

    @Test
    @DisplayName("a tombstone survives while its delete row is still in the feed")
    void tombstoneWaitsForItsDeleteRow() {
        UUID item = binder.create(projectId, deviceA, null, "document", "Doomed", null);
        binder.trash(item, deviceA);
        binder.emptyTrash(projectId, deviceA);
        jdbc.sql("UPDATE binder_item SET deleted_at = now() - interval '365 days' WHERE id = :id")
                .param("id", item).update();

        assertThat(retention.purgeTombstones())
                .as("while the delete row exists a client may still be about to read it")
                .isZero();
        assertThat(jdbc.sql("SELECT count(*) FROM binder_item WHERE id = :id")
                .param("id", item).query(Long.class).single()).isEqualTo(1);
    }

    @Test
    @DisplayName("a tombstone is removed once its delete row has left the feed")
    void tombstoneGoesAfterItsDeleteRow() {
        UUID item = binder.create(projectId, deviceA, null, "document", "Doomed", null);
        binder.trash(item, deviceA);
        binder.emptyTrash(projectId, deviceA);
        jdbc.sql("UPDATE binder_item SET deleted_at = now() - interval '365 days' WHERE id = :id")
                .param("id", item).update();
        jdbc.sql("DELETE FROM change_log WHERE entity_id = :id").param("id", item).update();

        assertThat(retention.purgeTombstones()).isEqualTo(1);
    }

    @Test
    @DisplayName("a live item is never touched by tombstone purging")
    void liveItemsAreSafe() {
        UUID item = binder.create(projectId, deviceA, null, "document", "Alive", null);
        jdbc.sql("DELETE FROM change_log WHERE entity_id = :id").param("id", item).update();

        retention.purgeTombstones();

        assertThat(jdbc.sql("SELECT count(*) FROM binder_item WHERE id = :id")
                .param("id", item).query(Long.class).single()).isEqualTo(1);
    }

    // --------------------------------------------------------------- exports

    @Test
    @DisplayName("an expired staged export is deleted from disk")
    void expiredArtifactsAreDeleted() throws Exception {
        Path staging = Path.of(compileProperties.stagingPath());
        Files.createDirectories(staging);
        Path file = staging.resolve("expired-" + UUID.randomUUID() + ".md");
        Files.writeString(file, "old export");

        UUID jobId = UUID.randomUUID();
        jdbc.sql("""
                INSERT INTO compile_job (id, project_id, inline_config, format, destination,
                                         status, output_path, expires_at, finished_at)
                VALUES (:id, :p, '{}'::jsonb, 'md', 'download', 'done', :path,
                        now() - interval '30 days', now() - interval '30 days')
                """)
                .param("id", jobId).param("p", projectId).param("path", file.toString()).update();

        assertThat(retention.purgeExpiredArtifacts()).isEqualTo(1);
        assertThat(Files.exists(file)).isFalse();
        assertThat(jdbc.sql("SELECT output_path FROM compile_job WHERE id = :id")
                .param("id", jobId).query(String.class).optional()).isEmpty();
    }

    @Test
    @DisplayName("A SERVER EXPORT IS NEVER DELETED — it is the operator's file, not ours")
    void serverExportsAreNeverDeleted() throws Exception {
        Path exports = Path.of(compileProperties.storagePath());
        Files.createDirectories(exports);
        Path file = exports.resolve("kept-" + UUID.randomUUID() + ".md");
        Files.writeString(file, "the author's own copy");

        jdbc.sql("""
                INSERT INTO compile_job (id, project_id, inline_config, format, destination,
                                         status, output_path, expires_at, finished_at)
                VALUES (:id, :p, '{}'::jsonb, 'md', 'server', 'done', :path,
                        now() - interval '30 days', now() - interval '30 days')
                """)
                .param("id", UUID.randomUUID()).param("p", projectId)
                .param("path", file.toString()).update();

        retention.purgeExpiredArtifacts();

        assertThat(Files.exists(file))
                .as("deleting an author's exported manuscript would be unforgivable")
                .isTrue();
    }

    @Test
    @DisplayName("a path outside the staging directory is refused even if a row points there")
    void refusesToDeleteOutsideStaging() {
        jdbc.sql("""
                INSERT INTO compile_job (id, project_id, inline_config, format, destination,
                                         status, output_path, expires_at, finished_at)
                VALUES (:id, :p, '{}'::jsonb, 'md', 'download', 'done', '/etc/hostname',
                        now() - interval '30 days', now() - interval '30 days')
                """)
                .param("id", UUID.randomUUID()).param("p", projectId).update();

        assertThat(retention.purgeExpiredArtifacts()).isZero();
        assertThat(Files.exists(Path.of("/etc/hostname"))).isTrue();
    }

    // ----------------------------------------------------------- credentials

    @Test
    @DisplayName("spent pairing codes and dead invitations are removed")
    void credentialsAreCleanedUp() {
        jdbc.sql("""
                INSERT INTO pairing_code (user_id, code_hash, expires_at, consumed_at,
                                          consumed_by_device_id)
                VALUES (:u, 'hash-old', now() - interval '90 days', now() - interval '90 days', :d)
                """).param("u", userId).param("d", deviceB).update();
        jdbc.sql("""
                INSERT INTO pairing_code (user_id, code_hash, expires_at)
                VALUES (:u, 'hash-fresh', now() + interval '10 minutes')
                """).param("u", userId).update();
        jdbc.sql("""
                INSERT INTO project_invitation (project_id, email, role, token_hash, expires_at)
                VALUES (:p, 'old@example.com', 'viewer', 'tok-old', now() - interval '90 days')
                """).param("p", projectId).update();

        assertThat(retention.purgeSpentPairingCodes()).isEqualTo(1);
        assertThat(retention.purgeDeadInvitations()).isEqualTo(1);
        assertThat(jdbc.sql("SELECT count(*) FROM pairing_code").query(Long.class).single())
                .as("a live code must survive")
                .isEqualTo(1);
    }

    @Test
    @DisplayName("a full sweep runs everything and reports what it did")
    void sweepRunsEverything() {
        binder.create(projectId, deviceA, null, "document", "Chapter One", null);
        var report = retention.sweep();
        assertThat(report).isNotNull();
        assertThat(report.total()).isGreaterThanOrEqualTo(0);
    }
}
