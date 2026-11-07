package com.example.shortener.service;

import com.example.shortener.config.AppProperties;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

@Service
public class CacheService {
    private static final Logger log = LoggerFactory.getLogger(CacheService.class);
    private static final String PREFIX = "short-url:";

    private final StringRedisTemplate redis;
    private final RedisGate redisGate;
    private final Duration ttl;
    private final Clock clock;
    private final Counter cacheHits;
    private final Counter cacheMisses;
    private final Counter cacheFallbacks;
    private final Counter cacheEvictions;

    public CacheService(StringRedisTemplate redis,
                        RedisGate redisGate,
                        AppProperties properties,
                        Clock clock,
                        MeterRegistry meterRegistry) {
        this.redis = redis;
        this.redisGate = redisGate;
        this.ttl = properties.cacheTtl();
        this.clock = clock;
        this.cacheHits = meterRegistry.counter("shortener.cache.hits");
        this.cacheMisses = meterRegistry.counter("shortener.cache.misses");
        this.cacheFallbacks = meterRegistry.counter("shortener.cache.fallbacks");
        this.cacheEvictions = meterRegistry.counter("shortener.cache.evictions");
    }

    public record Entry(Long id, String url, Instant expiresAt) {
    }

    public Optional<Entry> get(String code) {
        if (redisGate.isOpen()) {
            cacheMisses.increment();
            return Optional.empty();
        }
        final String value;
        try {
            value = redis.opsForValue().get(PREFIX + code);
            redisGate.recordSuccess();
        } catch (RuntimeException exception) {
            redisGate.recordFailure();
            cacheMisses.increment();
            cacheFallbacks.increment();
            log.warn("Redis read failed for short code {}; falling back to the database: {}",
                    code, exception.getMessage());
            return Optional.empty();
        }
        if (value == null) {
            cacheMisses.increment();
            return Optional.empty();
        }
        try {
            Entry entry = parse(value);
            if (entry.expiresAt() != null && !entry.expiresAt().isAfter(clock.instant())) {
                evict(code);
                cacheMisses.increment();
                return Optional.empty();
            }
            cacheHits.increment();
            return Optional.of(entry);
        } catch (RuntimeException exception) {
            log.warn("Ignoring malformed cache entry for short code {}", code, exception);
            evict(code);
            cacheMisses.increment();
            return Optional.empty();
        }
    }

    public void put(String code, Long id, String url, Instant expiresAt) {
        if (id == null || !isHttpUrl(url) || redisGate.isOpen()) {
            return;
        }
        Instant now = clock.instant();
        Duration effectiveTtl = ttl;
        if (expiresAt != null) {
            Duration untilExpiry = Duration.between(now, expiresAt);
            if (untilExpiry.isNegative() || untilExpiry.isZero()) {
                evict(code);
                return;
            }
            if (untilExpiry.compareTo(effectiveTtl) < 0) {
                effectiveTtl = untilExpiry;
            }
        }
        long ttlMillis = Math.max(1L, effectiveTtl.toMillis());
        String expiryValue = expiresAt == null ? "" : Long.toString(expiresAt.toEpochMilli());
        String value = id + "|" + expiryValue + "|" + url;
        try {
            redis.opsForValue().set(PREFIX + code, value, Duration.ofMillis(ttlMillis));
            redisGate.recordSuccess();
        } catch (RuntimeException exception) {
            redisGate.recordFailure();
            log.warn("Redis write failed for short code {}; continuing without cache: {}",
                    code, exception.getMessage());
        }
    }

    public void evict(String code) {
        if (redisGate.isOpen()) {
            return;
        }
        try {
            redis.delete(PREFIX + code);
            redisGate.recordSuccess();
            cacheEvictions.increment();
        } catch (RuntimeException exception) {
            redisGate.recordFailure();
            log.warn("Redis eviction failed for short code {}; entry will expire by TTL: {}",
                    code, exception.getMessage());
        }
    }

    public void evictAfterCommit(String code) {
        evictAfterCommit(List.of(code));
    }

    public void evictAfterCommit(Collection<String> codes) {
        if (codes == null || codes.isEmpty()) {
            return;
        }
        Collection<String> copy = List.copyOf(codes);
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    copy.forEach(CacheService.this::evict);
                }
            });
        } else {
            copy.forEach(this::evict);
        }
    }

    private Entry parse(String value) {
        String[] parts = value.split("\\|", 3);
        if (parts.length == 2) {
            // Backward-compatible entry written before the expiry field was added.
            return new Entry(Long.parseLong(parts[0]), parts[1], null);
        }
        if (parts.length != 3) {
            throw new IllegalArgumentException("Invalid cache entry");
        }
        Long id = Long.parseLong(parts[0]);
        Instant expiresAt = parts[1].isEmpty() ? null : Instant.ofEpochMilli(Long.parseLong(parts[1]));
        return new Entry(id, parts[2], expiresAt);
    }

    private static boolean isHttpUrl(String url) {
        if (url == null) {
            return false;
        }
        String lower = url.toLowerCase(Locale.ROOT);
        return lower.startsWith("http://") || lower.startsWith("https://");
    }
}
