package com.chegg.ratelimiter.service;

import com.chegg.ratelimiter.dto.RateLimitResult;
import com.chegg.ratelimiter.dto.RateLimitStatus;

/**
 * Core rate-limiter abstraction (SOLID: Interface Segregation + Dependency Inversion).
 *
 * <p>Implementations decide timestamps, storage, and eviction. Callers depend only on
 * this interface — they never reference a concrete algorithm class directly.</p>
 */
public interface RateLimiter {

    /**
     * Check whether the request is within the rate limit and, if allowed, record it.
     *
     * @param clientKey unique identifier for the caller (e.g. X-API-Key value)
     * @param endpoint  the request URI (e.g. "/api/general")
     * @return result indicating allowed/blocked status and current window metadata
     */
    RateLimitResult checkAndRecord(String clientKey, String endpoint);

    /**
     * Return the current rate-limit state for the caller without recording a new request.
     *
     * @param clientKey unique identifier for the caller
     * @param endpoint  the request URI
     * @return current limit, remaining, and resetAt values
     */
    RateLimitStatus getStatus(String clientKey, String endpoint);
}
