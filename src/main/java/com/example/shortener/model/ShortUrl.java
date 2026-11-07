package com.example.shortener.model;

import jakarta.persistence.*;

import java.time.Instant;

@Entity
@Table(name = "short_urls", indexes = {
        @Index(name = "idx_short_urls_expires_at", columnList = "expires_at"),
        @Index(name = "idx_short_urls_api_key_id", columnList = "api_key_id"),
        @Index(name = "idx_short_urls_active_expires", columnList = "active, expires_at"),
        @Index(name = "idx_short_urls_active_deactivated", columnList = "active, deactivated_at")
})
public class ShortUrl {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "short_code", nullable = false, unique = true, length = 16)
    private String shortCode;

    @Column(name = "original_url", nullable = false, length = 2048)
    private String originalUrl;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "api_key_id", nullable = false)
    private ApiKey apiKey;

    @Column(nullable = false)
    private boolean active = true;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt = Instant.now();

    @Column(name = "expires_at")
    private Instant expiresAt;

    @Column(name = "deactivated_at")
    private Instant deactivatedAt;

    protected ShortUrl() {
    }

    public ShortUrl(String shortCode, String originalUrl, ApiKey apiKey, Instant expiresAt) {
        this.shortCode = shortCode;
        this.originalUrl = originalUrl;
        this.apiKey = apiKey;
        this.expiresAt = expiresAt;
    }

    public Long getId() {
        return id;
    }

    public String getShortCode() {
        return shortCode;
    }

    public String getOriginalUrl() {
        return originalUrl;
    }

    public ApiKey getApiKey() {
        return apiKey;
    }

    public boolean isActive() {
        return active;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getExpiresAt() {
        return expiresAt;
    }

    public Instant getDeactivatedAt() {
        return deactivatedAt;
    }

    public void update(String url, Instant expiry) {
        this.originalUrl = url;
        this.expiresAt = expiry;
    }

    public void activate() {
        this.active = true;
        this.deactivatedAt = null;
    }

    public void deactivate(Instant now) {
        this.active = false;
        if (this.deactivatedAt == null) {
            this.deactivatedAt = now;
        }
    }

    public boolean isExpired(Instant now) {
        return expiresAt != null && !expiresAt.isAfter(now);
    }
}
