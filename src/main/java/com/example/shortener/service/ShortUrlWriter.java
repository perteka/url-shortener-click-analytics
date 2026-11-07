package com.example.shortener.service;

import com.example.shortener.model.ShortUrl;
import com.example.shortener.repository.ApiKeyRepository;
import com.example.shortener.repository.ShortUrlRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;

@Service
public class ShortUrlWriter {
    private final ShortUrlRepository shortUrls;
    private final ApiKeyRepository apiKeys;

    public ShortUrlWriter(ShortUrlRepository shortUrls, ApiKeyRepository apiKeys) {
        this.shortUrls = shortUrls;
        this.apiKeys = apiKeys;
    }

    @Transactional
    public ShortUrl persist(String code, String url, Long ownerId, Instant expiresAt) {
        ShortUrl entity = new ShortUrl(code, url, apiKeys.getReferenceById(ownerId), expiresAt);
        return shortUrls.saveAndFlush(entity);
    }
}
