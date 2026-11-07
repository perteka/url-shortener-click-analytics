package com.example.shortener.dto;

import java.util.Map;

public record AnalyticsResponse(long totalClicks, Map<String, Long> byDay, Map<String, Long> byReferrer, Map<String, Long> byDevice) {}
