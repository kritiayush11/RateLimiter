package com.chegg.ratelimiter.service;

import com.chegg.ratelimiter.config.RateLimitConfig;
import com.chegg.ratelimiter.dto.RateLimitEndpointConfig;
import com.chegg.ratelimiter.dto.RateLimitResult;
import com.chegg.ratelimiter.dto.RateLimitStatus;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests T1–T8 for SlidingWindowRateLimiterService.
 *
 * Uses a mutable {@link MutableClock} so time can be advanced without Thread.sleep.
 */
class RateLimiterServiceTest {

    private static final String CLIENT = "alice";
    private static final String ENDPOINT = "/api/test";
    private static final int LIMIT = 5;
    private static final int WINDOW_SECS = 60;

    private MutableClock clock;
    private SlidingWindowRateLimiterService service;

    @BeforeEach
    void setUp() {
        clock = new MutableClock(Instant.ofEpochSecond(1_000_000));
        RateLimitConfig config = buildConfig(ENDPOINT, LIMIT, WINDOW_SECS);
        service = new SlidingWindowRateLimiterService(config, clock);
    }

    // -------------------------------------------------------------------------
    // T1 — Normal request under limit
    // -------------------------------------------------------------------------
    @Test
    void requestWithinLimit_shouldPass() {
        RateLimitResult result = service.checkAndRecord(CLIENT, ENDPOINT);

        assertThat(result.allowed()).isTrue();
        assertThat(result.remaining()).isEqualTo(LIMIT - 1);
        assertThat(result.limit()).isEqualTo(LIMIT);
    }

    // -------------------------------------------------------------------------
    // T2 — Exact Nth (limit-th) request should still pass
    // -------------------------------------------------------------------------
    @Test
    void exactNthRequest_shouldPass() {
        // Fire LIMIT - 1 requests first
        for (int i = 0; i < LIMIT - 1; i++) {
            service.checkAndRecord(CLIENT, ENDPOINT);
        }

        // The LIMIT-th request must be allowed
        RateLimitResult result = service.checkAndRecord(CLIENT, ENDPOINT);
        assertThat(result.allowed()).isTrue();
        assertThat(result.remaining()).isEqualTo(0);
    }

    // -------------------------------------------------------------------------
    // T3 — (N+1)-th request should be blocked
    // -------------------------------------------------------------------------
    @Test
    void nPlusOneRequest_shouldBeBlocked() {
        for (int i = 0; i < LIMIT; i++) {
            service.checkAndRecord(CLIENT, ENDPOINT);
        }

        RateLimitResult result = service.checkAndRecord(CLIENT, ENDPOINT);
        assertThat(result.allowed()).isFalse();
    }

    // -------------------------------------------------------------------------
    // T4 — Different clients have independent counters
    // -------------------------------------------------------------------------
    @Test
    void differentClients_haveIndependentCounters() {
        // Exhaust alice
        for (int i = 0; i < LIMIT; i++) {
            service.checkAndRecord("alice", ENDPOINT);
        }
        RateLimitResult aliceBlocked = service.checkAndRecord("alice", ENDPOINT);
        assertThat(aliceBlocked.allowed()).isFalse();

        // bob should be unaffected
        RateLimitResult bobResult = service.checkAndRecord("bob", ENDPOINT);
        assertThat(bobResult.allowed()).isTrue();
        assertThat(bobResult.remaining()).isEqualTo(LIMIT - 1);
    }

    // -------------------------------------------------------------------------
    // T5 — After window expires, counter resets
    // -------------------------------------------------------------------------
    @Test
    void afterWindowExpires_counterResets() {
        // Exhaust the limit
        for (int i = 0; i < LIMIT; i++) {
            service.checkAndRecord(CLIENT, ENDPOINT);
        }
        assertThat(service.checkAndRecord(CLIENT, ENDPOINT).allowed()).isFalse();

        // Advance clock past the window
        clock.advance(WINDOW_SECS + 1);

        // Request should now be allowed again
        RateLimitResult result = service.checkAndRecord(CLIENT, ENDPOINT);
        assertThat(result.allowed()).isTrue();
    }

    // -------------------------------------------------------------------------
    // T6 — resetAt is a future timestamp
    // -------------------------------------------------------------------------
    @Test
    void resetAt_isFutureTimestamp() {
        RateLimitResult result = service.checkAndRecord(CLIENT, ENDPOINT);

        long nowSec = clock.instant().getEpochSecond();
        assertThat(result.resetAt()).isGreaterThan(nowSec);
    }

