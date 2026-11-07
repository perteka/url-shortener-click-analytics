package com.example.shortener.service;

import com.example.shortener.model.DeviceType;
import com.example.shortener.model.ShortUrl;
import com.example.shortener.repository.ShortUrlRepository;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.util.Optional;

@Service
public class RedirectService {
    private final ShortUrlRepository shortUrls;
    private final CacheService cache;
    private final ApplicationEventPublisher events;
    private final ReferrerNormalizer referrerNormalizer;
    private final Clock clock;

    public RedirectService(ShortUrlRepository shortUrls,
                            CacheService cache,
                            ApplicationEventPublisher events,
                            ReferrerNormalizer referrerNormalizer,
                            Clock clock) {
        this.shortUrls = shortUrls;
        this.cache = cache;
        this.events = events;
        this.referrerNormalizer = referrerNormalizer;
        this.clock = clock;
    }

    @Transactional
    public String redirect(String code, String rawReferrer, DeviceType deviceType) {
        Instant now = clock.instant();
        Optional<CacheService.Entry> cached = cache.get(code);
        Long id;
        String target;
        if (cached.isPresent()) {
            id = cached.get().id();
            target = cached.get().url();
        } else {
            ShortUrl entity = shortUrls.findByShortCodeAndActiveTrue(code)
                    .filter(url -> !url.isExpired(now))
                    .orElseThrow(() -> new NotFoundException("Short URL not found"));
            id = entity.getId();
            target = entity.getOriginalUrl();
            cache.put(code, id, target, entity.getExpiresAt());
        }

        String referrerHost = referrerNormalizer.host(rawReferrer);
        DeviceType resolvedDevice = deviceType == null ? DeviceType.UNKNOWN : deviceType;
        events.publishEvent(new RedirectEvent(id, referrerHost, resolvedDevice, now));
        return target;
    }
}
