package com.example.shortener.dto;

import jakarta.validation.constraints.Future;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import java.time.Instant;

public record CreateShortUrlRequest(
        @NotBlank
        @Size(max = 2048)
        @Pattern(regexp = "(?i)^https?://.+", message = "URL must use http or https")
        String url,
        @Size(min = 4, max = 16)
        @Pattern(regexp = "[A-Za-z0-9_-]+", message = "Custom code may contain letters, digits, _ and -")
        String customCode,
        @Future(message = "Expiry must be in the future")
        Instant expiresAt
) {
}
