package com.noveltea.binder;

import static org.assertj.core.api.Assertions.assertThat;

import com.noveltea.support.AbstractPostgresTest;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * Two devices acting on the binder at the same instant.
 *
 * <p>These do not assert which writer wins — that is not knowable. They assert the
 * invariants that must survive either outcome: no duplicate ordering keys among
 * siblings, exactly one parent per item, and no cycle. A failure that surfaces as a
 * clean exception is acceptable; silent structural corruption is not.
 */
class BinderConcurrencyTest extends AbstractPostgresTest {

    @Autowired BinderService binder;

    /** Runs both tasks as close to simultaneously as the JVM allows. */
    private <T> List<Future<T>> race(Callable<T> first, Callable<T> second) throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(2);
        CountDownLatch start = new CountDownLatch(1);
        try {
            Future<T> a = pool.submit(() -> { start.await(); return first.call(); });
            Future<T> b = pool.submit(() -> { start.await(); return second.call(); });
            start.countDown();
            pool.shutdown();
            assertThat(pool.awaitTermination(30, TimeUnit.SECONDS)).isTrue();
            return List.of(a, b);
        } finally {
            pool.shutdownNow();
        }
    }

    private long distinctOrderKeys(UUID parent) {
        return jdbc.sql("""
                SELECT count(DISTINCT order_key) FROM binder_item
                 WHERE project_id = :p AND parent_id IS NOT DISTINCT FROM CAST(:parent AS uuid)
                   AND deleted_at IS NULL
                """).param("p", projectId).param("parent", parent).query(Long.class).single();
    }

    private long childCount(UUID parent) {
        return jdbc.sql("""
                SELECT count(*) FROM binder_item
                 WHERE project_id = :p AND parent_id IS NOT DISTINCT FROM CAST(:parent AS uuid)
                   AND deleted_at IS NULL
                """).param("p", projectId).param("parent", parent).query(Long.class).single();
    }

    @Test
    @DisplayName("simultaneous creates at the same position never yield duplicate ordering keys")
    void concurrentCreatesAtSamePositionKeepOrderingValid() throws Exception {
        UUID anchor = binder.create(projectId, deviceA, null, "folder", "Anchor", null);

        AtomicInteger succeeded = new AtomicInteger();
        var results = race(
                () -> attempt(() -> binder.create(projectId, deviceA, null, "folder", "From A", anchor), succeeded),
                () -> attempt(() -> binder.create(projectId, deviceB, null, "folder", "From B", anchor), succeeded));
        for (Future<Boolean> f : results) {
            f.get();
        }

        assertThat(succeeded.get()).as("at least one writer must make progress").isGreaterThanOrEqualTo(1);
        assertThat(distinctOrderKeys(null))
                .as("every sibling ordering key is unique regardless of who won")
                .isEqualTo(childCount(null));
    }

    @Test
    @DisplayName("simultaneous moves of the same item leave it with exactly one parent")
    void concurrentMovesOfSameItemLeaveAValidTree() throws Exception {
        UUID left = binder.create(projectId, deviceA, null, "folder", "Left", null);
        UUID right = binder.create(projectId, deviceA, null, "folder", "Right", left);
        UUID scene = binder.create(projectId, deviceA, null, "document", "Scene", right);

        AtomicInteger succeeded = new AtomicInteger();
        var results = race(
                () -> attempt(() -> { binder.move(scene, left, null, deviceA); return scene; }, succeeded),
                () -> attempt(() -> { binder.move(scene, right, null, deviceB); return scene; }, succeeded));
        for (Future<Boolean> f : results) {
            f.get();
        }

        UUID parent = jdbc.sql("SELECT parent_id FROM binder_item WHERE id = :id")
                .param("id", scene).query(UUID.class).optional().orElse(null);
        assertThat(parent).as("the item must land under exactly one of the two").isIn(left, right);
        assertThat(distinctOrderKeys(parent)).isEqualTo(childCount(parent));

        long selfReferencing = jdbc.sql("""
                SELECT count(*) FROM binder_item WHERE id = parent_id
                """).query(Long.class).single();
        assertThat(selfReferencing).as("no item may become its own parent").isZero();
    }

    @Test
    @DisplayName("simultaneous trash and restore leave the item in a coherent place")
    void concurrentTrashAndRestoreConverge() throws Exception {
        UUID act = binder.create(projectId, deviceA, null, "folder", "Act I", null);
        UUID scene = binder.create(projectId, deviceA, act, "document", "Scene", null);
        UUID trash = binder.ensureTrash(projectId, deviceA);

        AtomicInteger succeeded = new AtomicInteger();
        var results = race(
                () -> attempt(() -> { binder.trash(scene, deviceA); return scene; }, succeeded),
                () -> attempt(() -> { binder.restore(scene, deviceB); return scene; }, succeeded));
        for (Future<Boolean> f : results) {
            f.get();
        }

        UUID parent = jdbc.sql("SELECT parent_id FROM binder_item WHERE id = :id")
                .param("id", scene).query(UUID.class).optional().orElse(null);
        assertThat(parent)
                .as("either still under its folder, or in the trash — never orphaned elsewhere")
                .isIn(act, trash, null);
        assertThat(distinctOrderKeys(parent)).isEqualTo(childCount(parent));
    }

    /** Runs an operation, counting success; contention surfacing as an exception is allowed. */
    private <T> Boolean attempt(Callable<T> op, AtomicInteger successes) {
        try {
            op.call();
            successes.incrementAndGet();
            return true;
        } catch (Exception e) {
            return false;
        }
    }
}
