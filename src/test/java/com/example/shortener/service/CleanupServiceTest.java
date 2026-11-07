package com.example.shortener.service;

import com.example.shortener.config.AppProperties;
import com.example.shortener.model.ShortUrl;
import com.example.shortener.repository.ClickEventRepository;
import com.example.shortener.repository.ShortUrlRepository;
import org.junit.jupiter.api.Test;
import org.springframework.data.domain.Pageable;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.Instant;
import java.util.List;

import static com.example.shortener.support.TestFixtures.FIXED_CLOCK;
import static com.example.shortener.support.TestFixtures.NOW;
import static com.example.shortener.support.TestFixtures.properties;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class CleanupServiceTest {
    private final ShortUrlRepository urls = mock(ShortUrlRepository.class);
    private final ClickEventRepository clicks = mock(ClickEventRepository.class);
    private final CacheService cache = mock(CacheService.class);
    private final JdbcTemplate jdbcTemplate = mock(JdbcTemplate.class);
    private final AppProperties properties = properties();

    private CleanupService service() {
        return new CleanupService(urls, clicks, cache, jdbcTemplate, FIXED_CLOCK, properties);
    }

    @Test
    void softDeletesExpiredUrlsAndHardDeletesOldInactiveRowsInOrder() {
        ShortUrl expired = mock(ShortUrl.class);
        when(expired.getId()).thenReturn(7L);
        when(expired.getShortCode()).thenReturn("expired7");

        ShortUrl oldInactive = mock(ShortUrl.class);
        when(oldInactive.getId()).thenReturn(9L);

        when(jdbcTemplate.queryForObject(anyString(), eq(Boolean.class), anyLong())).thenReturn(true);
        when(urls.findAllByActiveTrueAndExpiresAtLessThanEqual(any(Instant.class), any(Pageable.class)))
                .thenReturn(List.of(expired), List.of());
        when(urls.findAllByActiveFalseAndDeactivatedAtLessThanEqual(any(Instant.class), any(Pageable.class)))
                .thenReturn(List.of(oldInactive), List.of());
        when(clicks.deleteOlderThan(any(Instant.class), anyInt())).thenReturn(0);

        service().cleanup();

        verify(expired).deactivate(NOW);
        verify(urls).saveAll(List.of(expired));
        verify(urls).flush();

        var order = inOrder(cache, clicks, urls);
        order.verify(cache).evictAfterCommit(List.of("expired7"));
        order.verify(clicks).deleteForShortUrls(List.of(9L));
        order.verify(urls).deleteAllByIdInBatch(List.of(9L));
        verify(clicks).deleteOlderThan(any(Instant.class), eq(100));
    }

    @Test
    void skipsWhenAnotherInstanceHoldsAdvisoryLock() {
        when(jdbcTemplate.queryForObject(anyString(), eq(Boolean.class), anyLong())).thenReturn(false);

        service().cleanup();

        verifyNoInteractions(urls, clicks, cache);
    }

    @Test
    void treatsNullAdvisoryLockAsNotAcquired() {
        when(jdbcTemplate.queryForObject(anyString(), eq(Boolean.class), anyLong())).thenReturn(null);

        service().cleanup();

        verifyNoInteractions(urls, clicks, cache);
    }
}
