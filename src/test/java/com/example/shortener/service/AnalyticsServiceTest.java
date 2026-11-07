package com.example.shortener.service;

import com.example.shortener.dto.AnalyticsResponse;
import com.example.shortener.model.ApiKey;
import com.example.shortener.model.DeviceType;
import com.example.shortener.model.ShortUrl;
import com.example.shortener.repository.ClickEventRepository;
import com.example.shortener.repository.ShortUrlRepository;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static com.example.shortener.support.TestFixtures.FIXED_CLOCK;
import static com.example.shortener.support.TestFixtures.NOW;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class AnalyticsServiceTest {
    private final ShortUrlRepository shortUrls = mock(ShortUrlRepository.class);
    private final ClickEventRepository clicks = mock(ClickEventRepository.class);

    private AnalyticsService service() {
        return new AnalyticsService(shortUrls, clicks, FIXED_CLOCK);
    }

    private ApiKey owner() {
        ApiKey owner = mock(ApiKey.class);
        when(owner.getId()).thenReturn(1L);
        return owner;
    }

    @Test
    void returnsEmptyGapFilledAnalyticsSeries() {
        ApiKey owner = owner();
        ShortUrl entity = mock(ShortUrl.class);
        when(entity.getId()).thenReturn(7L);
        when(shortUrls.findByShortCodeAndApiKeyId("abc1234", 1L)).thenReturn(Optional.of(entity));
        Instant from = Instant.parse("2026-01-01T00:00:00Z");
        when(clicks.countByDay(7L, from)).thenReturn(List.<Object[]>of(new Object[]{"2026-01-02", 5L}));
        when(clicks.countByReferrer(7L, from, 50)).thenReturn(List.<Object[]>of(new Object[]{"example.com", 5L}));
        when(clicks.countByDevice(7L, from)).thenReturn(List.<Object[]>of(new Object[]{DeviceType.MOBILE, 5L}));

        AnalyticsResponse result = service().analytics("abc1234", 2, owner);

        assertEquals(5, result.totalClicks());
        assertEquals(2, result.byDay().size());
        assertEquals(0L, result.byDay().get("2026-01-01"));
        assertEquals(5L, result.byDay().get("2026-01-02"));
        assertEquals(5L, result.byReferrer().get("example.com"));
        assertEquals(5L, result.byDevice().get("MOBILE"));
    }

    @Test
    void validatesAnalyticsRange() {
        ApiKey owner = owner();
        assertThrows(BadRequestException.class, () -> service().analytics("abc1234", 0, owner));
        assertThrows(BadRequestException.class, () -> service().analytics("abc1234", 366, owner));
    }
}
