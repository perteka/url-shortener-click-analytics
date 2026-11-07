package com.example.shortener.dto;

public record CreatedApiKeyResponse(
        Long id,
        String name,
        String apiKey
) {
}
