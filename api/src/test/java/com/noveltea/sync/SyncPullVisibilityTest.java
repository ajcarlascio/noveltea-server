package com.noveltea.sync;

import static org.assertj.core.api.Assertions.assertThat;

import com.noveltea.support.AbstractPostgresTest;
import com.noveltea.sync.dto.SyncDtos.ChangeRecord;
import com.noveltea.sync.dto.SyncDtos.PullResponse;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * The reason this project cannot test sync against H2.
 *
 * <p>change_log ids come from a sequence: assigned at INSERT, visible at COMMIT. Two
 * concurrent writers can therefore commit out of id order. If the feed serves a higher
 * id while a lower one is still in flight, the client advances its cursor past a row it
 * will never be offered again — a permanently lost edit, invisible until the author
 * notices missing prose weeks later.
 */
class SyncPullVisibilityTest extends AbstractPostgresTest {

    @Autowired SyncService sync;

    @Test
    @DisplayName("a row from an uncommitted transaction is withheld, even when a later id has committed")
    void doesNotServePastAnInFlightWrite() throws Exception {
        UUID docId = seedDocument("Chapter One", "V", "start");

        // Transaction A claims an id but does NOT commit.
        try (Connection slow = dataSource.getConnection()) {
            slow.setAutoCommit(false);
            insertChange(slow, docId, "update");

            // Transaction B claims a HIGHER id and commits immediately.
            try (Connection fast = dataSource.getConnection()) {
                fast.setAutoCommit(false);
                insertChange(fast, docId, "update");
                fast.commit();
            }

            PullResponse whileInFlight = sync.pull(projectId, 0, 100);

            assertThat(whileInFlight.changes())
                    .as("no row may be served while an earlier id is still uncommitted")
                    .isEmpty();
            assertThat(whileInFlight.latestId())
                    .as("cursor must not advance past a withheld row")
                    .isZero();

            slow.commit();
        }

        PullResponse afterCommit = sync.pull(projectId, 0, 100);
        assertThat(afterCommit.changes())
                .as("both rows become visible once the slow transaction commits")
                .hasSize(2);

        List<Long> ids = afterCommit.changes().stream().map(ChangeRecord::id).toList();
        assertThat(ids).isSorted();
    }

    @Test
    @DisplayName("latestId is the highest id actually served, never the table maximum")
    void latestIdReflectsWhatWasServed() {
        UUID docId = seedDocument("Chapter One", "V", "start");
        for (int i = 0; i < 5; i++) {
            jdbc.sql("""
                    INSERT INTO change_log (project_id, entity_type, entity_id, op, device_id)
                    VALUES (:p, 'document', :e, 'update', :d)
                    """)
                    .param("p", projectId).param("e", docId).param("d", deviceA).update();
        }

        PullResponse page = sync.pull(projectId, 0, 2);

        assertThat(page.changes()).hasSize(2);
        assertThat(page.hasMore()).isTrue();
        assertThat(page.latestId())
                .as("a client resuming from latestId must not skip the unserved rows")
                .isEqualTo(page.changes().get(1).id());

        PullResponse next = sync.pull(projectId, page.latestId(), 100);
        assertThat(next.changes()).hasSize(3);
    }

    @Test
    @DisplayName("paging through the feed yields every row exactly once")
    void pagingLosesNothing() {
        UUID docId = seedDocument("Chapter One", "V", "start");
        for (int i = 0; i < 17; i++) {
            jdbc.sql("""
                    INSERT INTO change_log (project_id, entity_type, entity_id, op, device_id)
                    VALUES (:p, 'document', :e, 'update', :d)
                    """)
                    .param("p", projectId).param("e", docId).param("d", deviceA).update();
        }

        List<Long> seen = new java.util.ArrayList<>();
        long cursor = 0;
        for (int guard = 0; guard < 50; guard++) {
            PullResponse page = sync.pull(projectId, cursor, 5);
            page.changes().forEach(c -> seen.add(c.id()));
            if (!page.hasMore()) break;
            cursor = page.latestId();
        }

        assertThat(seen).hasSize(17).doesNotHaveDuplicates().isSorted();
    }

    private void insertChange(Connection connection, UUID entityId, String op) throws Exception {
        try (PreparedStatement ps = connection.prepareStatement("""
                INSERT INTO change_log (project_id, entity_type, entity_id, op, device_id)
                VALUES (?, 'document', ?, ?, ?)
                """)) {
            ps.setObject(1, projectId);
            ps.setObject(2, entityId);
            ps.setString(3, op);
            ps.setObject(4, deviceA);
            ps.executeUpdate();
        }
    }
}
