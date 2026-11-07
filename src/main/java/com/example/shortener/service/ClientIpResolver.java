package com.example.shortener.service;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.stereotype.Component;

/**
 * Resolves the best-effort client IP for rate limiting.
 * Behind a reverse proxy the first hop of X-Forwarded-For is authoritative;
 * otherwise the socket peer address is used.
 */
@Component
public class ClientIpResolver {
    public String resolve(HttpServletRequest request) {
        String forwarded = request.getHeader("X-Forwarded-For");
        if (forwarded != null) {
            for (String candidate : forwarded.split(",")) {
                String ip = candidate.trim();
                if (!ip.isEmpty()) {
                    return ip;
                }
            }
        }
        return request.getRemoteAddr();
    }
}
