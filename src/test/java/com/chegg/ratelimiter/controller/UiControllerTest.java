package com.chegg.ratelimiter.controller;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * TDD tests T17–T21 for the UI page and its backend button interactions.
 *
 * T17 — GET / returns HTTP 200 (page loads)
 * T18 — Response body contains all three button elements
 * T19 — GET /api/general with default key returns {"message":"OK"}
 * T20 — POST /api/submit with default key returns {"message":"OK"}
 * T21 — GET /api/status with default key returns JSON with limit, remaining, resetAt
 *
 * SOLID notes:
 *  - Single Responsibility: this test class only covers UI page + button endpoint behaviour.
 *  - Open/Closed: UiController is a new class; no existing controllers are modified.
 *  - Dependency Inversion: tests depend on the HTTP contract, not on UiController internals.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class UiControllerTest {

    /** Default API key hardcoded in the UI page JavaScript. */
    private static final String DEFAULT_KEY = "demo-user";

    @Autowired
    private MockMvc mockMvc;

    // -------------------------------------------------------------------------
    // T17 — GET / returns HTTP 200
    // -------------------------------------------------------------------------
    @Test
    void uiPage_loads_returns200() throws Exception {
        mockMvc.perform(get("/"))
                .andExpect(status().isOk());
    }

    // -------------------------------------------------------------------------
    // T18 — Page body contains all three button elements (by id)
    // -------------------------------------------------------------------------
    @Test
    void uiPage_containsAllThreeButtons() throws Exception {
        mockMvc.perform(get("/"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("id=\"btn-general\"")))
                .andExpect(content().string(containsString("id=\"btn-submit\"")))
                .andExpect(content().string(containsString("id=\"btn-status\"")));
    }

    // -------------------------------------------------------------------------
    // T19 — GET /api/general with default key → 200 + {"message":"OK"}
    // -------------------------------------------------------------------------
    @Test
    void generalEndpoint_withDefaultKey_returns200AndOkMessage() throws Exception {
        mockMvc.perform(get("/api/general").header("X-API-Key", DEFAULT_KEY))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.message").value("OK"));
    }

    // -------------------------------------------------------------------------
    // T20 — POST /api/submit with default key → 200 + {"message":"OK"}
    // -------------------------------------------------------------------------
    @Test
    void submitEndpoint_withDefaultKey_returns200AndOkMessage() throws Exception {
        mockMvc.perform(post("/api/submit").header("X-API-Key", DEFAULT_KEY))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.message").value("OK"));
    }

    // -------------------------------------------------------------------------
    // T21 — GET /api/status with default key → 200 + JSON with limit/remaining/resetAt
    // -------------------------------------------------------------------------
    @Test
    void statusEndpoint_withDefaultKey_returnsStatusJson() throws Exception {
        mockMvc.perform(get("/api/status").header("X-API-Key", DEFAULT_KEY))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.limit").isNumber())
                .andExpect(jsonPath("$.remaining").isNumber())
                .andExpect(jsonPath("$.resetAt").isNumber());
    }
}
