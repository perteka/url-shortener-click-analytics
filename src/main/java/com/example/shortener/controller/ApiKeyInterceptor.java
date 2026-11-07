package com.example.shortener.controller;

import com.example.shortener.model.ApiKey;
import com.example.shortener.service.ApiKeyService;
import com.example.shortener.service.ForbiddenException;
import com.example.shortener.service.RateLimitService;
import com.example.shortener.service.UnauthorizedException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.HttpHeaders;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

@Component
public class ApiKeyInterceptor implements HandlerInterceptor {
    public static final String API_KEY_ATTRIBUTE = ApiKeyInterceptor.class.getName() + ".apiKey";

    private final ApiKeyService apiKeys;
    private final RateLimitService rateLimiter;

    public ApiKeyInterceptor(ApiKeyService apiKeys, RateLimitService rateLimiter) {
        this.apiKeys = apiKeys;
        this.rateLimiter = rateLimiter;
    }

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
        response.setHeader(HttpHeaders.CACHE_CONTROL, "no-store");
        response.setHeader(HttpHeaders.PRAGMA, "no-cache");
        if ("OPTIONS".equalsIgnoreCase(request.getMethod())) {
            return true;
        }
        ApiKey key = apiKeys.requireActive(request.getHeader("X-API-Key"));
        if (!rateLimiter.allowApiKey(key.getId())) {
            throw new RateLimitExceededException(rateLimiter.apiRetryAfterSeconds());
        }
        request.setAttribute(API_KEY_ATTRIBUTE, key);
        return true;
    }

    public static ApiKey apiKey(HttpServletRequest request) {
        Object value = request.getAttribute(API_KEY_ATTRIBUTE);
        if (value instanceof ApiKey apiKey) {
            return apiKey;
        }
        throw new UnauthorizedException("Invalid API key");
    }

    public static ApiKey requireAdmin(HttpServletRequest request) {
        ApiKey apiKey = apiKey(request);
        if (!apiKey.isAdmin()) {
            throw new ForbiddenException("Admin API key required");
        }
        return apiKey;
    }
}
