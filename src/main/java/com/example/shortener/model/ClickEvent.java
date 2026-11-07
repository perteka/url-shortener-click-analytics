package com.example.shortener.model;

import jakarta.persistence.*;

import java.time.Instant;

@Entity
@Table(name = "click_events", indexes = {
        @Index(name = "idx_click_url_time", columnList = "short_url_id, clicked_at"),
        @Index(name = "idx_click_time", columnList = "clicked_at"),
        @Index(name = "idx_click_referrer_host", columnList = "referrer_host")
})
public class ClickEvent {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "short_url_id", nullable = false)
    private ShortUrl shortUrl;

    @Column(name = "clicked_at", nullable = false, updatable = false)
    private Instant clickedAt;

    @Column(length = 512)
    private String referrer;

    @Column(name = "referrer_host", length = 255)
    private String referrerHost;

    @Enumerated(EnumType.STRING)
    @Column(name = "device_type", nullable = false, length = 16)
    private DeviceType deviceType;

    protected ClickEvent() {
    }

    public ClickEvent(ShortUrl shortUrl, String referrer, String referrerHost, DeviceType deviceType, Instant clickedAt) {
        this.shortUrl = shortUrl;
        this.referrer = referrer;
        this.referrerHost = referrerHost;
        this.deviceType = deviceType;
        this.clickedAt = clickedAt;
    }

    public Long getId() {
        return id;
    }

    public ShortUrl getShortUrl() {
        return shortUrl;
    }

    public Instant getClickedAt() {
        return clickedAt;
    }

    public String getReferrer() {
        return referrer;
    }

    public String getReferrerHost() {
        return referrerHost;
    }

    public DeviceType getDeviceType() {
        return deviceType;
    }
}
