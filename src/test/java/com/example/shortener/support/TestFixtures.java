package com.example.shortener.support;

import com.example.shortener.config.AppProperties;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;

public final class TestFixtures {
    public static final Instant NOW = Instant.parse("2026-01-02T12:00:00Z");
    public static final Clock FIXED_CLOCK = Clock.fixed(NOW, ZoneOffset.UTC);

    private TestFixtures() {
    }

    public static AppProperties properties() {
        return properties(100, 600);
    }

    public static AppProperties properties(int apiLimit, int redirectLimit) {
        return new AppProperties(
                "http://localhost:8080",
                Duration.ofHours(1),
                "test-api-key-1234567890",
                "test-api-key-pepper-1234567890",
                new AppProperties.RateLimit(apiLimit, Duration.ofMinutes(1)),
                new AppProperties.RateLimit(redirectLimit, Duration.ofMinutes(1)),
                Duration.ofDays(365),
                Duration.ofDays(30),
                100,
                "0 0 3 * * *",
                List.of("http://localhost:3000")
        );
    }
}
