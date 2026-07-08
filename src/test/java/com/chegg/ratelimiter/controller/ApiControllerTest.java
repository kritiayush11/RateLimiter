package com.chegg.ratelimiter.controller;

import com.chegg.ratelimiter.config.RateLimitConfig;
import com.chegg.ratelimiter.dto.RateLimitEndpointConfig;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.HashMap;
import java.util.Map;

import static org.hamcrest.Matchers.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * Integration tests T9–T16 using MockMvc + full Spring context.
 *
 * Each test class run gets a fresh application context via @DirtiesContext.
 * The inner @TestConfiguration replaces the production Clock bean with a
 * controllable MutableClock and uses short windows (1 s) for T16.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@DirtiesContext(classMode = DirtiesContext.ClassMode.BEFORE_EACH_TEST_METHOD)
class ApiControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private MutableClock mutableClock;

    @Autowired
    private RateLimitConfig rateLimitConfig;

    @BeforeEach
    void resetClock() {
        mutableClock.reset(Instant.ofEpochSecond(2_000_000));
    }

    // -------------------------------------------------------------------------
    // T9 — GET /api/general within limit → 200 + rate-limit headers
    // -------------------------------------------------------------------------
    @Test
    void generalEndpoint_withinLimit_returns200WithHeaders() throws Exception {
        mockMvc.perform(get("/api/general").header("X-API-Key", "alice"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.message").value("OK"))
                .andExpect(header().exists("X-RateLimit-Limit"))
                .andExpect(header().exists("X-RateLimit-Remaining"))
                .andExpect(header().exists("X-RateLimit-Reset"));
    }

    // -------------------------------------------------------------------------
    // T10 — 21st request to /api/general → 429
    // -------------------------------------------------------------------------
    @Test
    void generalEndpoint_exceeds20_returns429() throws Exception {
        int limit = rateLimitConfig.getConfigForEndpoint("/api/general").getLimit();

        for (int i = 0; i < limit; i++) {
            mockMvc.perform(get("/api/general").header("X-API-Key", "t10-user"))
                    .andExpect(status().isOk());
        }

        mockMvc.perform(get("/api/general").header("X-API-Key", "t10-user"))
                .andExpect(status().is(429))
                .andExpect(jsonPath("$.error").value("Too many requests"))
                .andExpect(jsonPath("$.retryAfterSeconds").isNumber());
    }

    // -------------------------------------------------------------------------
    // T11 — Exhausting /api/submit does not affect /api/general
    // -------------------------------------------------------------------------
    @Test
    void submitEndpoint_independentOf_generalEndpoint() throws Exception {
        int submitLimit = rateLimitConfig.getConfigForEndpoint("/api/submit").getLimit();

        for (int i = 0; i < submitLimit; i++) {
            mockMvc.perform(post("/api/submit").header("X-API-Key", "t11-user"))
                    .andExpect(status().isOk());
        }
        // submit should now be blocked
        mockMvc.perform(post("/api/submit").header("X-API-Key", "t11-user"))
                .andExpect(status().is(429));

        // general should still be available
        mockMvc.perform(get("/api/general").header("X-API-Key", "t11-user"))
                .andExpect(status().isOk());
    }

    // -------------------------------------------------------------------------
    // T12 — GET /api/status returns correct JSON
    // -------------------------------------------------------------------------
    @Test
    void statusEndpoint_returnsCorrectJson() throws Exception {
        mockMvc.perform(get("/api/status").header("X-API-Key", "t12-user"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.limit").isNumber())
                .andExpect(jsonPath("$.remaining").isNumber())
                .andExpect(jsonPath("$.resetAt").isNumber());
    }

    // -------------------------------------------------------------------------
    // T13 — Missing X-API-Key → 400
    // -------------------------------------------------------------------------
    @Test
    void missingApiKey_returns400() throws Exception {
        mockMvc.perform(get("/api/general"))
                .andExpect(status().isBadRequest());
    }

    // -------------------------------------------------------------------------
    // T14 — /api/submit blocks at 6, /api/general still passes
    // -------------------------------------------------------------------------
    @Test
    void submitEndpoint_blocksAt5_beforeGeneralAt20() throws Exception {
        // /api/submit limit = 5; make 5 allowed requests
        for (int i = 0; i < 5; i++) {
            mockMvc.perform(post("/api/submit").header("X-API-Key", "t14-user"))
                    .andExpect(status().isOk());
        }
        // 6th submit → 429
        mockMvc.perform(post("/api/submit").header("X-API-Key", "t14-user"))
                .andExpect(status().is(429));

        // /api/general is independent — still passes
        mockMvc.perform(get("/api/general").header("X-API-Key", "t14-user"))
                .andExpect(status().isOk());
    }

    // -------------------------------------------------------------------------
    // T15 — 429 response includes Retry-After header with value > 0
    // -------------------------------------------------------------------------
    @Test
    void blockedRequest_hasRetryAfterHeader() throws Exception {
        int submitLimit = rateLimitConfig.getConfigForEndpoint("/api/submit").getLimit();

        for (int i = 0; i < submitLimit; i++) {
            mockMvc.perform(post("/api/submit").header("X-API-Key", "t15-user"));
        }

        mockMvc.perform(post("/api/submit").header("X-API-Key", "t15-user"))
                .andExpect(status().is(429))
                .andExpect(header().exists("Retry-After"))
                .andExpect(header().string("Retry-After", not("0")));
    }

    // -------------------------------------------------------------------------
    // T16 — After window reset, client can request again (Clock injection)
    // -------------------------------------------------------------------------
    @Test
    void afterWindowReset_clientCanRequestAgain() throws Exception {
        // Use a 1-second window for this test via a dedicated client key
        // Reconfigure the endpoint to 1-second window
        RateLimitEndpointConfig shortWindow = new RateLimitEndpointConfig();
        shortWindow.setLimit(2);
        shortWindow.setWindowSizeInSeconds(1);
        rateLimitConfig.getEndpoints().put("/api/general", shortWindow);

        String key = "t16-user";

        // Exhaust the 2-request limit
        mockMvc.perform(get("/api/general").header("X-API-Key", key))
                .andExpect(status().isOk());
        mockMvc.perform(get("/api/general").header("X-API-Key", key))
                .andExpect(status().isOk());

        // Now blocked
        mockMvc.perform(get("/api/general").header("X-API-Key", key))
                .andExpect(status().is(429));

        // Advance the injected clock past the 1-second window
        mutableClock.advance(2);

        // Should be allowed again
        mockMvc.perform(get("/api/general").header("X-API-Key", key))
                .andExpect(status().isOk());
    }

    // -------------------------------------------------------------------------
    // TestConfiguration — replaces production Clock bean
    // -------------------------------------------------------------------------
    @TestConfiguration
    static class TestClockConfig {

        /**
         * Expose a single MutableClock that is BOTH the Clock bean AND the MutableClock bean.
         * The @Primary annotation ensures Spring picks this over the production ClockConfig bean.
         */
        @Bean
        @Primary
        public MutableClock clock() {
            return new MutableClock(Instant.ofEpochSecond(2_000_000));
        }
    }

    /**
     * A resettable clock — wraps a volatile Instant so tests can advance time.
     */
    public static class MutableClock extends Clock {

        private volatile Instant now;

        public MutableClock(Instant start) {
            this.now = start;
        }

        public void advance(long seconds) {
            now = now.plusSeconds(seconds);
        }

        public void reset(Instant instant) {
            now = instant;
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
