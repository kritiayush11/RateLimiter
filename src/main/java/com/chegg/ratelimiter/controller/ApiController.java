package com.chegg.ratelimiter.controller;

import com.chegg.ratelimiter.dto.RateLimitStatus;
import com.chegg.ratelimiter.dto.StatusResponse;
import com.chegg.ratelimiter.interceptor.RateLimitInterceptor;
import com.chegg.ratelimiter.service.RateLimiter;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * Demo API endpoints gated by the rate limiter (SOLID: Single Responsibility).
 *
 * <p>This controller only handles response construction — all rate-limit logic
 * lives in {@link RateLimitInterceptor} and the service layer.</p>
 */
@RestController
@RequestMapping("/api")
public class ApiController {

    private final RateLimiter rateLimiter;

    public ApiController(RateLimiter rateLimiter) {
        this.rateLimiter = rateLimiter;
    }

    /** GET /api/general — general-purpose endpoint, 20 req / 60 s. */
    @GetMapping("/general")
    public ResponseEntity<Map<String, String>> general() {
        return ResponseEntity.ok(Map.of("message", "OK"));
    }

    /** POST /api/submit — write/submission endpoint, 5 req / 60 s. */
    @PostMapping("/submit")
    public ResponseEntity<Map<String, String>> submit() {
        return ResponseEntity.ok(Map.of("message", "OK"));
    }

    /**
     * GET /api/status — returns the caller's current rate-limit state without
     * recording an additional request against the general counter.
     *
     * <p>Note: the interceptor has already incremented the {@code /api/status}
     * counter before this method is reached.</p>
     */
    @GetMapping("/status")
    public ResponseEntity<StatusResponse> status(HttpServletRequest request) {
        String apiKey = request.getHeader(RateLimitInterceptor.API_KEY_HEADER);
        RateLimitStatus s = rateLimiter.getStatus(apiKey, "/api/status");
        return ResponseEntity.ok(new StatusResponse(s.limit(), s.remaining(), s.resetAt()));
    }
}
