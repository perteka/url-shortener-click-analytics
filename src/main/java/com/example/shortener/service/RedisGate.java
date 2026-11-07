package com.example.shortener.service;

import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Small circuit breaker for Redis. The application must keep serving redirects
 * when Redis is unavailable, so callers check this gate before making a call
 * and open it after a failure to avoid paying the connection timeout on every
 * request.
 */
@Component
public class RedisGate {
    private static final Duration COOLDOWN = Duration.ofSeconds(10);

    private final Clock clock;
    private final AtomicLong blockedUntil = new AtomicLong();

    public RedisGate(Clock clock) {
        this.clock = clock;
    }

    public boolean isOpen() {
        return clock.instant().toEpochMilli() < blockedUntil.get();
    }

    public void recordFailure() {
        blockedUntil.set(clock.instant().plus(COOLDOWN).toEpochMilli());
    }

    public void recordSuccess() {
        blockedUntil.set(0);
    }
}