    // -------------------------------------------------------------------------
    // T7 — Sliding window evicts old entries correctly
    // -------------------------------------------------------------------------
    @Test
    void slidingWindow_evictsOldEntries() {
        // Use 3 requests at t=0
        for (int i = 0; i < 3; i++) {
            service.checkAndRecord(CLIENT, ENDPOINT);
        }

        // Advance clock so those 3 timestamps are just outside the window
        clock.advance(WINDOW_SECS + 1);  // t = 61s — original 3 are now expired

        // Add 2 more at t=61s — they are within the new window [61s-60s, 61s] = [1s, 61s]
        service.checkAndRecord(CLIENT, ENDPOINT);
        service.checkAndRecord(CLIENT, ENDPOINT);

        // Only 2 timestamps remain in window → remaining = LIMIT - 2 = 3 more allowed
        RateLimitResult result = service.checkAndRecord(CLIENT, ENDPOINT);
        assertThat(result.allowed()).isTrue();
    }

    // -------------------------------------------------------------------------
    // T8 — Concurrent requests are thread-safe
    // -------------------------------------------------------------------------
    @Test
    void concurrentRequests_areThreadSafe() throws InterruptedException {
        int threads = 100;
        CountDownLatch ready = new CountDownLatch(threads);
        CountDownLatch start = new CountDownLatch(1);
        AtomicInteger allowed = new AtomicInteger(0);
        AtomicInteger blocked = new AtomicInteger(0);

        ExecutorService pool = Executors.newFixedThreadPool(threads);
        for (int i = 0; i < threads; i++) {
            pool.submit(() -> {
                ready.countDown();
                try {
                    start.await();
                    RateLimitResult r = service.checkAndRecord("concurrent-user", ENDPOINT);
                    if (r.allowed()) allowed.incrementAndGet();
                    else blocked.incrementAndGet();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            });
        }

        ready.await();   // all threads ready
        start.countDown(); // release simultaneously
        pool.shutdown();
        pool.awaitTermination(10, java.util.concurrent.TimeUnit.SECONDS);

        // Exactly LIMIT threads should have been allowed
        assertThat(allowed.get()).isEqualTo(LIMIT);
        assertThat(blocked.get()).isEqualTo(threads - LIMIT);
    }

    // -------------------------------------------------------------------------
    // Cleanup test (Task 6 unit coverage)
    // -------------------------------------------------------------------------
    @Test
    void cleanupExpiredEntries_removesStaleKeys() {
        service.checkAndRecord(CLIENT, ENDPOINT);
        assertThat(service.storeSize()).isEqualTo(1);

        // Advance clock well past the window
        clock.advance(WINDOW_SECS + 10);
        service.cleanupExpiredEntries();

        assertThat(service.storeSize()).isEqualTo(0);
    }

    // -------------------------------------------------------------------------
    // getStatus test
    // -------------------------------------------------------------------------
    @Test
    void getStatus_reflectsCurrentUsage() {
        service.checkAndRecord(CLIENT, ENDPOINT);
        service.checkAndRecord(CLIENT, ENDPOINT);

        RateLimitStatus status = service.getStatus(CLIENT, ENDPOINT);
        assertThat(status.limit()).isEqualTo(LIMIT);
        assertThat(status.remaining()).isEqualTo(LIMIT - 2);
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    private static RateLimitConfig buildConfig(String path, int limit, int windowSecs) {
        RateLimitEndpointConfig endpointCfg = new RateLimitEndpointConfig();
        endpointCfg.setLimit(limit);
        endpointCfg.setWindowSizeInSeconds(windowSecs);

        RateLimitConfig config = new RateLimitConfig();
        config.getEndpoints().put(path, endpointCfg);
        return config;
    }

    /**
     * A simple mutable clock whose instant can be advanced by whole seconds.
     * Thread-safe via volatile.
     */
    static class MutableClock extends Clock {

        private volatile Instant now;

        MutableClock(Instant start) {
            this.now = start;
        }

        /** Advance the clock by {@code seconds}. */
        void advance(long seconds) {
            now = now.plusSeconds(seconds);
        }

        @Override
        public ZoneOffset getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(java.time.ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return now;
        }
    }
}
