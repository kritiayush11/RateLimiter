package com.chegg.ratelimiter.interceptor;

import com.chegg.ratelimiter.dto.ErrorResponse;
import com.chegg.ratelimiter.dto.RateLimitResult;
import com.chegg.ratelimiter.service.RateLimiter;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

/**
 * Per-request rate-limit enforcement (SOLID: Single Responsibility).
 *
 * <p>Responsibilities:
 * <ul>
 *   <li>Validate that {@code X-API-Key} header is present; return 400 otherwise.</li>
 *   <li>Delegate allow/deny decision to {@link RateLimiter} (Dependency Inversion).</li>
 *   <li>Write {@code X-RateLimit-*} headers on every response.</li>
 *   <li>Write 429 JSON body + {@code Retry-After} header when blocked.</li>
 * </ul>
 */
@Component
public class RateLimitInterceptor implements HandlerInterceptor {

    public static final String API_KEY_HEADER = "X-API-Key";
    public static final String HEADER_LIMIT = "X-RateLimit-Limit";
    public static final String HEADER_REMAINING = "X-RateLimit-Remaining";
    public static final String HEADER_RESET = "X-RateLimit-Reset";

    private final RateLimiter rateLimiter;
    private final ObjectMapper objectMapper;

    public RateLimitInterceptor(RateLimiter rateLimiter, ObjectMapper objectMapper) {
        this.rateLimiter = rateLimiter;
        this.objectMapper = objectMapper;
    }

    @Override
    public boolean preHandle(HttpServletRequest request,
                             HttpServletResponse response,
                             Object handler) throws Exception {

        String apiKey = request.getHeader(API_KEY_HEADER);

        // 400 — missing API key
        if (apiKey == null || apiKey.isBlank()) {
            response.setStatus(HttpServletResponse.SC_BAD_REQUEST);
            response.setContentType(MediaType.APPLICATION_JSON_VALUE);
            response.getWriter().write("{\"error\":\"Missing X-API-Key header\"}");
            return false;
        }

        String endpoint = request.getRequestURI();
        RateLimitResult result = rateLimiter.checkAndRecord(apiKey, endpoint);

        // Always set rate-limit headers
        response.setHeader(HEADER_LIMIT, String.valueOf(result.limit()));
        response.setHeader(HEADER_REMAINING, String.valueOf(result.remaining()));
        response.setHeader(HEADER_RESET, String.valueOf(result.resetAt()));

        if (!result.allowed()) {
            // 429 — rate limit exceeded
            response.setStatus(429);
            response.setContentType(MediaType.APPLICATION_JSON_VALUE);
            response.setHeader(HttpHeaders.RETRY_AFTER, String.valueOf(result.retryAfterSeconds()));

            ErrorResponse body = new ErrorResponse("Too many requests", result.retryAfterSeconds());
            response.getWriter().write(objectMapper.writeValueAsString(body));
            return false;
        }

        return true;
    }
}
