package com.example.shortener.service;

import com.example.shortener.config.AppProperties;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

class ApiKeyHasherTest {
    @Test
    void producesStableHmacSha256HexDigest() {
        ApiKeyHasher hasher = new ApiKeyHasher(propertiesWithPepper("key"));

        assertEquals("f7bc83f430538424b13298e6aa6fb143ef4d59a14946175997479dbc2d1a3cd8",
                hasher.hash("The quick brown fox jumps over the lazy dog"));
    }

    @Test
    void hashesDifferentInputsDifferently() {
        ApiKeyHasher hasher = new ApiKeyHasher(propertiesWithPepper("pepper-pepper-pepper"));

        String first = hasher.hash("first-key");
        String second = hasher.hash("second-key");

        assertEquals(64, first.length());
        assertEquals(first, hasher.hash("first-key"));
        assertNotEquals(first, second);
    }

    private AppProperties propertiesWithPepper(String pepper) {
        return new AppProperties(
                "http://localhost:8080",
                Duration.ofHours(1),
                "test-api-key-1234567890",
                pepper,
                new AppProperties.RateLimit(100, Duration.ofMinutes(1)),
                new AppProperties.RateLimit(600, Duration.ofMinutes(1)),
                Duration.ofDays(365),
                Duration.ofDays(30),
                100,
                "0 0 3 * * *",
                List.of()
        );
    }
}
