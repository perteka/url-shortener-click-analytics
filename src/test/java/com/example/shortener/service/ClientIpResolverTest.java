package com.example.shortener.service;

import jakarta.servlet.http.HttpServletRequest;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class ClientIpResolverTest {
    @Test
    void ignoresForwardedHeaderFromUntrustedPeer() {
        ClientIpResolver resolver = new ClientIpResolver("");
        HttpServletRequest request = mock(HttpServletRequest.class);
        when(request.getRemoteAddr()).thenReturn("203.0.113.20");
        when(request.getHeader("X-Forwarded-For")).thenReturn("198.51.100.1");

        assertEquals("203.0.113.20", resolver.resolve(request));
    }

    @Test
    void resolvesClientAddressFromTrustedProxyChain() {
        ClientIpResolver resolver = new ClientIpResolver("10.0.0.10, 10.0.0.11");
        HttpServletRequest request = mock(HttpServletRequest.class);
        when(request.getRemoteAddr()).thenReturn("10.0.0.10");
        when(request.getHeader("X-Forwarded-For"))
                .thenReturn("203.0.113.200, 198.51.100.15, 10.0.0.11");

        assertEquals("198.51.100.15", resolver.resolve(request));
    }

    @Test
    void fallsBackToRemoteAddressWithoutForwardedHeader() {
        ClientIpResolver resolver = new ClientIpResolver("10.0.0.10");
        HttpServletRequest request = mock(HttpServletRequest.class);
        when(request.getRemoteAddr()).thenReturn("10.0.0.10");
        when(request.getHeader("X-Forwarded-For")).thenReturn(null);

        assertEquals("10.0.0.10", resolver.resolve(request));
    }

    @Test
    void rejectsInvalidTrustedProxyConfiguration() {
        org.junit.jupiter.api.Assertions.assertThrows(
                IllegalArgumentException.class,
                () -> new ClientIpResolver("proxy.example.com")
        );
    }
}
