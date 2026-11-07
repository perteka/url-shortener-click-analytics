package com.example.shortener.dto;

import java.time.Instant;

public record ApiKeyResponse(
        Long id,
        String name,
        boolean active,
        boolean admin,
        Instant createdAt
) {
}
