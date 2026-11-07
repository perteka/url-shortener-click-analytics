package com.example.shortener.service;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.Arrays;
import java.util.Locale;
import java.util.Set;
import java.util.stream.Collectors;

@Component
public class DestinationHostPolicy {
    private final Set<String> allowedHosts;

    public DestinationHostPolicy(@Value("${app.allowed-destination-hosts:}") String configuredHosts) {
        this.allowedHosts = Arrays.stream(configuredHosts.split(","))
                .map(String::trim)
                .filter(host -> !host.isEmpty())
                .map(host -> host.toLowerCase(Locale.ROOT))
                .collect(Collectors.toUnmodifiableSet());
    }

    public boolean isAllowed(String host) {
        return allowedHosts.isEmpty()
                || (host != null && allowedHosts.contains(host.toLowerCase(Locale.ROOT)));
    }
}
