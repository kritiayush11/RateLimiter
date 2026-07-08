package com.chegg.ratelimiter.dto;

/**
 * JSON body returned on 429 Too Many Requests.
 */
public record ErrorResponse(
        String error,
        long retryAfterSeconds
) {}
