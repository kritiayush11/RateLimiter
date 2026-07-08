package com.chegg.ratelimiter.config;

import com.chegg.ratelimiter.dto.RateLimitEndpointConfig;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.Map;

/**
 * Binds {@code rate-limit.endpoints} from application.properties.
 *
 * <p>Keys are endpoint paths (e.g. {@code /api/general}); values hold per-endpoint
 * limit and window size. New endpoints are added via config — no code change needed
 * (SOLID: Open/Closed Principle).</p>
 */
@Component
@ConfigurationProperties(prefix = "rate-limit")
public class RateLimitConfig {

    /**
     * Map of endpoint path → config.
     * Example key: "/api/general"
     */
    private Map<String, RateLimitEndpointConfig> endpoints = new HashMap<>();

    public Map<String, RateLimitEndpointConfig> getEndpoints() {
        return endpoints;
    }

    public void setEndpoints(Map<String, RateLimitEndpointConfig> endpoints) {
        this.endpoints = endpoints;
    }

    /**
     * Convenience accessor — returns the config for {@code path}, or a sensible
     * default (20 req / 60 s) when the path is not explicitly configured.
     */
    public RateLimitEndpointConfig getConfigForEndpoint(String path) {
        return endpoints.getOrDefault(path, defaultConfig());
    }

    private RateLimitEndpointConfig defaultConfig() {
        RateLimitEndpointConfig cfg = new RateLimitEndpointConfig();
        cfg.setLimit(20);
        cfg.setWindowSizeInSeconds(60);
        return cfg;
    }
}
