package com.noveltea.retention;

import com.noveltea.compile.CompileProperties;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Deletes what nothing will read again.
 *
 * <p>The hard part is the change feed. A client that has been offline learns an item was
 * deleted <b>only</b> from its delete row; purge that row too early and the client keeps a
 * document its author threw away, indefinitely. So a row is only removed once both are
 * true: it is older than the retention window, and every live device has already read past
 * it. Where a device is far behind, its project records how far the feed was purged, and
 * that device is told to resync rather than handed a feed with holes in it.
 */
@Service
public class RetentionService {

    private static final Logger log = LoggerFactory.getLogger(RetentionService.class);

    private final JdbcClient jdbc;
    private final RetentionProperties properties;
    private final CompileProperties compileProperties;

    public RetentionService(
            JdbcClient jdbc, RetentionProperties properties, CompileProperties compileProperties) {
        this.jdbc = jdbc;
        this.properties = properties;
        this.compileProperties = compileProperties;
    }

    public record SweepReport(
            int changeLogRows,
            int tombstones,
            int compileJobs,
            int artifactFiles,
            int pairingCodes,
            int invitations) {

        public int total() {
            return changeLogRows + tombstones + compileJobs + artifactFiles + pairingCodes + invitations;
        }
    }

    /** Runs every purge. Safe to call repeatedly; each step is idempotent. */
    public SweepReport sweep() {
        int changeLog = 0;
        for (UUID projectId : projectIds()) {
            changeLog += purgeChangeLog(projectId);
        }
        int artifacts = purgeExpiredArtifacts();
        return new SweepReport(
                changeLog,
                purgeTombstones(),
                purgeFinishedCompileJobs(),
                artifacts,
                purgeSpentPairingCodes(),
                purgeDeadInvitations());
    }

    private List<UUID> projectIds() {
        return jdbc.sql("SELECT id FROM project").query(UUID.class).list();
    }

    // ------------------------------------------------------------ change feed

    /**
     * Trims one project's feed.
     *
     * <p>The cut-off is the lower of the retention window and the slowest live device's
     * cursor. A device that has never synced (cursor 0) blocks purging entirely, which is
     * correct: it has seen nothing, so nothing is safe to remove yet.
     */
    @Transactional
    public int purgeChangeLog(UUID projectId) {
        OffsetDateTime olderThan = OffsetDateTime.now().minus(properties.changeLogRetention());

        Long slowestDevice = jdbc.sql("""
                SELECT min(coalesce(d.last_seen_change_id, 0))
                  FROM device d
                  JOIN project p ON p.owner_id = d.user_id
                 WHERE p.id = :projectId AND d.revoked_at IS NULL
                """)
                .param("projectId", projectId)
                .query(Long.class)
                .optional()
                .orElse(null);

        // No live devices at all: the retention window alone decides.
        long deviceCeiling = slowestDevice == null ? Long.MAX_VALUE : slowestDevice;

        List<Long> removable = jdbc.sql("""
                SELECT id FROM change_log
                 WHERE project_id = :projectId
                   AND created_at < :olderThan
                   AND id <= :deviceCeiling
                 ORDER BY id
                 LIMIT :batch
                """)
                .param("projectId", projectId)
                .param("olderThan", olderThan)
                .param("deviceCeiling", deviceCeiling)
                .param("batch", properties.batchSize())
                .query(Long.class)
                .list();

        if (removable.isEmpty()) {
            return 0;
        }
        long highest = removable.get(removable.size() - 1);

        jdbc.sql("DELETE FROM change_log WHERE project_id = :projectId AND id <= :highest")
                .param("projectId", projectId).param("highest", highest).update();

        // Recorded before anyone can ask for a range that no longer exists.
        jdbc.sql("""
                UPDATE project SET change_log_purged_below = greatest(change_log_purged_below, :highest)
                 WHERE id = :projectId
                """)
                .param("highest", highest).param("projectId", projectId).update();

        log.info("purged {} change_log rows for project {} (below id {})",
                removable.size(), projectId, highest);
        return removable.size();
    }

