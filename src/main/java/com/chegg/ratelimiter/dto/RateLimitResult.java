package com.chegg.ratelimiter.dto;

/**
 * Result of a single checkAndRecord call.
 *
 * @param allowed        true if the request is within the rate limit
 * @param limit          configured max requests per window
 * @param remaining      requests remaining after this one (0 when blocked)
 * @param resetAt        Unix epoch seconds when the current window resets
 * @param retryAfterSeconds seconds until the caller may retry (0 when allowed)
 */
public record RateLimitResult(
        boolean allowed,
        int limit,
        int remaining,
        long resetAt,
        long retryAfterSeconds
) {}
