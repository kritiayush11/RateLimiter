package com.chegg.ratelimiter.controller;

import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;

/**
 * Serves the demo UI page (SOLID: Single Responsibility + Open/Closed).
 *
 * <p>Responsibilities:
 * <ul>
 *   <li>Serve {@code GET /} → renders the {@code index} Thymeleaf template.</li>
 * </ul>
 *
 * <p>This class has no dependency on {@link com.chegg.ratelimiter.service.RateLimiter}
 * or any business logic — it only resolves the view name (Dependency Inversion).
 * All API interactions happen client-side via JavaScript fetch calls to the existing
 * {@link ApiController} endpoints.</p>
 *
 * <p>No existing classes were modified to add this page (Open/Closed Principle).</p>
 */
@Controller
public class UiController {

    /**
     * GET / — returns the demo UI page with three buttons.
     *
     * @return Thymeleaf view name resolved to {@code templates/index.html}
     */
    @GetMapping("/")
    public String index() {
        return "index";
    }
}
