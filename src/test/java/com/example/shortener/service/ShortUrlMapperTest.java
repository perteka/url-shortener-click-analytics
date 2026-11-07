package com.example.shortener.service;

import com.example.shortener.config.AppProperties;
import com.example.shortener.dto.ShortUrlResponse;
import com.example.shortener.model.ShortUrl;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class ShortUrlMapperTest {
    @Test
    void buildsPublicUrlAndKeepsExpiry() {
        AppProperties properties = new AppProperties(
                "https://sho.rt/", Duration.ofHours(1), "test-api-key-1234567890",
                "test-api-key-pepper-1234567890",
                new AppProperties.RateLimit(100, Duration.ofMinutes(1)),
                new AppProperties.RateLimit(600, Duration.ofMinutes(1)),
                Duration.ofDays(365), Duration.ofDays(30), 100, "0 0 3 * * *", List.of()
        );
        Instant createdAt = Instant.parse("2026-01-01T00:00:00Z");
        Instant expiresAt = Instant.parse("2026-02-01T00:00:00Z");
        ShortUrl entity = mock(ShortUrl.class);
        when(entity.getShortCode()).thenReturn("abc1234");
        when(entity.getOriginalUrl()).thenReturn("https://example.com");
        when(entity.isActive()).thenReturn(true);
        when(entity.getCreatedAt()).thenReturn(createdAt);
        when(entity.getExpiresAt()).thenReturn(expiresAt);

        ShortUrlResponse response = new ShortUrlMapper(properties).toResponse(entity);

        assertEquals("abc1234", response.shortCode());
        assertEquals("https://sho.rt/abc1234", response.shortUrl());
        assertEquals("https://example.com", response.originalUrl());
        assertTrue(response.active());
        assertEquals(createdAt, response.createdAt());
        assertEquals(expiresAt, response.expiresAt());
    }
}
