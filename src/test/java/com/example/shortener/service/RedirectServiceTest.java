package com.example.shortener.service;

import com.example.shortener.model.DeviceType;
import com.example.shortener.model.ShortUrl;
import com.example.shortener.repository.ShortUrlRepository;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.context.ApplicationEventPublisher;

import java.time.Instant;
import java.util.Optional;

import static com.example.shortener.support.TestFixtures.FIXED_CLOCK;
import static com.example.shortener.support.TestFixtures.NOW;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class RedirectServiceTest {
    private final ShortUrlRepository shortUrls = mock(ShortUrlRepository.class);
    private final CacheService cache = mock(CacheService.class);
    private final ShortUrlMapper mapper = mock(ShortUrlMapper.class);
    private final ShortUrlWriter writer = mock(ShortUrlWriter.class);
    private final ApplicationEventPublisher events = mock(ApplicationEventPublisher.class);
    private final ReferrerNormalizer referrerNormalizer = mock(ReferrerNormalizer.class);

    private RedirectService service() {
        return new RedirectService(shortUrls, cache, events, referrerNormalizer, FIXED_CLOCK);
    }

    @Test
    void redirectsFromCacheWithoutDatabaseLookup() {
        when(cache.get("abc1234"))
                .thenReturn(Optional.of(new CacheService.Entry(42L, "https://example.com", null)));
        when(referrerNormalizer.host("https://ref.example/path")).thenReturn("ref.example");

        String target = service().redirect("abc1234", "https://ref.example/path", DeviceType.MOBILE);

        assertEquals("https://example.com", target);
        verifyNoInteractions(shortUrls);
        ArgumentCaptor<RedirectEvent> event = ArgumentCaptor.forClass(RedirectEvent.class);
        verify(events).publishEvent(event.capture());
        assertEquals(42L, event.getValue().shortUrlId());
        assertEquals("ref.example", event.getValue().referrerHost());
        assertEquals(DeviceType.MOBILE, event.getValue().deviceType());
        assertEquals(NOW, event.getValue().clickedAt());
    }

    @Test
    void resolvesCacheMissFromDatabaseAndFillsCache() {
        ShortUrl entity = mock(ShortUrl.class);
        when(entity.getId()).thenReturn(42L);
        when(entity.getOriginalUrl()).thenReturn("https://example.com");
        when(entity.getExpiresAt()).thenReturn(null);
        when(entity.isExpired(NOW)).thenReturn(false);
        when(cache.get("abc1234")).thenReturn(Optional.empty());
        when(shortUrls.findByShortCodeAndActiveTrue("abc1234")).thenReturn(Optional.of(entity));

        String target = service().redirect("abc1234", null, DeviceType.DESKTOP);

        assertEquals("https://example.com", target);
        verify(shortUrls).findByShortCodeAndActiveTrue("abc1234");
        verify(cache).put("abc1234", 42L, "https://example.com", null);
        verify(events).publishEvent(any(RedirectEvent.class));
    }

    @Test
    void rejectsUnknownOrExpiredShortUrl() {
        when(cache.get("missing1")).thenReturn(Optional.empty());
        when(shortUrls.findByShortCodeAndActiveTrue("missing1")).thenReturn(Optional.empty());
        assertThrows(NotFoundException.class, () -> service().redirect("missing1", null, DeviceType.UNKNOWN));

        ShortUrl expired = mock(ShortUrl.class);
        when(expired.isExpired(NOW)).thenReturn(true);
        when(cache.get("expired1")).thenReturn(Optional.empty());
        when(shortUrls.findByShortCodeAndActiveTrue("expired1")).thenReturn(Optional.of(expired));
        assertThrows(NotFoundException.class, () -> service().redirect("expired1", null, DeviceType.UNKNOWN));
        verify(cache, never()).put(eq("expired1"), any(), anyString(), any());
    }
}
