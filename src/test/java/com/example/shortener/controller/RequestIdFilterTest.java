package com.example.shortener.controller;

import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class RequestIdFilterTest {
    private final RequestIdFilter filter = new RequestIdFilter();

    @Test
    void acceptsSafeCallerSuppliedId() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/urls");
        request.addHeader(RequestIdFilter.HEADER, "client-request-123");
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilter(request, response, new MockFilterChain());

        assertEquals("client-request-123", response.getHeader(RequestIdFilter.HEADER));
        assertEquals("client-request-123", RequestIdFilter.requestId(request));
        assertNull(MDC.get(RequestIdFilter.MDC_KEY));
    }

    @Test
    void replacesUnsafeCallerSuppliedId() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/urls");
        request.addHeader(RequestIdFilter.HEADER, "bad\nid");
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilter(request, response, new MockFilterChain());

        String generated = response.getHeader(RequestIdFilter.HEADER);
        assertNotEquals("bad\nid", generated);
        assertDoesNotThrow(() -> UUID.fromString(generated));
    }

    @Test
    void generatesIdWhenHeaderIsMissing() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/urls");
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilter(request, response, new MockFilterChain());

        assertDoesNotThrow(() -> UUID.fromString(response.getHeader(RequestIdFilter.HEADER)));
    }

    @Test
    void reusesExistingIdOnErrorDispatch() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/error");
        request.setAttribute(RequestIdFilter.REQUEST_ID_ATTRIBUTE, "original-request-1");
        request.addHeader(RequestIdFilter.HEADER, "different-header-value");
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilter(request, response, new MockFilterChain());

        assertEquals("original-request-1", response.getHeader(RequestIdFilter.HEADER));
        assertEquals("original-request-1", RequestIdFilter.requestId(request));
    }
}
