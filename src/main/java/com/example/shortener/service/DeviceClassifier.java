package com.example.shortener.service;

import com.example.shortener.model.DeviceType;
import org.springframework.stereotype.Component;

import java.util.Locale;

@Component
public class DeviceClassifier {
    public DeviceType classify(String userAgent) {
        if (userAgent == null || userAgent.isBlank()) {
            return DeviceType.UNKNOWN;
        }
        String value = userAgent.toLowerCase(Locale.ROOT);
        if (value.contains("bot") || value.contains("crawler") || value.contains("spider")) {
            return DeviceType.BOT;
        }
        if (value.contains("ipad") || value.contains("tablet")
                || (value.contains("android") && !value.contains("mobile"))) {
            return DeviceType.TABLET;
        }
        if (value.contains("mobile") || value.contains("android") || value.contains("iphone")) {
            return DeviceType.MOBILE;
        }
        return DeviceType.DESKTOP;
    }
}
