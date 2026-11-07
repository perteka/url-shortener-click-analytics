package com.example.shortener.dto;

import java.time.Instant;

public record ShortUrlResponse(
        String shortCode,
        String shortUrl,
        String originalUrl,
        boolean active,
        Instant createdAt,
        Instant expiresAt
) {
}
