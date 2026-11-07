package com.example.shortener.service;

import com.example.shortener.config.AppProperties;
import org.springframework.stereotype.Component;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.util.HexFormat;

@Component
public class ApiKeyHasher {
    private final byte[] pepper;

    public ApiKeyHasher(AppProperties properties) {
        this.pepper = properties.apiKeyPepper().getBytes(StandardCharsets.UTF_8);
    }

    public String hash(String rawKey) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(pepper, "HmacSHA256"));
            return HexFormat.of().formatHex(mac.doFinal(rawKey.getBytes(StandardCharsets.UTF_8)));
        } catch (GeneralSecurityException exception) {
            throw new IllegalStateException("Unable to hash API key", exception);
        }
    }
}
