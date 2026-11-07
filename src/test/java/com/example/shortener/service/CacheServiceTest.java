package com.example.shortener.service;

import com.example.shortener.config.AppProperties;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static com.example.shortener.support.TestFixtures.FIXED_CLOCK;
import static com.example.shortener.support.TestFixtures.NOW;
import static com.example.shortener.support.TestFixtures.properties;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class CacheServiceTest {
    private final StringRedisTemplate redis = mock(StringRedisTemplate.class);
    @SuppressWarnings("unchecked")
    private final ValueOperations<String, String> values = mock(ValueOperations.class);
    private final RedisGate gate = new RedisGate(FIXED_CLOCK);
    private final AppProperties properties = properties();
    private final MeterRegistry meterRegistry = mock(MeterRegistry.class);
    private final Counter cacheHitsCounter = mock(Counter.class);
    private final Counter cacheMissesCounter = mock(Counter.class);
    private final Counter cacheFallbacksCounter = mock(Counter.class);
    private final Counter cacheEvictionsCounter = mock(Counter.class);

    private CacheService cache() {
        when(meterRegistry.counter("shortener.cache.hits")).thenReturn(cacheHitsCounter);
        when(meterRegistry.counter("shortener.cache.misses")).thenReturn(cacheMissesCounter);
        when(meterRegistry.counter("shortener.cache.fallbacks")).thenReturn(cacheFallbacksCounter);
        when(meterRegistry.counter("shortener.cache.evictions")).thenReturn(cacheEvictionsCounter);
        when(redis.opsForValue()).thenReturn(values);
        return new CacheService(redis, gate, properties, FIXED_CLOCK, meterRegistry);
    }

    @Test
    void readsCurrentAndLegacyCacheEntries() {
        CacheService cache = cache();
        when(values.get("short-url:abc1234")).thenReturn("42||https://example.com");

        assertEquals(Optional.of(new CacheService.Entry(42L, "https://example.com", null)),
                cache.get("abc1234"));
    }

    @Test
    void readsLegacyTwoPartEntry() {
        CacheService cache = cache();
        when(values.get("short-url:abc1234")).thenReturn("42|https://example.com");

        assertEquals(Optional.of(new CacheService.Entry(42L, "https://example.com", null)),
                cache.get("abc1234"));
    }

    @Test
    void returnsEntryWithFutureExpiry() {
        CacheService cache = cache();
        Instant expiry = NOW.plusSeconds(60);
        when(values.get("short-url:abc1234"))
                .thenReturn("42|" + expiry.toEpochMilli() + "|https://example.com");

        assertEquals(Optional.of(new CacheService.Entry(42L, "https://example.com", expiry)),
                cache.get("abc1234"));
    }

    @Test
    void ignoresAndEvictsExpiredEntry() {
        CacheService cache = cache();
        when(values.get("short-url:abc1234"))
                .thenReturn("42|" + NOW.minusSeconds(1).toEpochMilli() + "|https://example.com");

        assertTrue(cache.get("abc1234").isEmpty());
        verify(redis).delete("short-url:abc1234");
    }

    @Test
    void ignoresAndEvictsMalformedEntry() {
        CacheService cache = cache();
        when(values.get("short-url:abc1234")).thenReturn("not-a-valid-entry");

        assertTrue(cache.get("abc1234").isEmpty());
        verify(redis).delete("short-url:abc1234");
    }

    @Test
    void writesWithConfiguredTtl() {
        CacheService cache = cache();

        cache.put("abc1234", 42L, "https://example.com", null);

        verify(values).set("short-url:abc1234", "42||https://example.com", Duration.ofHours(1));
    }

    @Test
    void writesOnlyAfterTransactionCommit() {
        CacheService cache = cache();
        TransactionSynchronizationManager.initSynchronization();
        try {
            cache.putAfterCommit("abc1234", 42L, "https://example.com", null);
            verify(values, never()).set(any(), any(), any(Duration.class));

            TransactionSynchronizationManager.getSynchronizations()
                    .forEach(TransactionSynchronization::afterCommit);

            verify(values).set("short-url:abc1234", "42||https://example.com", Duration.ofHours(1));
        } finally {
            TransactionSynchronizationManager.clearSynchronization();
        }
    }

    @Test
    void capsCacheLifetimeAtUrlExpiry() {
        CacheService cache = cache();
        Instant expiry = NOW.plusSeconds(30);

        cache.put("abc1234", 42L, "https://example.com", expiry);

        verify(values).set(eq("short-url:abc1234"),
                eq("42|" + expiry.toEpochMilli() + "|https://example.com"),
                argThat(ttl -> ttl.compareTo(Duration.ofSeconds(30)) <= 0 && !ttl.isZero()));
    }

    @Test
    void doesNotCacheAlreadyExpiredUrl() {
        CacheService cache = cache();

        cache.put("abc1234", 42L, "https://example.com", NOW.minusSeconds(1));

        verify(values, never()).set(any(), any(), any(Duration.class));
        verify(redis).delete("short-url:abc1234");
    }

    @Test
    void refusesNonHttpUrl() {
        CacheService cache = cache();

        cache.put("abc1234", 42L, "javascript:alert(1)", null);

        verify(values, never()).set(any(), any(), any(Duration.class));
    }

    @Test
    void fallsBackWhenRedisReadFailsAndOpensCircuit() {
        CacheService cache = cache();
        when(values.get("short-url:abc1234")).thenThrow(new RedisConnectionFailureException("down"));

        assertTrue(cache.get("abc1234").isEmpty());
        assertTrue(cache.get("abc1234").isEmpty());

        verify(redis, times(1)).opsForValue();
    }

    @Test
    void fallsBackWhenRedisWriteFails() {
        CacheService cache = cache();
        doThrow(new RedisConnectionFailureException("down"))
                .when(values).set(any(), any(), any(Duration.class));

        cache.put("abc1234", 42L, "https://example.com", null);

        assertTrue(gate.isOpen());
    }

    @Test
    void evictsImmediatelyWithoutTransaction() {
        CacheService cache = cache();

        cache.evictAfterCommit("abc1234");

        verify(redis).delete("short-url:abc1234");
    }

    @Test
    void evictsOnlyAfterCommitInsideTransaction() {
        CacheService cache = cache();
        TransactionSynchronizationManager.initSynchronization();
        try {
            cache.evictAfterCommit(List.of("abc1234", "def5678"));
            verify(redis, never()).delete(any(String.class));

            List<TransactionSynchronization> synchronizations =
                    TransactionSynchronizationManager.getSynchronizations();
            synchronizations.forEach(TransactionSynchronization::afterCommit);

            verify(redis).delete("short-url:abc1234");
            verify(redis).delete("short-url:def5678");
        } finally {
            TransactionSynchronizationManager.clearSynchronization();
        }
    }
}
