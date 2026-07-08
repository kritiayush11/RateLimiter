package com.chegg.ratelimiter.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Clock;

/**
 * Provides the system clock as a Spring bean.
 *
 * <p>Keeping this in its own class allows integration tests to override only this bean
 * (via a {@code @Primary} {@code @TestConfiguration}) without touching WebConfig.</p>
 */
@Configuration
public class ClockConfig {

    @Bean
    public Clock clock() {
        return Clock.systemUTC();
    }
}
