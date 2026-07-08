package com.chegg.ratelimiter.dto;

/**
 * JSON body returned by GET /api/status.
 */
public record StatusResponse(
        int limit,
        int remaining,
        long resetAt
) {}
