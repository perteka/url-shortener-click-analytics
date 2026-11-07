package com.example.shortener.service;

import com.example.shortener.config.AppProperties;
import com.example.shortener.dto.ShortUrlResponse;
import com.example.shortener.model.ShortUrl;
import org.springframework.stereotype.Component;

@Component
public class ShortUrlMapper {
    private final String baseUrl;

    public ShortUrlMapper(AppProperties properties) {
        this.baseUrl = properties.baseUrl().replaceAll("/+$", "");
    }

    public ShortUrlResponse toResponse(ShortUrl entity) {
        return new ShortUrlResponse(
                entity.getShortCode(),
                baseUrl + "/" + entity.getShortCode(),
                entity.getOriginalUrl(),
                entity.isActive(),
                entity.getCreatedAt(),
                entity.getExpiresAt()
        );
    }
}
