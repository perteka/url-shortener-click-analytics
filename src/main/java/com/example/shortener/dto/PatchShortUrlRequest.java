package com.example.shortener.dto;

import jakarta.validation.constraints.Future;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import java.time.Instant;

public record PatchShortUrlRequest(
        @Size(max = 2048)
        @Pattern(regexp = "(?i)^https?://.+", message = "URL must use http or https")
        String url,
        @Future(message = "Expiry must be in the future")
        Instant expiresAt,
        Boolean clearExpiry,
        Boolean active
) {
}
