package com.chegg.ratelimiter.dto;

/**
 * Per-endpoint rate limit configuration loaded from application.properties.
 */
public class RateLimitEndpointConfig {

    private int limit = 20;
    private int windowSizeInSeconds = 60;

    public int getLimit() {
        return limit;
    }

    public void setLimit(int limit) {
        this.limit = limit;
    }

    public int getWindowSizeInSeconds() {
        return windowSizeInSeconds;
    }

    public void setWindowSizeInSeconds(int windowSizeInSeconds) {
        this.windowSizeInSeconds = windowSizeInSeconds;
    }
}
