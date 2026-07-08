package com.chegg.ratelimiter.service;

import com.chegg.ratelimiter.config.RateLimitConfig;
import com.chegg.ratelimiter.dto.RateLimitEndpointConfig;
import com.chegg.ratelimiter.dto.RateLimitResult;
import com.chegg.ratelimiter.dto.RateLimitStatus;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Sliding Window Log rate limiter (SOLID: Single Responsibility, Liskov Substitution).
 *
 * <p>Algorithm:
 * <ol>
 *   <li>Maintain a {@code ConcurrentHashMap} keyed by {@code "clientKey::endpoint"}.
 *       Each value is a thread-safe {@code List<Long>} of request timestamps (epoch ms).</li>
 *   <li>On every {@link #checkAndRecord}: evict timestamps older than
 *       {@code now - windowSizeMs}, count remaining, allow or deny.</li>
 *   <li>A {@code @Scheduled} task runs every 60 s to garbage-collect fully-expired
 *       entries, keeping memory bounded.</li>
 * </ol>
 *
 * <p>The {@link Clock} is injected so tests can control time without {@code Thread.sleep}.
 */
@Service
public class SlidingWindowRateLimiterService implements RateLimiter {

    /** Storage: "clientKey::endpoint" → sorted list of request timestamps (epoch ms). */
    private final ConcurrentHashMap<String, List<Long>> store = new ConcurrentHashMap<>();

    private final RateLimitConfig rateLimitConfig;
    private final Clock clock;

    public SlidingWindowRateLimiterService(RateLimitConfig rateLimitConfig, Clock clock) {
        this.rateLimitConfig = rateLimitConfig;
        this.clock = clock;
    }

    // -------------------------------------------------------------------------
    // RateLimiter interface
    // -------------------------------------------------------------------------

    @Override
    public RateLimitResult checkAndRecord(String clientKey, String endpoint) {
        RateLimitEndpointConfig cfg = rateLimitConfig.getConfigForEndpoint(endpoint);
        int limit = cfg.getLimit();
        long windowMs = (long) cfg.getWindowSizeInSeconds() * 1_000;

        String key = storeKey(clientKey, endpoint);
        long nowMs = clock.millis();

        // Get-or-create the timestamp list for this key
        List<Long> timestamps = store.computeIfAbsent(key,
                k -> Collections.synchronizedList(new ArrayList<>()));

        synchronized (timestamps) {
            // 1. Evict timestamps outside the current window
            evictExpired(timestamps, nowMs, windowMs);

            int currentCount = timestamps.size();

            if (currentCount < limit) {
                // 2a. Within limit — record this request
                timestamps.add(nowMs);
                int remaining = limit - timestamps.size();
                long resetAt = computeResetAt(timestamps, cfg.getWindowSizeInSeconds(), nowMs);
                return new RateLimitResult(true, limit, remaining, resetAt, 0L);
            } else {
                // 2b. Over limit — reject without recording
                long resetAt = computeResetAt(timestamps, cfg.getWindowSizeInSeconds(), nowMs);
                long retryAfter = Math.max(1L, resetAt - nowMs / 1_000);
                return new RateLimitResult(false, limit, 0, resetAt, retryAfter);
            }
        }
    }

    @Override
    public RateLimitStatus getStatus(String clientKey, String endpoint) {
        RateLimitEndpointConfig cfg = rateLimitConfig.getConfigForEndpoint(endpoint);
        int limit = cfg.getLimit();
        long windowMs = (long) cfg.getWindowSizeInSeconds() * 1_000;

        String key = storeKey(clientKey, endpoint);
        long nowMs = clock.millis();

        List<Long> timestamps = store.computeIfAbsent(key,
                k -> Collections.synchronizedList(new ArrayList<>()));

        synchronized (timestamps) {
            evictExpired(timestamps, nowMs, windowMs);
            int used = timestamps.size();
            int remaining = Math.max(0, limit - used);
            long resetAt = computeResetAt(timestamps, cfg.getWindowSizeInSeconds(), nowMs);
            return new RateLimitStatus(limit, remaining, resetAt);
        }
    }

    // -------------------------------------------------------------------------
    // Scheduled memory cleanup (Task 6)
    // -------------------------------------------------------------------------

    /**
     * Every 60 seconds: remove timestamps older than their window from every entry,
     * then delete the entry entirely if it becomes empty.
     *
     * <p>Uses the injected {@link Clock} so the same logic is exercised in tests.</p>
     */
    @Scheduled(fixedDelay = 60_000)
    public void cleanupExpiredEntries() {
        long nowMs = clock.millis();

        Iterator<Map.Entry<String, List<Long>>> it = store.entrySet().iterator();
        while (it.hasNext()) {
            Map.Entry<String, List<Long>> entry = it.next();
            String key = entry.getKey();
            List<Long> timestamps = entry.getValue();

            // Derive endpoint from key to look up its window size
            String endpoint = endpointFromKey(key);
            RateLimitEndpointConfig cfg = rateLimitConfig.getConfigForEndpoint(endpoint);
            long windowMs = (long) cfg.getWindowSizeInSeconds() * 1_000;

            synchronized (timestamps) {
                evictExpired(timestamps, nowMs, windowMs);
                if (timestamps.isEmpty()) {
                    it.remove();
                }
            }
        }
    }

    // -------------------------------------------------------------------------
    // Package-private helpers (accessible in tests without reflection)
    // -------------------------------------------------------------------------

    /** Number of active entries in the store — useful for cleanup tests. */
    int storeSize() {
        return store.size();
    }

    // -------------------------------------------------------------------------
    // Private helpers
    // -------------------------------------------------------------------------

    private static String storeKey(String clientKey, String endpoint) {
        return clientKey + "::" + endpoint;
    }

    /** Extracts the endpoint portion from a store key ("clientKey::endpoint"). */
    private static String endpointFromKey(String key) {
        int sep = key.indexOf("::");
        return sep >= 0 ? key.substring(sep + 2) : key;
    }

    /**
     * Remove all timestamps that fall outside {@code [nowMs - windowMs, nowMs]}.
     * Must be called while holding the list's monitor.
     */
    private static void evictExpired(List<Long> timestamps, long nowMs, long windowMs) {
        long cutoff = nowMs - windowMs;
        timestamps.removeIf(ts -> ts <= cutoff);
    }

    /**
     * Compute the Unix epoch second at which the oldest timestamp in the window will expire.
     * If the window is empty (first ever request), returns {@code now + windowSizeInSeconds}.
     */
    private static long computeResetAt(List<Long> timestamps, int windowSizeInSeconds, long nowMs) {
        if (timestamps.isEmpty()) {
            return nowMs / 1_000 + windowSizeInSeconds;
        }
        long oldestMs = timestamps.stream().mapToLong(Long::longValue).min().orElse(nowMs);
        return oldestMs / 1_000 + windowSizeInSeconds;
    }
}