    // ------------------------------------------------------------- tombstones

    /**
     * Removes binder rows whose deletion has long since propagated.
     *
     * <p>Only rows whose own delete row has already left the feed are eligible: while that
     * row still exists, a client may still be about to read it.
     */
    @Transactional
    public int purgeTombstones() {
        OffsetDateTime olderThan = OffsetDateTime.now().minus(properties.tombstoneRetention());
        return jdbc.sql("""
                DELETE FROM binder_item b
                 WHERE b.deleted_at IS NOT NULL
                   AND b.deleted_at < :olderThan
                   AND NOT EXISTS (
                       SELECT 1 FROM change_log c
                        WHERE c.entity_id = b.id AND c.entity_type = 'binder_item')
                """)
                .param("olderThan", olderThan)
                .update();
    }

    // ---------------------------------------------------------------- exports

    /** Deletes staged export files past their expiry, then the rows that pointed at them. */
    public int purgeExpiredArtifacts() {
        OffsetDateTime cutoff = OffsetDateTime.now().minus(properties.artifactGrace());

        List<Map<String, Object>> expired = jdbc.sql("""
                SELECT id, output_path FROM compile_job
                 WHERE expires_at IS NOT NULL AND expires_at < :cutoff AND output_path IS NOT NULL
                 LIMIT :batch
                """)
                .param("cutoff", cutoff).param("batch", properties.batchSize())
                .query().listOfRows();

        int deleted = 0;
        for (Map<String, Object> row : expired) {
            String stored = (String) row.get("output_path");
            Path path = Path.of(stored).toAbsolutePath().normalize();

            // Never delete outside the staging area, whatever the row says. `server`
            // exports are the operator's files and are not ours to remove.
            Path staging = Path.of(compileProperties.stagingPath()).toAbsolutePath().normalize();
            if (!path.startsWith(staging)) {
                log.warn("refusing to delete {} — outside the staging directory", path);
                continue;
            }
            try {
                if (Files.deleteIfExists(path)) {
                    deleted++;
                }
                jdbc.sql("UPDATE compile_job SET output_path = NULL WHERE id = :id")
                        .param("id", row.get("id")).update();
            } catch (Exception e) {
                log.warn("could not delete expired export {}: {}", path, e.getMessage());
            }
        }
        return deleted;
    }

    /** Removes finished job rows whose artifact is already gone. */
    @Transactional
    public int purgeFinishedCompileJobs() {
        OffsetDateTime cutoff = OffsetDateTime.now().minus(properties.expiredCredentialRetention());
        return jdbc.sql("""
                DELETE FROM compile_job
                 WHERE output_path IS NULL
                   AND finished_at IS NOT NULL
                   AND finished_at < :cutoff
                """)
                .param("cutoff", cutoff)
                .update();
    }

    // ------------------------------------------------------------ credentials

    /** A spent or expired pairing code is a credential; keeping it serves nobody. */
    @Transactional
    public int purgeSpentPairingCodes() {
        OffsetDateTime cutoff = OffsetDateTime.now().minus(properties.expiredCredentialRetention());
        return jdbc.sql("""
                DELETE FROM pairing_code
                 WHERE (consumed_at IS NOT NULL AND consumed_at < :cutoff)
                    OR (consumed_at IS NULL AND expires_at < :cutoff)
                """)
                .param("cutoff", cutoff)
                .update();
    }

    @Transactional
    public int purgeDeadInvitations() {
        OffsetDateTime cutoff = OffsetDateTime.now().minus(properties.expiredCredentialRetention());
        return jdbc.sql("""
                DELETE FROM project_invitation
                 WHERE (accepted_at IS NOT NULL AND accepted_at < :cutoff)
                    OR (revoked_at IS NOT NULL AND revoked_at < :cutoff)
                    OR (accepted_at IS NULL AND revoked_at IS NULL AND expires_at < :cutoff)
                """)
                .param("cutoff", cutoff)
                .update();
    }
}
