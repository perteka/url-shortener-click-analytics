package com.example.shortener.service;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.Arrays;
import java.util.Set;
import java.util.stream.Collectors;

@Component
public class ClientIpResolver {
    private final Set<String> trustedProxies;

    public ClientIpResolver(@Value("${app.trusted-proxies:}") String trustedProxyAddresses) {
        this.trustedProxies = Arrays.stream(trustedProxyAddresses.split(","))
                .map(String::trim)
                .filter(value -> !value.isEmpty())
                .map(ClientIpResolver::normalizeAddress)
                .collect(Collectors.toUnmodifiableSet());
    }

    public String resolve(HttpServletRequest request) {
        String peerAddress = request.getRemoteAddr();
        String normalizedPeer = tryNormalizeAddress(peerAddress);
        if (normalizedPeer == null || !trustedProxies.contains(normalizedPeer)) {
            return peerAddress;
        }

        String forwarded = request.getHeader("X-Forwarded-For");
        if (forwarded == null || forwarded.isBlank()) {
            return peerAddress;
        }

        String[] chain = forwarded.split(",");
        for (int index = chain.length - 1; index >= 0; index--) {
            String address = tryNormalizeAddress(chain[index].trim());
            if (address == null) {
                return peerAddress;
            }
            if (!trustedProxies.contains(address)) {
                return address;
            }
        }
        return peerAddress;
    }

    private static String normalizeAddress(String address) {
        String normalized = tryNormalizeAddress(address);
        if (normalized == null) {
            throw new IllegalArgumentException("TRUSTED_PROXIES must contain valid IP addresses");
        }
        return normalized;
    }

    private static String tryNormalizeAddress(String address) {
        if (address == null || address.isBlank()
                || !address.matches("[0-9a-fA-F:.]+")
                || (!address.contains(":") && !address.matches("[0-9]{1,3}(\\.[0-9]{1,3}){3}"))) {
            return null;
        }
        try {
            return InetAddress.getByName(address).getHostAddress().toLowerCase();
        } catch (UnknownHostException exception) {
            return null;
        }
    }
}
