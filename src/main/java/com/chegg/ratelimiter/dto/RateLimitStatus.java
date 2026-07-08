package com.chegg.ratelimiter.dto;

/**
 * Current rate-limit state for a client+endpoint pair, without recording a new request.
 *
 * @param limit     configured max requests per window
 * @param remaining requests remaining in the current window
 * @param resetAt   Unix epoch seconds when the current window resets
 */
public record RateLimitStatus(
        int limit,
        int remaining,
        long resetAt
) {}
