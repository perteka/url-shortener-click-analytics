package com.example.shortener.service;

import com.example.shortener.model.DeviceType;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Locale;

import static org.junit.jupiter.api.Assertions.assertEquals;

class DeviceClassifierTest {
    private final DeviceClassifier classifier = new DeviceClassifier();
    private Locale originalLocale;

    @BeforeEach
    void rememberLocale() {
        originalLocale = Locale.getDefault();
    }

    @AfterEach
    void restoreLocale() {
        Locale.setDefault(originalLocale);
    }

    @Test
    void classifiesCommonUserAgents() {
        assertEquals(DeviceType.MOBILE,
                classifier.classify("Mozilla/5.0 (Linux; Android 13; Pixel 7) Mobile Safari/537.36"));
        assertEquals(DeviceType.TABLET,
                classifier.classify("Mozilla/5.0 (Linux; Android 13; SM-X700) AppleWebKit/537.36"));
        assertEquals(DeviceType.TABLET, classifier.classify("Mozilla/5.0 (iPad; CPU OS 17_0)"));
        assertEquals(DeviceType.BOT, classifier.classify("Googlebot/2.1 (+http://www.google.com/bot.html)"));
        assertEquals(DeviceType.DESKTOP,
                classifier.classify("Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36"));
        assertEquals(DeviceType.UNKNOWN, classifier.classify(null));
        assertEquals(DeviceType.UNKNOWN, classifier.classify("  "));
    }

    @Test
    void classificationDoesNotDependOnDefaultLocale() {
        Locale.setDefault(Locale.forLanguageTag("tr-TR"));

        assertEquals(DeviceType.MOBILE, classifier.classify("MOBILE"));
        assertEquals(DeviceType.DESKTOP, classifier.classify("WINDOWS"));
    }
}
