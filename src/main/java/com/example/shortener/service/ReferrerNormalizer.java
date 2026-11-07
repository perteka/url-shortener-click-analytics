package com.example.shortener.service;

import org.springframework.stereotype.Component;

import java.net.URI;
import java.util.Locale;

@Component
public class ReferrerNormalizer {
    public String host(String referrer) {
        if (referrer == null || referrer.isBlank()) {
            return null;
        }
        try {
            URI uri = URI.create(referrer.trim());
            String host = uri.getHost();
            return host == null || host.length() > 255 ? null : host.toLowerCase(Locale.ROOT);
        } catch (RuntimeException exception) {
            return null;
        }
    }
}
