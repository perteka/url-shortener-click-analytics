package com.example.shortener.service;

import com.example.shortener.config.AppProperties;
import com.example.shortener.model.ShortUrl;
import com.example.shortener.repository.ClickEventRepository;
import com.example.shortener.repository.ShortUrlRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.PageRequest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

@Service
public class CleanupService {
    private static final Logger log = LoggerFactory.getLogger(CleanupService.class);
    private static final long ADVISORY_LOCK_ID = 7_334_512_009L;
    private static final int CLICK_DELETE_CHUNK_SIZE = 500;

    private final ShortUrlRepository shortUrls;
    private final ClickEventRepository clicks;
    private final CacheService cache;
    private final JdbcTemplate jdbcTemplate;
    private final Clock clock;
    private final AppProperties properties;

    public CleanupService(ShortUrlRepository shortUrls,
                          ClickEventRepository clicks,
                          CacheService cache,
                          JdbcTemplate jdbcTemplate,
                          Clock clock,
                          AppProperties properties) {
        this.shortUrls = shortUrls;
        this.clicks = clicks;
        this.cache = cache;
        this.jdbcTemplate = jdbcTemplate;
        this.clock = clock;
        this.properties = properties;
    }

    @Scheduled(cron = "${app.cleanup-cron}", zone = "UTC")
    @Transactional
    public void cleanup() {
        Boolean locked = jdbcTemplate.queryForObject(
                "select pg_try_advisory_xact_lock(?)",
                Boolean.class,
                ADVISORY_LOCK_ID
        );
        if (!Boolean.TRUE.equals(locked)) {
            log.info("Cleanup skipped because another instance holds the advisory lock");
            return;
        }

        Instant now = clock.instant();
        int batchSize = properties.cleanupBatchSize();
        List<String> codesToEvict = new ArrayList<>();
        int deactivated = 0;

        while (true) {
            List<ShortUrl> expired = shortUrls.findAllByActiveTrueAndExpiresAtLessThanEqual(
                    now,
                    PageRequest.of(0, batchSize)
            );
            if (expired.isEmpty()) {
                break;
            }
            for (ShortUrl shortUrl : expired) {
                shortUrl.deactivate(now);
                codesToEvict.add(shortUrl.getShortCode());
            }
            shortUrls.saveAll(expired);
            shortUrls.flush();
            deactivated += expired.size();
            if (expired.size() < batchSize) {
                break;
            }
        }
        if (!codesToEvict.isEmpty()) {
            cache.evictAfterCommit(codesToEvict);
        }

        Instant hardDeleteCutoff = now.minus(properties.expiredUrlRetention());
        int deletedUrls = 0;
        while (true) {
            List<ShortUrl> oldInactive = shortUrls.findAllByActiveFalseAndDeactivatedAtLessThanEqual(
                    hardDeleteCutoff,
                    PageRequest.of(0, batchSize)
            );
            if (oldInactive.isEmpty()) {
                break;
            }
            List<Long> ids = oldInactive.stream().map(ShortUrl::getId).toList();
            deleteClicksInChunks(ids);
            shortUrls.deleteAllByIdInBatch(ids);
            deletedUrls += ids.size();
            if (ids.size() < batchSize) {
                break;
            }
        }

        Instant clickCutoff = now.minus(properties.analyticsRetention());
        int deletedClicks = 0;
        while (true) {
            int batch = clicks.deleteOlderThan(clickCutoff, batchSize);
            deletedClicks += batch;
            if (batch < batchSize) {
                break;
            }
        }

        log.info("Cleanup finished: deactivated={}, hardDeletedUrls={}, deletedOldClicks={}",
                deactivated, deletedUrls, deletedClicks);
    }

    private void deleteClicksInChunks(List<Long> ids) {
        for (int start = 0; start < ids.size(); start += CLICK_DELETE_CHUNK_SIZE) {
            int end = Math.min(start + CLICK_DELETE_CHUNK_SIZE, ids.size());
            clicks.deleteForShortUrls(ids.subList(start, end));
        }
    }
}
