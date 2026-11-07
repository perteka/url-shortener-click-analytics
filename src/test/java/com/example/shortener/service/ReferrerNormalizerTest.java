package com.example.shortener.service;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class ReferrerNormalizerTest {
    private final ReferrerNormalizer normalizer = new ReferrerNormalizer();

    @Test
    void extractsAndLowercasesHost() {
        assertEquals("example.com", normalizer.host("https://Example.COM/path?q=1"));
        assertEquals("sub.example.com", normalizer.host("http://sub.example.com:8080/page"));
        assertEquals("example.com", normalizer.host("https://user:pass@example.com/path"));
    }

    @Test
    void returnsNullForMissingOrInvalidReferrer() {
        assertNull(normalizer.host(null));
        assertNull(normalizer.host(""));
        assertNull(normalizer.host("not a url"));
        assertNull(normalizer.host("mailto:test@example.com"));
    }
}
