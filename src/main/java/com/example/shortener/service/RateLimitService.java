package com.example.shortener.service;

import com.example.shortener.config.AppProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;

@Service
public class RateLimitService {
    private static final int MAX_LOCAL_WINDOWS = 10_000;
    private static final Logger log = LoggerFactory.getLogger(RateLimitService.class);
    private static final DefaultRedisScript<Long> INCREMENT_SCRIPT = new DefaultRedisScript<>(
            "local current = redis.call('INCR', KEYS[1]); " +
                    "if current == 1 then redis.call('PEXPIRE', KEYS[1], ARGV[1]) end; " +
                    "return current",
            Long.class
    );

    private record Window(long startedAt, int count, long windowMillis) {
    }

    private final ConcurrentHashMap<String, Window> localWindows = new ConcurrentHashMap<>();
    private final StringRedisTemplate redis;
    private final RedisGate redisGate;
    private final Clock clock;
    private final int apiLimit;
    private final long apiWindowMillis;
    private final int redirectLimit;
    private final long redirectWindowMillis;

    public RateLimitService(StringRedisTemplate redis, RedisGate redisGate, AppProperties properties, Clock clock) {
        this.redis = redis;
        this.redisGate = redisGate;
        this.clock = clock;
        this.apiLimit = properties.rateLimit().requests();
        this.apiWindowMillis = properties.rateLimit().window().toMillis();
        this.redirectLimit = properties.redirectRateLimit().requests();
        this.redirectWindowMillis = properties.redirectRateLimit().window().toMillis();
    }

    public boolean allowApiKey(Long apiKeyId) {
        return allow("rate:api:" + apiKeyId, apiLimit, apiWindowMillis);
    }

    public boolean allowRedirect(String clientIp) {
        return allow("rate:redirect:" + (clientIp == null ? "unknown" : clientIp), redirectLimit, redirectWindowMillis);
    }

    public long apiRetryAfterSeconds() {
        return retryAfterSeconds(apiWindowMillis);
    }

    public long redirectRetryAfterSeconds() {
        return retryAfterSeconds(redirectWindowMillis);
    }

    private boolean allow(String key, int limit, long windowMillis) {
        Long redisCount = incrementInRedis(key, windowMillis);
        if (redisCount != null) {
            return redisCount <= limit;
        }
        return allowLocally(key, limit, windowMillis);
    }

    private Long incrementInRedis(String key, long windowMillis) {
        if (redisGate.isOpen()) {
            return null;
        }
        try {
            Long count = redis.execute(INCREMENT_SCRIPT, List.of(key), Long.toString(windowMillis));
            redisGate.recordSuccess();
            return count;
        } catch (RuntimeException exception) {
            redisGate.recordFailure();
            log.warn("Redis rate limiter unavailable; using the local fallback: {}", exception.getMessage());
            return null;
        }
    }

    private boolean allowLocally(String key, int limit, long windowMillis) {
        long now = Instant.now(clock).toEpochMilli();
        synchronized (localWindows) {
            Window old = localWindows.get(key);
            if (old == null) {
                localWindows.entrySet().removeIf(entry ->
                        now - entry.getValue().startedAt() >= entry.getValue().windowMillis() * 2);
                if (localWindows.size() >= MAX_LOCAL_WINDOWS) {
                    log.warn("Local rate-limit capacity reached; rejecting a new rate-limit key");
                    return false;
                }
                localWindows.put(key, new Window(now, 1, windowMillis));
                return true;
            }
            if (now - old.startedAt() >= windowMillis) {
                localWindows.put(key, new Window(now, 1, windowMillis));
                return true;
            }
            Window next = new Window(old.startedAt(), old.count() + 1, windowMillis);
            localWindows.put(key, next);
            return next.count() <= limit;
        }
    }

    private static long retryAfterSeconds(long windowMillis) {
        return Math.max(1, (windowMillis + 999) / 1000);
    }
}
