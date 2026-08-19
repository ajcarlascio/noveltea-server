package com.noveltea.ratelimit;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** The limiter itself, without Spring. */
class RateLimitTest {

    private final SlidingWindowLimiter limiter = new SlidingWindowLimiter();

    @Test
    @DisplayName("allows up to the limit, then refuses")
    void allowsThenRefuses() {
        Duration window = Duration.ofMinutes(5);
        for (int i = 0; i < 10; i++) {
            assertThat(limiter.tryAcquire("k", 10, window)).as("attempt %s", i + 1).isTrue();
        }
        assertThat(limiter.tryAcquire("k", 10, window)).isFalse();
    }

    @Test
    @DisplayName("keys are independent, so one caller cannot lock out another")
    void keysAreIndependent() {
        Duration window = Duration.ofMinutes(5);
        for (int i = 0; i < 10; i++) {
            limiter.tryAcquire("noisy", 10, window);
        }
        assertThat(limiter.tryAcquire("noisy", 10, window)).isFalse();
        assertThat(limiter.tryAcquire("quiet", 10, window))
                .as("a shared limit would let one account deny service to everyone")
                .isTrue();
    }

    @Test
    @DisplayName("the window slides: old attempts stop counting")
    void windowSlides() throws Exception {
        Duration window = Duration.ofMillis(300);
        for (int i = 0; i < 3; i++) {
            assertThat(limiter.tryAcquire("k", 3, window)).isTrue();
        }
        assertThat(limiter.tryAcquire("k", 3, window)).isFalse();

        Thread.sleep(350);
        assertThat(limiter.tryAcquire("k", 3, window))
                .as("someone who mistyped a password must not be locked out permanently")
                .isTrue();
    }

    @Test
    @DisplayName("a success clears the counter")
    void resetClears() {
        Duration window = Duration.ofMinutes(5);
        for (int i = 0; i < 10; i++) limiter.tryAcquire("k", 10, window);
        limiter.reset("k");
        assertThat(limiter.tryAcquire("k", 10, window)).isTrue();
    }

    @Test
    @DisplayName("concurrent callers never exceed the limit")
    void concurrentCallersRespectTheLimit() throws Exception {
        Duration window = Duration.ofMinutes(5);
        int limit = 20;
        AtomicInteger granted = new AtomicInteger();
        ExecutorService pool = Executors.newFixedThreadPool(8);
        CountDownLatch start = new CountDownLatch(1);

        for (int i = 0; i < 200; i++) {
            pool.submit(() -> {
                try {
                    start.await();
                    if (limiter.tryAcquire("shared", limit, window)) granted.incrementAndGet();
                } catch (InterruptedException ignored) {
                    Thread.currentThread().interrupt();
                }
            });
        }
        start.countDown();
        pool.shutdown();
        assertThat(pool.awaitTermination(20, TimeUnit.SECONDS)).isTrue();

        assertThat(granted.get())
                .as("a racy limiter would let far more through than the limit")
                .isEqualTo(limit);
    }

    @Test
    @DisplayName("idle keys are evicted, so the map is not a slow leak")
    void idleKeysAreEvicted() throws Exception {
        Duration window = Duration.ofMillis(100);
        for (int i = 0; i < 50; i++) limiter.tryAcquire("k" + i, 5, window);
        assertThat(limiter.trackedKeys()).isEqualTo(50);

        Thread.sleep(250);
        limiter.evictIdle(window);
        assertThat(limiter.trackedKeys()).isZero();
    }
}
