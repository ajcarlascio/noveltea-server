package com.noveltea.binder;

import static org.assertj.core.api.Assertions.assertThat;

import com.noveltea.support.AbstractPostgresTest;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.RepeatedTest;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * Two devices moving A under B and B under A at the same instant.
 *
 * <p>The cycle check reads the tree and then writes, so under concurrency both moves can
 * pass against a pre-move snapshot and both commit — leaving a subtree that points at
 * itself. It exists in the database and appears nowhere in the binder, because every read
 * walks down from the roots. That is the failure the guard exists to prevent, and the
 * single-threaded tests cannot see it.
 *
 * <p>Repeated because a race that only sometimes loses is still a race.
 */
class BinderMoveCycleRaceTest extends AbstractPostgresTest {

    @Autowired BinderService binder;

    /** Items reachable by walking down from the roots — what any client can actually see. */
    private long reachableCount() {
        return jdbc.sql("""
                WITH RECURSIVE reachable AS (
                    SELECT id FROM binder_item
                     WHERE project_id = :p AND parent_id IS NULL AND deleted_at IS NULL
                    UNION
                    SELECT b.id FROM binder_item b
                      JOIN reachable r ON b.parent_id = r.id
                     WHERE b.deleted_at IS NULL
                )
                SELECT count(*) FROM reachable
                """).param("p", projectId).query(Long.class).single();
    }

    private long liveCount() {
        return jdbc.sql("""
                SELECT count(*) FROM binder_item WHERE project_id = :p AND deleted_at IS NULL
                """).param("p", projectId).query(Long.class).single();
    }

    @RepeatedTest(6)
    @DisplayName("simultaneous opposing moves never detach a subtree from the binder")
    void opposingMovesCannotCreateACycle() throws Exception {
        jdbc.sql("DELETE FROM binder_item WHERE project_id = :p").param("p", projectId).update();
        UUID a = binder.create(projectId, deviceA, null, "folder", "A", null);
        UUID b = binder.create(projectId, deviceA, null, "folder", "B", null);

        ExecutorService pool = Executors.newFixedThreadPool(2);
        CountDownLatch start = new CountDownLatch(1);
        try {
            Callable<Boolean> moveAUnderB = () -> attempt(start, () -> binder.move(a, b, null, deviceA));
            Callable<Boolean> moveBUnderA = () -> attempt(start, () -> binder.move(b, a, null, deviceB));

            var futures = List.of(pool.submit(moveAUnderB), pool.submit(moveBUnderA));
            start.countDown();
            pool.shutdown();
            assertThat(pool.awaitTermination(30, TimeUnit.SECONDS)).isTrue();
            for (var future : futures) {
                future.get();
            }
        } finally {
            pool.shutdownNow();
        }

        assertThat(reachableCount())
                .as("a mutual move left a subtree that exists but no client can reach")
                .isEqualTo(liveCount());
    }

    private Boolean attempt(CountDownLatch start, Runnable action) {
        try {
            start.await();
            action.run();
            return true;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        } catch (RuntimeException e) {
            // Losing the race is a correct outcome; committing a cycle is not.
            return false;
        }
    }
}
