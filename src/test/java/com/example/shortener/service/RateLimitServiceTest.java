package com.example.shortener.service;

import com.example.shortener.config.AppProperties;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;

import static com.example.shortener.support.TestFixtures.FIXED_CLOCK;
import static com.example.shortener.support.TestFixtures.properties;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class RateLimitServiceTest {
    private final StringRedisTemplate redis = mock(StringRedisTemplate.class);
    private final RedisGate gate = new RedisGate(FIXED_CLOCK);

    @Test
    void enforcesLimitUsingAtomicRedisCounters() {
        AppProperties properties = properties(2, 2);
        RateLimitService service = new RateLimitService(redis, gate, properties, FIXED_CLOCK);
        when(redis.execute(any(RedisScript.class), anyList(), any())).thenReturn(1L, 2L, 3L);

        assertTrue(service.allowApiKey(1L));
        assertTrue(service.allowApiKey(1L));
        assertFalse(service.allowApiKey(1L));
    }

    @Test
    void fallsBackToLocalWindowsWhenRedisFails() {
        AppProperties properties = properties(2, 2);
        RateLimitService service = new RateLimitService(redis, gate, properties, FIXED_CLOCK);
        when(redis.execute(any(RedisScript.class), anyList(), any()))
                .thenThrow(new RedisConnectionFailureException("down"));

        assertTrue(service.allowApiKey(1L));
        assertTrue(service.allowApiKey(1L));
        assertFalse(service.allowApiKey(1L));

        verify(redis, times(1)).execute(any(RedisScript.class), anyList(), any());
        assertTrue(gate.isOpen());
    }

    @Test
    void keepsApiAndRedirectWindowsIndependent() {
        AppProperties properties = properties(1, 1);
        RateLimitService service = new RateLimitService(redis, gate, properties, FIXED_CLOCK);
        when(redis.execute(any(RedisScript.class), anyList(), any()))
                .thenThrow(new RedisConnectionFailureException("down"));

        assertTrue(service.allowApiKey(1L));
        assertFalse(service.allowApiKey(1L));
        assertTrue(service.allowRedirect("192.0.2.10"));
        assertTrue(service.allowRedirect("192.0.2.11"));
    }

    @Test
    void capsLocalFallbackKeysAndRejectsNewKeysAtCapacity() {
        RateLimitService service = new RateLimitService(redis, gate, properties(100, 100), FIXED_CLOCK);
        when(redis.execute(any(RedisScript.class), anyList(), any()))
                .thenThrow(new RedisConnectionFailureException("down"));

        for (int index = 0; index < 10_000; index++) {
            assertTrue(service.allowRedirect("192.0.2." + index));
        }

        assertFalse(service.allowRedirect("198.51.100.1"));
        assertTrue(service.allowRedirect("192.0.2.1"));
    }

    @Test
    void exposesWholeWindowAsRetryAfter() {
        RateLimitService service = new RateLimitService(redis, gate, properties(10, 10), FIXED_CLOCK);

        assertEquals(60, service.apiRetryAfterSeconds());
        assertEquals(60, service.redirectRetryAfterSeconds());
    }
}
