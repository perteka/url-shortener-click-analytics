package com.example.shortener.service;

import com.example.shortener.config.AppProperties;
import com.example.shortener.dto.AnalyticsResponse;
import com.example.shortener.model.ApiKey;
import com.example.shortener.model.ShortUrl;
import com.example.shortener.repository.ClickEventRepository;
import com.example.shortener.repository.ShortUrlRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.Map;

@Service
public class AnalyticsService {
    private static final int MAX_REFERRER_GROUPS = 50;

    private final ShortUrlRepository shortUrls;
    private final ClickEventRepository clicks;
    private final Clock clock;

    public AnalyticsService(ShortUrlRepository shortUrls,
                             ClickEventRepository clicks,
                             Clock clock) {
        this.shortUrls = shortUrls;
        this.clicks = clicks;
        this.clock = clock;
    }

    @Transactional(readOnly = true)
    public AnalyticsResponse analytics(String code, int days, ApiKey owner) {
        if (days < 1 || days > 365) {
            throw new BadRequestException("days must be between 1 and 365");
        }
        ShortUrl entity = owned(code, owner);
        Instant now = clock.instant();
        LocalDate today = LocalDate.ofInstant(now, ZoneOffset.UTC);
        LocalDate start = today.minusDays(days - 1L);
        Instant from = start.atStartOfDay(ZoneOffset.UTC).toInstant();

        Map<String, Long> byDay = new LinkedHashMap<>();
        for (LocalDate day = start; !day.isAfter(today); day = day.plusDays(1)) {
            byDay.put(day.toString(), 0L);
        }
        clicks.countByDay(entity.getId(), from)
                .forEach(row -> byDay.put(String.valueOf(row[0]), ((Number) row[1]).longValue()));

        Map<String, Long> byReferrer = new LinkedHashMap<>();
        clicks.countByReferrer(entity.getId(), from, MAX_REFERRER_GROUPS)
                .forEach(row -> byReferrer.put(String.valueOf(row[0]), ((Number) row[1]).longValue()));

        Map<String, Long> byDevice = new LinkedHashMap<>();
        clicks.countByDevice(entity.getId(), from)
                .forEach(row -> byDevice.put(String.valueOf(row[0]), ((Number) row[1]).longValue()));

        long total = byDay.values().stream().mapToLong(Long::longValue).sum();
        return new AnalyticsResponse(total, byDay, byReferrer, byDevice);
    }

    private ShortUrl owned(String code, ApiKey owner) {
        if (owner == null) {
            throw new UnauthorizedException("Invalid API key");
        }
        return shortUrls.findByShortCodeAndApiKeyId(code, owner.getId())
                .orElseThrow(() -> new NotFoundException("Short URL not found"));
    }
}
