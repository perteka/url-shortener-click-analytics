package com.example.shortener.controller;

import com.example.shortener.dto.AnalyticsResponse;
import com.example.shortener.service.AnalyticsService;
import com.example.shortener.service.BadRequestException;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/urls/{code}/analytics")
public class AnalyticsController {
    private final AnalyticsService service;

    public AnalyticsController(AnalyticsService service) {
        this.service = service;
    }

    @GetMapping
    public AnalyticsResponse get(@PathVariable String code,
                                 @RequestParam(defaultValue = "30") int days,
                                 HttpServletRequest request) {
        if (days < 1 || days > 365) {
            throw new BadRequestException("days must be between 1 and 365");
        }
        return service.analytics(code, days, ApiKeyInterceptor.apiKey(request));
    }
}
