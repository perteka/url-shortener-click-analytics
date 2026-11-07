package com.example.shortener.service;

import com.example.shortener.model.DeviceType;

import java.time.Instant;

public record RedirectEvent(
        Long shortUrlId,
        String referrerHost,
        DeviceType deviceType,
        Instant clickedAt
) {
}
