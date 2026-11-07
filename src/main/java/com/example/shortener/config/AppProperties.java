package com.example.shortener.config;

import jakarta.validation.Valid;
import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

import java.net.URI;
import java.time.Duration;
import java.util.List;

@Validated
@ConfigurationProperties(prefix = "app")
public record AppProperties(
        @NotBlank String baseUrl,
        @NotNull Duration cacheTtl,
        @NotBlank String apiKey,
        @NotBlank String apiKeyPepper,
        @Valid @NotNull RateLimit rateLimit,
        @Valid @NotNull RateLimit redirectRateLimit,
        @NotNull Duration analyticsRetention,
        @NotNull Duration expiredUrlRetention,
        @Min(1) int cleanupBatchSize,
        @NotBlank String cleanupCron,
        List<String> corsAllowedOrigins
) {
    public record RateLimit(@Min(1) int requests, @NotNull Duration window) {
        @AssertTrue(message = "rate-limit window must be positive")
        public boolean isWindowPositive() {
            return window != null && !window.isZero() && !window.isNegative();
        }
    }

    @AssertTrue(message = "base-url must be an absolute http or https URL")
    public boolean isBaseUrlValid() {
        try {
            URI uri = URI.create(baseUrl);
            return uri.isAbsolute()
                    && ("http".equalsIgnoreCase(uri.getScheme()) || "https".equalsIgnoreCase(uri.getScheme()))
                    && uri.getHost() != null;
        } catch (RuntimeException exception) {
            return false;
        }
    }

    @AssertTrue(message = "cache-ttl must be positive")
    public boolean isCacheTtlPositive() {
        return isPositive(cacheTtl);
    }

    @AssertTrue(message = "analytics-retention must be positive")
    public boolean isAnalyticsRetentionPositive() {
        return isPositive(analyticsRetention);
    }

    @AssertTrue(message = "expired-url-retention must be positive")
    public boolean isExpiredUrlRetentionPositive() {
        return isPositive(expiredUrlRetention);
    }

    @AssertTrue(message = "api-key must be at least 16 characters")
    public boolean isApiKeyLongEnough() {
        return apiKey != null && apiKey.length() >= 16;
    }

    @AssertTrue(message = "api-key-pepper must be at least 16 characters")
    public boolean isPepperLongEnough() {
        return apiKeyPepper != null && apiKeyPepper.length() >= 16;
    }

    public List<String> corsAllowedOrigins() {
        if (corsAllowedOrigins == null) {
            return List.of();
        }
        return corsAllowedOrigins.stream()
                .filter(origin -> origin != null && !origin.isBlank())
                .map(String::trim)
                .toList();
    }

    private static boolean isPositive(Duration duration) {
        return duration != null && !duration.isZero() && !duration.isNegative();
    }
}
