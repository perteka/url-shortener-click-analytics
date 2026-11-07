package com.example.shortener.controller;

import com.example.shortener.model.ApiKey;
import com.example.shortener.service.ApiKeyService;
import com.example.shortener.service.ForbiddenException;
import com.example.shortener.service.RateLimitService;
import com.example.shortener.service.UnauthorizedException;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class ApiKeyInterceptorTest {
    private final ApiKeyService apiKeys = mock(ApiKeyService.class);
    private final RateLimitService limiter = mock(RateLimitService.class);
    private final ApiKeyInterceptor interceptor = new ApiKeyInterceptor(apiKeys, limiter);

    @Test
    void bypassesAuthenticationForCorsPreflight() {
        MockHttpServletRequest request = new MockHttpServletRequest("OPTIONS", "/api/urls");
        MockHttpServletResponse response = new MockHttpServletResponse();

        assertTrue(interceptor.preHandle(request, response, new Object()));
        verifyNoInteractions(apiKeys, limiter);
        assertEquals("no-store", response.getHeader("Cache-Control"));
    }

    @Test
    void authenticatesAndRateLimitsApiRequest() {
        ApiKey key = mock(ApiKey.class);
        when(key.getId()).thenReturn(7L);
        when(apiKeys.requireActive("raw-key")).thenReturn(key);
        when(limiter.allowApiKey(7L)).thenReturn(true);
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/urls");
        request.addHeader("X-API-Key", "raw-key");
        MockHttpServletResponse response = new MockHttpServletResponse();

        assertTrue(interceptor.preHandle(request, response, new Object()));
        assertSame(key, ApiKeyInterceptor.apiKey(request));
        assertEquals("no-store", response.getHeader("Cache-Control"));
    }

    @Test
    void rejectsRequestWhenRateLimited() {
        ApiKey key = mock(ApiKey.class);
        when(key.getId()).thenReturn(7L);
        when(apiKeys.requireActive("raw-key")).thenReturn(key);
        when(limiter.allowApiKey(7L)).thenReturn(false);
        when(limiter.apiRetryAfterSeconds()).thenReturn(60L);
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/urls");
        request.addHeader("X-API-Key", "raw-key");

        RateLimitExceededException exception = assertThrows(RateLimitExceededException.class,
                () -> interceptor.preHandle(request, new MockHttpServletResponse(), new Object()));
        assertEquals(60, exception.getRetryAfterSeconds());
    }

    @Test
    void rejectsMissingAuthenticationAttribute() {
        assertThrows(UnauthorizedException.class,
                () -> ApiKeyInterceptor.apiKey(new MockHttpServletRequest()));
    }

    @Test
    void requiresAdminForKeyManagement() {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/keys");
        ApiKey regular = mock(ApiKey.class);
        when(regular.isAdmin()).thenReturn(false);
        request.setAttribute(ApiKeyInterceptor.API_KEY_ATTRIBUTE, regular);

        assertThrows(ForbiddenException.class, () -> ApiKeyInterceptor.requireAdmin(request));

        ApiKey admin = mock(ApiKey.class);
        when(admin.isAdmin()).thenReturn(true);
        request.setAttribute(ApiKeyInterceptor.API_KEY_ATTRIBUTE, admin);
        assertSame(admin, ApiKeyInterceptor.requireAdmin(request));
    }
}
