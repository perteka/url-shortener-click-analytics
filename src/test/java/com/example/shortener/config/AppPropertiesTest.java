package com.example.shortener.config;

import jakarta.validation.Validation;
import jakarta.validation.Validator;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;

import static com.example.shortener.support.TestFixtures.properties;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AppPropertiesTest {
    private final Validator validator = Validation.buildDefaultValidatorFactory().getValidator();

    @Test
    void acceptsValidConfiguration() {
        assertTrue(validator.validate(properties()).isEmpty());
    }

    @Test
    void rejectsUnsafeOrMalformedConfiguration() {
        assertFalse(validator.validate(new AppProperties(
                "ftp://example.com", Duration.ofHours(1), "test-api-key-1234567890",
                "test-api-key-pepper-1234567890",
                new AppProperties.RateLimit(100, Duration.ofMinutes(1)),
                new AppProperties.RateLimit(600, Duration.ofMinutes(1)),
                Duration.ofDays(365), Duration.ofDays(30), 100, "0 0 3 * * *", List.of()
        )).isEmpty());

        assertFalse(validator.validate(new AppProperties(
                "http://localhost:8080", Duration.ZERO, "short", "short",
                new AppProperties.RateLimit(0, Duration.ZERO),
                new AppProperties.RateLimit(0, Duration.ZERO),
                Duration.ZERO, Duration.ZERO, 0, "", List.of()
        )).isEmpty());
    }

    @Test
    void trimsAndFiltersCorsOrigins() {
        AppProperties properties = new AppProperties(
                "http://localhost:8080", Duration.ofHours(1), "test-api-key-1234567890",
                "test-api-key-pepper-1234567890",
                new AppProperties.RateLimit(100, Duration.ofMinutes(1)),
                new AppProperties.RateLimit(600, Duration.ofMinutes(1)),
                Duration.ofDays(365), Duration.ofDays(30), 100, "0 0 3 * * *",
                java.util.Arrays.asList(" http://localhost:3000 ", "", null, "https://example.com")
        );

        assertEquals(List.of("http://localhost:3000", "https://example.com"),
                properties.corsAllowedOrigins());
    }
}
